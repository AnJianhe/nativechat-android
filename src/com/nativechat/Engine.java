package com.nativechat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次生成的完整编排，逐条对应网页版 `store.ts` 的 sendMessage / streamReply：
 *
 * 1. {@link #send} / {@link #regenerate} / {@link #edit} 在消息树上新增分支；
 * 2. 后台线程先补齐附件 file_id（{@link #prepare}），再按上下文规则组装请求；
 * 3. 收流 → 合并事件 → 落盘；
 * 4. 服务端判定 file_id 失效时清空对应的 serverId、退避 2^n 秒后重发，最多 2 次；
 * 5. 中止（{@link #stop}）立即生效，已收内容保留为已停止。
 *
 * 界面只通过 {@link Listener} 拿快照，不直接碰线程。
 */
public final class Engine {
    public interface Listener {
        /** 流式过程中的回复快照（主线程，已按 ~90ms 节流） */
        void onReply(Msg reply);

        /** 服务端生成了会话标题 */
        void onTitle(String title);

        /** 本轮结束（完成/停止/出错） */
        void onDone(Msg reply);

        /** 附件 serverId 有更新，界面需要刷新 */
        void onFilesChanged();
    }

    private final Context context;
    private final Db db;
    private final Settings settings;
    private final Attachments attachments;
    private volatile Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final Object PROCESS_LOCK = new Object();
    private static volatile boolean processStreaming;
    private volatile Api.Call call;
    private volatile boolean streaming;
    private volatile String activeSession;
    private long lastPublish;
    private boolean publishPending;

    public Engine(Context context, Db db, Listener listener) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.settings = new Settings(context);
        this.attachments = new Attachments(context);
        this.listener = listener;
    }

    public boolean isStreaming() {
        return processStreaming;
    }

    public String activeSession() {
        return activeSession;
    }

    public Attachments attachments() {
        return attachments;
    }

    /** 停止生成：断开连接，已收内容按「已停止」收尾。 */
    public void stop() {
        Api.Call current = call;
        if (current != null) {
            current.cancel();
        }
    }

    /** Activity 销毁后取消请求并丢弃已排队的界面通知。 */
    public void close() {
        listener = null;
        stop();
    }

    private void notifyListener(java.util.function.Consumer<Listener> action) {
        main.post(() -> {
            Listener target = listener;
            if (target != null) action.accept(target);
        });
    }

    // ================================================================ 入口

    /** 发送一条新提问；sessionId 为 null 时新建会话。 */
    public boolean send(String sessionId, String text, List<Msg.FileAtt> files, boolean thinking,
                        boolean search) {
        synchronized (PROCESS_LOCK) {
        if (processStreaming) {
            return false;
        }
        long now = System.currentTimeMillis();
        Db.Session session = sessionId == null ? null : db.session(sessionId);
        if (session == null) {
            session = new Db.Session();
            session.id = Util.newId();
            session.title = "";
            session.createdAt = now;
            session.updatedAt = now;
            db.saveSession(session);
        }
        List<Msg> history = db.visibleMessages(session.id);
        String parentId = history.isEmpty() ? null : history.get(history.size() - 1).id;

        Msg question = new Msg();
        question.id = Util.newId();
        question.sessionId = session.id;
        question.role = Msg.USER;
        question.content = text == null ? "" : text;
        question.createdAt = now;
        question.parentId = parentId;
        for (Msg.FileAtt file : files) {
            question.files.add(file);
            db.saveFile(file);
        }
        Msg reply = newReply(session.id, question.id, now + 1, thinking, search);

        appendBranch(session, parentId, Arrays.asList(question, reply));
        startStream(session.id, reply, history, question, null);
        return true;
    
        }
    }

    /** 重新生成：在原问题下新增版本，不覆盖旧回答和后续对话。 */
    public boolean regenerate(String sessionId, String replyId) {
        synchronized (PROCESS_LOCK) {
        if (processStreaming) {
            return false;
        }
        Db.Session session = db.session(sessionId);
        if (session == null) {
            return false;
        }
        List<Msg> path = db.visibleMessages(sessionId);
        int index = replyId == null ? path.size() - 1 : indexOf(path, replyId);
        if (index <= 0 || index >= path.size()) {
            return false;
        }
        Msg question = path.get(index - 1);
        if (!question.isUser() || path.get(index).isUser()) {
            return false;
        }
        List<Msg> history = new ArrayList<>(path.subList(0, index - 1));
        Msg reply = newReply(sessionId, question.id, System.currentTimeMillis(),
            path.get(index).thinking, path.get(index).search);
        appendBranch(session, question.id, Arrays.asList(reply));
        startStream(sessionId, reply, history, question, null);
        return true;
    
        }
    }

    /** 编辑提问：新增同父版本，保留原附件；旧问题的回答与后续对话仍可切回。 */
    public boolean edit(String sessionId, String userId, String content) {
        synchronized (PROCESS_LOCK) {
        if (processStreaming) {
            return false;
        }
        Db.Session session = db.session(sessionId);
        if (session == null) {
            return false;
        }
        List<Msg> path = db.visibleMessages(sessionId);
        int index = indexOf(path, userId);
        if (index < 0) {
            return false;
        }
        Msg previous = path.get(index);
        if (!previous.isUser()) {
            return false;
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() && previous.files.isEmpty()) {
            return false;
        }
        String parentId = index == 0 ? null : path.get(index - 1).id;
        Msg question = new Msg();
        question.id = Util.newId();
        question.sessionId = sessionId;
        question.role = Msg.USER;
        question.content = text;
        question.createdAt = System.currentTimeMillis();
        question.parentId = parentId;
        question.files.addAll(previous.files);
        Msg reply = newReply(sessionId, question.id, question.createdAt + 1,
            settings.thinking(), settings.search());
        appendBranch(session, parentId, Arrays.asList(question, reply));
        startStream(sessionId, reply, new ArrayList<>(path.subList(0, index)), question, null);
        return true;
    
        }
    }

    /** 继续最后一条未完成的回答：保留原消息与开关，把新流的内容追加到原块之后。 */
    public boolean continueReply(String sessionId, String replyId) {
        synchronized (PROCESS_LOCK) {
        if (processStreaming) {
            return false;
        }
        Db.Session session = db.session(sessionId);
        if (session == null) {
            return false;
        }
        List<Msg> path = db.visibleMessages(sessionId);
        if (path.size() < 2) {
            return false;
        }
        Msg question = path.get(path.size() - 2);
        Msg previous = path.get(path.size() - 1);
        if (!question.isUser() || previous.isUser() || !previous.id.equals(replyId)
            || !Prompt.canContinueReply(previous)) {
            return false;
        }
        Msg reply = previous.copy();
        reply.sessionId = sessionId;
        reply.model = previous.model == null ? settings.model() : previous.model;
        reply.thinking = previous.thinking;
        reply.search = previous.search;
        reply.status = Msg.PENDING;
        reply.stopReason = null;
        reply.stopDetails = null;
        reply.error = null;
        db.saveMessage(reply);
        db.touchSession(sessionId);
        startStream(sessionId, reply, new ArrayList<>(path.subList(0, path.size() - 2)),
            question, previous);
        return true;
    
        }
    }

    /** 附件补传失败后重试：保留原回答位置，重新补传并请求。 */
    public boolean retryUpload(String sessionId, String replyId) {
        synchronized (PROCESS_LOCK) {
        if (processStreaming) {
            return false;
        }
        Db.Session session = db.session(sessionId);
        if (session == null) {
            return false;
        }
        List<Msg> path = db.visibleMessages(sessionId);
        int index = indexOf(path, replyId);
        if (index <= 0) {
            return false;
        }
        Msg question = path.get(index - 1);
        Msg previous = path.get(index);
        if (!question.isUser() || previous.isUser() || previous.error == null
            || !Api.UPLOAD.equals(previous.error.optString("kind"))) {
            return false;
        }
        Msg reply = previous.copy();
        reply.sessionId = sessionId;
        reply.status = Msg.PENDING;
        reply.error = null;
        db.saveMessage(reply);
        startStream(sessionId, reply, new ArrayList<>(path.subList(0, index - 1)), question, null);
        return true;
    
        }
    }

    /** 切换版本：只允许切到当前路径上某节点的兄弟版本，并恢复该版本已选的后续分支。 */
    public void selectVersion(String sessionId, String messageId) {
        synchronized (PROCESS_LOCK) {
        if (processStreaming) return;
        Msg target = db.message(messageId);
        if (target == null) {
            return;
        }
        List<Msg> path = db.visibleMessages(sessionId);
        boolean onPath = false;
        for (Msg message : path) {
            if (java.util.Objects.equals(message.parentId, target.parentId)) {
                onPath = true;
                break;
            }
        }
        if (!onPath) {
            return;
        }
        if (target.parentId == null) {
            db.setSessionRoot(sessionId, target.id);
        } else {
            db.selectChild(target.parentId, target.id);
        }
        db.touchSession(sessionId);
        }
    }

    /** 页面打开时把上次没结束的回复按「已停止」收尾。 */
    public void settleInterrupted(String sessionId) {
        synchronized (PROCESS_LOCK) {
        if (processStreaming) return;
        List<Msg> all = db.messages(sessionId);
        for (Msg message : all) {
            if (!message.isUser()
                && (Msg.PENDING.equals(message.status) || Msg.STREAMING.equals(message.status))) {
                Prompt.settleReply(message, Msg.STOPPED, System.currentTimeMillis(), null);
                db.saveMessage(message);
            }
        }
        }
    }

    /** 删除与流的启动及最终落盘互斥。 */
    public boolean deleteSession(String sessionId) {
        synchronized (PROCESS_LOCK) {
            if (processStreaming) return false;
            db.deleteSession(sessionId);
            return true;
        }
    }

    // ================================================================ 流式

    private void startStream(final String sessionId, final Msg reply, final List<Msg> history,
                             final Msg question, final Msg prefix) {
        streaming = true;
        activeSession = sessionId;
        final long streamStart = SystemClock.uptimeMillis();
        final int[] eventCount = {0};
        Diag.log("STREAM", "开始 · 会话 " + ChatActivity.shortId(sessionId) + " · 历史 "
            + history.size() + " 条 · 模型 " + (reply.model == null ? settings.model() : reply.model)
            + " · 思考 " + reply.thinking + " · 搜索 " + reply.search
            + " · 附件 " + question.files.size() + " · " + Diag.heap());
        final Api.Call sessionCall = new Api.Call();
        call = sessionCall;
        final String apiKey = settings.apiKey();
        final String origin = settings.origin();
        final String model = reply.model == null ? settings.model() : reply.model;
        final boolean thinking = reply.thinking;
        final boolean search = reply.search;
        Db.Session session = db.session(sessionId);
        final boolean wantTitle = session == null || session.title == null
            || session.title.isEmpty();

        Thread worker = new Thread(() -> {
            Msg current = reply;
            boolean[] receivedContent = {false};
            boolean[] titleDone = {!wantTitle};
            Map<Integer, Integer> blockIndexes = new java.util.HashMap<>();
            JSONArray prepared = null;
            boolean preparing = true;
            try {
                for (int attempt = 0; ; attempt++) {
                    preparing = true;
                    try {
                        Request request = prepare(sessionId, history, question, origin, apiKey,
                            sessionCall);
                        if (sessionCall.isCancelled()) {
                            Prompt.settleReply(current, Msg.STOPPED, System.currentTimeMillis(),
                                null);
                            break;
                        }
                        preparing = false;
                        String reminder = prefix != null
                            ? prefix.latestReminder
                            : Prompt.createLatestReminder(settings.reminderLocation(),
                            settings.reminderLanguage(), System.currentTimeMillis());
                        current.latestReminder = reminder;
                        current.status = Msg.PENDING;
                        db.saveMessage(current);

                        JSONArray messages = Prompt.toRequestMessages(request.history,
                            withTime(request.question, current.latestReminder), prefix,
                            current.thinking, reminder,
                            settings.sendHistoryThinking(),
                            compactIfNeeded(sessionId, request.history, origin, apiKey, model,
                                sessionCall),
                            Math.max(4000, settings.contextLimit() - 4000));
                        Diag.log("PREP", "请求 " + messages.length() + " 条 · 估算 "
                            + Prompt.estimateTokens(messages) + " tokens");
                        prepared = messages;
                        boolean withFiles = Prompt.hasFileBlocks(messages);
                        JSONObject body = Prompt.buildRequest(model, messages, thinking, search,
                            !titleDone[0], settings.temperature());

                        final Object eventLock = new Object();
                        final boolean[] eventsClosed = {false};
                        try {
                        Api.streamMessages(origin, apiKey, body, withFiles, sessionCall, event -> {
                            synchronized (eventLock) {
                            if (eventsClosed[0] || sessionCall.isCancelled()) return;
                            JSONObject usable = Prompt.readEvent(event);
                            if (usable == null) {
                                return;
                            }
                            String type = usable.optString("type", "");
                            eventCount[0]++;
                            if ("content_block_start".equals(type)) {
                                receivedContent[0] = true;
                            }
                            if ("message_delta".equals(type)) {
                                captureTitle(sessionId, usable, sessionCall, titleDone);
                            }
                            Prompt.applyEvent(current, usable, System.currentTimeMillis(),
                                blockIndexes);
                            publish(current, false);
                            }
                        });
                        } finally {
                            synchronized (eventLock) { eventsClosed[0] = true; }
                        }
                        Prompt.settleReply(current, sessionCall.isCancelled() ? Msg.STOPPED : Msg.DONE,
                            System.currentTimeMillis(), null);
                        break;
                    } catch (Api.ApiError error) {
                        Diag.log("ERROR", "请求失败 · " + error.kind + " " + error.status
                            + " · " + error.getMessage());
                        if (sessionCall.isCancelled()) {
                            Prompt.settleReply(current, Msg.STOPPED, System.currentTimeMillis(),
                                null);
                            break;
                        }
                        if (preparing) {
                            Prompt.settleReply(current, Msg.ERROR, System.currentTimeMillis(),
                                uploadError(error));
                            break;
                        }
                        if (attempt < Prompt.FILE_RETRY_LIMIT && !receivedContent[0]
                            && reuploadInvalidated(sessionId, prepared, error)) {
                            waitForRetry(attempt, sessionCall);
                            if (sessionCall.isCancelled()) {
                                Prompt.settleReply(current, Msg.STOPPED,
                                    System.currentTimeMillis(), null);
                                break;
                            }
                            continue;
                        }
                        if (Api.INTERRUPTED.equals(error.kind) && current.stopReason != null) {
                            // 已收到 message_delta 的结束原因，只是缺了结尾的 message_stop
                            Prompt.settleReply(current, Msg.DONE, System.currentTimeMillis(), null);
                        } else {
                            Prompt.settleReply(current, Msg.ERROR, System.currentTimeMillis(),
                                error.toJson());
                        }
                        break;
                    }
                }
            } catch (Throwable error) {
                Prompt.settleReply(current, Msg.ERROR, System.currentTimeMillis(), protocol(error));
            } finally {
                try {
                    db.saveMessage(current);
                    db.touchSession(sessionId);
                } finally {
                synchronized (PROCESS_LOCK) {
                    streaming = false;
                    call = null;
                    processStreaming = false;
                }
                }
                publish(current, true);
                final Msg settled = current.copy();
                Diag.log("STREAM", "结束 · " + settled.status + " · 事件 " + eventCount[0]
                    + " · 正文 " + settled.replyText().length() + " 字 · 用时 "
                    + (SystemClock.uptimeMillis() - streamStart) + "ms · " + Diag.heap());
                notifyListener(target -> target.onDone(settled));
            }
        }, "chat-stream");
        worker.setDaemon(true);
        processStreaming = true;
        try {
            worker.start();
        } catch (RuntimeException | Error error) {
            processStreaming = false;
            streaming = false;
            call = null;
            throw error;
        }
    }

    /** 标题只在首轮、且用户还没手动命名时写入。 */
    private void captureTitle(String sessionId, JSONObject event, Api.Call sessionCall,
                              boolean[] titleDone) {
        if (titleDone[0] || sessionCall.isCancelled()) {
            return;
        }
        JSONObject delta = event.optJSONObject("delta");
        if (delta == null) {
            return;
        }
        String stop = delta.optString("stop_reason", "");
        if (!"end_turn".equals(stop) && !"max_tokens".equals(stop)) {
            return;
        }
        String title = delta.optString("title", "").trim();
        if (title.isEmpty()) {
            return;
        }
        Db.Session session = db.session(sessionId);
        if (session == null || (session.title != null && !session.title.isEmpty())) {
            titleDone[0] = true;
            return;
        }
        db.renameSession(sessionId, title);
        titleDone[0] = true;
        notifyListener(target -> target.onTitle(title));
    }

    private void publish(Msg reply, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastPublish < 350) {
            publishPending = true;
            return;
        }
        lastPublish = now;
        publishPending = false;
        final Msg snapshot = reply.copy();
        notifyListener(target -> target.onReply(snapshot));
    }

    // ================================================================ 附件补齐

    private static final class Request {
        List<Msg> history;
        Msg question;
    }

    /**
     * 发送前补齐附件的 serverId：
     * - 只补本轮提问的附件；失败即整体失败（记为 upload 错误，可重试）。
     * - 历史轮次的附件不再批量补传：老图没有 serverId 就不进请求，只在本机展示。
     *   这样长会话第一次发送不会因为几十张图排队上传而卡几十秒。
     */
    private Request prepare(String sessionId, List<Msg> history, Msg question, String origin,
                            String apiKey, Api.Call call) throws Api.ApiError {
        Request request = new Request();
        long since = Diag.now();
        request.question = refresh(question);
        request.history = refreshAll(history);

        for (Msg.FileAtt file : request.question.files) {
            if (call.isCancelled()) {
                break;
            }
            long started = Diag.now();
            if (ensureServerId(sessionId, file, origin, apiKey, call) == null
                && file.serverId == null && !attachments.exists(file)) {
                // 没有本地原件也没有 serverId：只展示，不进入请求
                continue;
            }
            Diag.log("PREP", "本轮附件 " + file.name + " · " + Diag.millis(started) + "ms");
        }

        request.question = refreshFiles(refresh(question));
        request.history = refreshAll(history);
        boolean hasFile = false;
        for (Msg.FileAtt file : request.question.files) {
            if (file.serverId != null && !file.serverId.isEmpty()) {
                hasFile = true;
                break;
            }
        }
        if ((request.question.content == null || request.question.content.isEmpty()) && !hasFile) {
            throw new Api.ApiError(Api.UPLOAD,
                "这次只有附件，但附件没有成功上传，先重试一次", -1);
        }
        Diag.log("PREP", "准备完成 · 历史 " + request.history.size() + " 条 · 附件 "
            + request.question.files.size() + " · " + Diag.millis(since) + "ms");
        return request;
    }

    /**
     * serverId 只落在 files 表里（消息 JSON 不再回写），所以从库里重读消息后要把它们补回来，
     * 否则附件会被当成「没有 serverId」而丢掉，请求里就只剩空内容。
     */
    private Msg refreshFiles(Msg message) {
        for (Msg.FileAtt file : message.files) {
            if (file.serverId != null && !file.serverId.isEmpty()) {
                continue;
            }
            Msg.FileAtt stored = db.file(file.id);
            if (stored != null && stored.serverId != null && !stored.serverId.isEmpty()) {
                file.serverId = stored.serverId;
            }
        }
        return message;
    }

    /**
     * 本轮提问的副本，末尾追加一行当前时间。
     * 快照（latest_reminder）被服务端限定为五段塞不进时间，system 又只有那 16 个字，
     * 所以把时间放在整段对话的最末尾——既在上下文里，也不破坏任何格式约束。
     */
    private static Msg withTime(Msg question, String reminder) {
        if (question == null) {
            return null;
        }
        Msg copy = question.copy();
        copy.content = (question.content == null ? "" : question.content)
            + "\n\n（当前时间 " + Prompt.timeText(System.currentTimeMillis()) + "）";
        return copy;
    }

    // ================================================================ 上下文压缩
    /** 保留最近多少轮不折叠。 */
    private static final int KEEP_ROUNDS = 20;

    /**
     * 上下文接近上限时，把较早的轮次压成摘要。
     * 没有开启、没到阈值、或者压缩失败，都返回已有摘要（可能为 null），请求照常发出。
     */
    private Compactor.Summary compactIfNeeded(String sessionId, List<Msg> history, String origin,
                                              String apiKey, String model, Api.Call call) {
        Compactor.Summary summary = Compactor.load(context, sessionId);
        if (!Compactor.stillValid(summary, history)) {
            summary = null;
        }
        if (!settings.autoCompact()) {
            return summary;
        }
        long used = usedTokens(history);
        long limit = settings.contextLimit();
        if (used > 0 && used < limit * 3 / 4) {
            return summary;
        }
        try {
            Compactor.Summary fresh = compact(sessionId, history, summary, origin, apiKey, model,
                call);
            if (fresh != null) {
                Compactor.save(context, sessionId, fresh);
                Diag.log("COMPACT", "压缩 " + fresh.rounds + " 轮 · 摘要 "
                    + fresh.text.length() + " 字 · 风格 " + fresh.style);
                return fresh;
            }
        } catch (Api.ApiError error) {
            Diag.log("COMPACT", "压缩失败 · " + error.kind + " " + error.status
                + " · " + error.getMessage());
        }
        return summary;
    }

    private Compactor.Summary compact(String sessionId, List<Msg> history,
                                      Compactor.Summary previous, String origin, String apiKey,
                                      String model, Api.Call call) throws Api.ApiError {
        List<Msg[]> rounds = Prompt.getContextRounds(history);
        if (rounds.size() <= KEEP_ROUNDS + 2) {
            return previous;
        }
        int from = 0;
        if (previous != null) {
            for (int index = 0; index < rounds.size(); index++) {
                if (previous.upto.equals(rounds.get(index)[0].id)) {
                    from = index + 1;
                    break;
                }
            }
        }
        int to = rounds.size() - KEEP_ROUNDS;
        if (to <= from) {
            return previous;
        }
        List<Msg[]> folded = new ArrayList<>(rounds.subList(from, to));
        // 已有摘要并进新的，避免多份摘要互相矛盾
        if (previous != null && previous.text != null && !previous.text.isEmpty()) {
            Msg note = new Msg();
            note.role = Msg.USER;
            note.content = "【之前的摘要】" + previous.text;
            Msg ack = new Msg();
            ack.role = Msg.ASSISTANT;
            ack.blocks.add(Msg.textBlock("（已并入）"));
            folded.add(0, new Msg[]{note, ack});
        }
        Compactor.Summary draft = Compactor.summarize(context, sessionId, folded, origin, apiKey,
            model, resolveStyle(folded, previous, origin, apiKey, model, call), call);
        return verify(draft, folded, origin, apiKey, model, call);
    }

    /** 摘要生成后再跑一次「新摘要 ↔ 旧上下文」的互相校验，可选，费 token。 */
    private Compactor.Summary verify(Compactor.Summary draft, List<Msg[]> folded, String origin,
                                     String apiKey, String model, Api.Call call) {
        if (draft == null || !settings.compactVerify()) {
            return draft;
        }
        long since = Diag.now();
        Compactor.Summary refined = Compactor.refine(draft, folded, origin, apiKey, model, call);
        Diag.log("COMPACT", "校验耗时 " + Diag.millis(since) + "ms");
        return refined;
    }

    /** 自动模式下先让模型判断对话类型；已有摘要就沿用上次的判断，省一次请求。 */
    private int resolveStyle(List<Msg[]> folded, Compactor.Summary previous, String origin,
                             String apiKey, String model, Api.Call call) {
        int style = settings.compactStyle();
        if (style != Compactor.STYLE_AUTO) {
            return style;
        }
        if (previous != null && previous.style >= 0) {
            return previous.style;
        }
        int detected = Compactor.detectStyle(folded, origin, apiKey, model, call);
        Diag.log("COMPACT", "自动判断对话类型：" + detected);
        return detected;
    }

    /** 最近一次请求实际占用的 token；没有用量记录时按字符估算。 */
    private static long usedTokens(List<Msg> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            Msg message = history.get(index);
            if (message.isUser() || message.usage == null) {
                continue;
            }
            long tokens = Prompt.contextTokens(message.usage);
            if (tokens > 0) {
                return tokens;
            }
        }
        long estimate = 0;
        for (Msg[] round : Prompt.getContextRounds(history)) {
            estimate += Prompt.estimateTokens(round[0]) + Prompt.estimateTokens(round[1]);
        }
        return estimate;
    }

    private Msg refresh(Msg message) {
        Msg latest = db.message(message.id);
        if (latest == null) {
            return message;
        }
        latest.sessionId = message.sessionId;
        return latest;
    }

    private List<Msg> refreshAll(List<Msg> messages) {
        List<Msg> list = new ArrayList<>(messages.size());
        for (Msg message : messages) {
            // 附件上传只会改变带附件的用户消息；其他历史无需反复读取并解析。
            list.add(message.isUser() && !message.files.isEmpty() ? refresh(message) : message);
        }
        return list;
    }

    /**
     * 附件的服务端 id：已有直接用；本地有原件就上传并写回会话里所有引用同一附件的消息；
     * 本地没有原件（导入的非图片附件只有元数据）返回 null。
     */
    private String ensureServerId(String sessionId, Msg.FileAtt file, String origin,
                                  String apiKey, Api.Call call) throws Api.ApiError {
        if (file.serverId != null && !file.serverId.isEmpty()) {
            return file.serverId;
        }
        Msg.FileAtt stored = db.file(file.id);
        if (stored == null) {
            stored = file;
            db.saveFile(stored);
        }
        if (stored.serverId != null && !stored.serverId.isEmpty()) {
            file.serverId = stored.serverId;
            return stored.serverId;
        }
        String serverId = attachments.upload(origin, apiKey, stored, call);
        if (serverId == null) {
            return null;
        }
        file.serverId = serverId;
        saveServerId(sessionId, file.id, serverId);
        notifyListener(Listener::onFilesChanged);
        return serverId;
    }

    /**
     * 把补传得到的 serverId 落库。
     * 只写 files 表：消息 JSON 里的副本由 ensureServerId 通过 db.file() 兜底读取，
     * 不再为此把整棵会话（上千条）重读重写一遍。
     */
    private void saveServerId(String sessionId, String fileId, String serverId) {
        db.setFileServerId(fileId, serverId);
    }

    /** 本次请求实际会用到的服务端 file_id。 */
    private static void addServerIds(Set<String> target, List<Msg.FileAtt> files) {
        for (Msg.FileAtt file : files) {
            if (file.serverId != null && !file.serverId.isEmpty()) {
                target.add(file.serverId);
            }
        }
    }

    /**
     * 服务端判定 file_id 失效时，清掉对应附件的 serverId，下一次补齐会重新读取本地原件上传。
     * 报错里点名了 file_id 就只重置这些，没点名则重置本次请求用到的全部附件。
     */
    private boolean reuploadInvalidated(String sessionId, JSONArray prepared, Api.ApiError error) {
        if (!Api.isFileInvalidated(error)) {
            return false;
        }
        List<Msg> all = db.messages(sessionId);
        Set<String> targets = new HashSet<>(Api.extractFileIds(error.getMessage()));
        if (targets.isEmpty()) {
            for (Msg message : all) {
                if (message.isUser()) {
                    addServerIds(targets, message.files);
                }
            }
        }
        if (targets.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Msg message : all) {
            if (!message.isUser()) {
                continue;
            }
            for (Msg.FileAtt file : message.files) {
                if (file.serverId != null && targets.contains(file.serverId)) {
                    file.serverId = null;
                    db.setFileServerId(file.id, null);
                    changed = true;
                }
            }
        }
        if (changed) {
            db.saveMessages(all);
        }
        return changed;
    }

    /** 重传前的退避等待：第 n 次等待 2^n 秒，停止生成时立即返回。 */
    private void waitForRetry(int attempt, Api.Call call) {
        long delay = Prompt.FILE_RETRY_BASE_DELAY * (1L << Math.min(attempt, 8));
        long deadline = System.currentTimeMillis() + delay;
        while (System.currentTimeMillis() < deadline) {
            if (call.isCancelled()) {
                return;
            }
            try {
                Thread.sleep(Math.min(100, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    // ================================================================ 小工具

    private Msg newReply(String sessionId, String parentId, long createdAt, boolean thinking,
                         boolean search) {
        Msg reply = new Msg();
        reply.id = Util.newId();
        reply.sessionId = sessionId;
        reply.parentId = parentId;
        reply.role = Msg.ASSISTANT;
        reply.createdAt = createdAt;
        reply.status = Msg.PENDING;
        reply.model = settings.model();
        reply.thinking = thinking;
        reply.search = search;
        reply.prefix = true;
        return reply;
    }

    private boolean thinkingOfSibling(String sessionId, String userId) {
        for (Msg reply : db.repliesTo(sessionId, userId)) {
            return reply.thinking;
        }
        return settings.thinking();
    }

    /** 新消息挂在指定父节点下并选中新分支；原节点与其后续对话保持不变。 */
    private void appendBranch(Db.Session session, String parentId, List<Msg> messages) {
        db.appendBranch(session.id, parentId, messages);
    }

    private static int indexOf(List<Msg> messages, String id) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).id.equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private static JSONObject uploadError(Api.ApiError error) {
        JSONObject json = error.toJson();
        try {
            json.put("kind", Api.UPLOAD);
            json.put("retryable", Api.isRetryable(error));
        } catch (Exception ignored) {
        }
        return json;
    }

    private static JSONObject protocol(Throwable error) {
        JSONObject json = new JSONObject();
        try {
            json.put("kind", Api.PROTOCOL);
            String message = error.getMessage();
            json.put("message", message == null ? error.getClass().getSimpleName() : message);
        } catch (Exception ignored) {
        }
        return json;
    }
}



