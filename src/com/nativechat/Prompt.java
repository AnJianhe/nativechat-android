package com.nativechat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义核心：流式事件合并 + 上下文构建。逐条对应网页版：
 * - `api/parseEvent.ts` 的事件校验
 * - `chat/reply.ts` 的 applyEvent / settleReply / toRequestMessages / canContinueReply
 * - `chat/latestReminder.ts` 的本轮信息快照
 *
 * 这里没有 UI，也没有 IO，只做纯数据变换，方便和原实现逐行对照。
 */
public final class Prompt {
    /** 所有生成请求统一携带的助手身份说明 */
    public static final String SYSTEM_PROMPT = "该助手为DeepSeek，由深度求索公司创造。";
    /** deepseek-flash 同时支持深度思考与图片输入 */
    public static final String DEFAULT_MODEL = "deepseek-flash";
    /** 快照里的平台标识；网页版写死 Web，原生版沿用同一取值 */
    public static final String PLATFORM = "Web";
    public static final String DEFAULT_LANGUAGE = "中文";
    /** 安全审核固定开启，所有请求都走扩展模式 */
    public static final String SAFETY_LEVEL = "high";
    /** 服务端判定 file_id 失效后重新补传并重发的最多次数（不含首次请求） */
    public static final int FILE_RETRY_LIMIT = 2;
    /** 退避基数：第 n 次重试等待 2^n 秒 */
    public static final long FILE_RETRY_BASE_DELAY = 1000L;

    private Prompt() {
    }

    // ================================================================ 请求体

    /** 阅读链接始终开启；智能搜索开关控制是否另行声明搜索工具（含边想边搜）。 */
    public static JSONArray serverTools(boolean search) {
        JSONArray tools = new JSONArray();
        tools.put(tool("deepseek_read_url"));
        if (search) {
            tools.put(tool("deepseek_web_search"));
        }
        return tools;
    }

    private static JSONObject tool(String name) {
        JSONObject tool = new JSONObject();
        try {
            tool.put("type", name);
            tool.put("name", name);
        } catch (Exception ignored) {
        }
        return tool;
    }

    /** 请求体里是否引用了 file_id（决定要不要带 anthropic-beta）。 */
    public static boolean hasFileBlocks(JSONArray messages) {
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null) {
                continue;
            }
            JSONArray content = message.optJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int at = 0; at < content.length(); at++) {
                JSONObject block = content.optJSONObject(at);
                if (block == null) {
                    continue;
                }
                String type = block.optString("type");
                if ("image".equals(type) || "document".equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static JSONObject buildRequest(String model, JSONArray messages, boolean thinking,
                                          boolean search, boolean withTitle) {
        return buildRequest(model, messages, thinking, search, withTitle,
            Settings.DEFAULT_TEMPERATURE);
    }

    /** temperature 传负数表示不带这个字段（沿用服务端默认）。 */
    public static JSONObject buildRequest(String model, JSONArray messages, boolean thinking,
                                          boolean search, boolean withTitle, float temperature) {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            // 保持官方那 16 字身份声明不变；时间放在本轮提问末尾。
            body.put("system", SYSTEM_PROMPT);
            body.put("messages", messages);
            JSONObject thinkingField = new JSONObject();
            thinkingField.put("type", thinking ? "enabled" : "disabled");
            body.put("thinking", thinkingField);
            JSONObject safety = new JSONObject();
            safety.put("level", SAFETY_LEVEL);
            body.put("safety_check", safety);
            body.put("tools", serverTools(search));
            if (temperature >= 0f) {
                // 保留两位小数，避免 0.1f 之类的浮点误差进入请求体
                body.put("temperature", Math.round(temperature * 100f) / 100.0);
            }
            if (withTitle) {
                JSONObject title = new JSONObject();
                title.put("enabled", true);
                body.put("title", title);
            }
            body.put("stream", true);
        } catch (Exception ignored) {
        }
        return body;
    }

    // ================================================================ 事件校验

    /**
     * 未识别或无法安全消费的事件返回 null（静默丢弃），与 parseEvent.ts 的行为一致：
     * 丢弃不能影响后续有效内容与正常结束。
     */
    public static JSONObject readEvent(JSONObject value) {
        if (value == null) {
            return null;
        }
        String type = value.optString("type", "");
        if ("message_start".equals(type)) {
            JSONObject message = value.optJSONObject("message");
            if (message == null) {
                return null;
            }
            return value;
        }
        if ("content_block_start".equals(type)) {
            if (!isIndex(value.opt("index"))) {
                return null;
            }
            JSONObject block = readBlock(value.optJSONObject("content_block"));
            if (block == null) {
                return null;
            }
            return putQuietly(value, "content_block", block);
        }
        if ("content_block_delta".equals(type)) {
            if (!isIndex(value.opt("index"))) {
                return null;
            }
            JSONObject delta = readDelta(value.optJSONObject("delta"));
            if (delta == null) {
                return null;
            }
            return putQuietly(value, "delta", delta);
        }
        if ("content_block_stop".equals(type)) {
            return isIndex(value.opt("index")) ? value : null;
        }
        if ("message_delta".equals(type)) {
            JSONObject delta = value.optJSONObject("delta");
            // Some providers send usage without a delta or without stop_reason.
            // Preserve usage even when an unrelated delta is missing or malformed.
            if (delta != null) {
                String stop = stringOrNull(delta, "stop_reason");
                if (stop == null || "end_turn".equals(stop) || "max_tokens".equals(stop)
                    || "tool_use".equals(stop) || "refusal".equals(stop)) return value;
            }
            return hasValidUsage(value.optJSONObject("usage")) ? value : null;
        }
        if ("message_stop".equals(type)) {
            return value;
        }
        // ping 以及任何未知类型
        return null;
    }

    private static JSONObject putQuietly(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception ignored) {
        }
        return target;
    }

    private static boolean isIndex(Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        double number = ((Number) value).doubleValue();
        return number >= 0 && number == Math.floor(number) && number <= Integer.MAX_VALUE;
    }

    private static String stringOrNull(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static JSONObject readBlock(JSONObject block) {
        if (block == null) {
            return null;
        }
        String type = block.optString("type", "");
        if ("text".equals(type)) {
            String text = stringOrNull(block, "text");
            return text == null ? null : block;
        }
        if ("thinking".equals(type)) {
            String thinking = stringOrNull(block, "thinking");
            return thinking == null ? null : block;
        }
        if ("server_tool_use".equals(type)) {
            String id = stringOrNull(block, "id");
            String kind = block.optString("kind", "");
            String name = block.optString("name", "");
            JSONObject input = block.optJSONObject("input");
            if (id == null || input == null || !isToolKind(kind)
                || !("deepseek_web_search".equals(name) || "deepseek_read_url".equals(name))) {
                return null;
            }
            String action = input.optString("action", "");
            if ("search".equals(action)) {
                JSONArray queries = input.optJSONArray("queries");
                if (queries == null) {
                    return null;
                }
            } else if ("open_page".equals(action) || "read_link".equals(action)) {
                if (stringOrNull(input, "url") == null) {
                    return null;
                }
            } else if ("find_in_page".equals(action)) {
                if (stringOrNull(input, "url") == null || stringOrNull(input, "pattern") == null) {
                    return null;
                }
            } else {
                return null;
            }
            return block;
        }
        if ("web_search_tool_result".equals(type)) {
            if (stringOrNull(block, "tool_use_id") == null || !isSearchKind(block.optString("kind", ""))) {
                return null;
            }
            JSONArray content = block.optJSONArray("content");
            if (content == null) {
                return null;
            }
            JSONArray kept = new JSONArray();
            for (int index = 0; index < content.length(); index++) {
                JSONObject item = content.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String itemType = item.optString("type", "");
                if ("web_search_tool_result_error".equals(itemType)) {
                    if (stringOrNull(item, "error_code") != null) {
                        kept.put(item);
                    }
                } else if ("web_search_result".equals(itemType)
                    && stringOrNull(item, "url") != null && stringOrNull(item, "title") != null) {
                    kept.put(item);
                }
            }
            return putQuietly(block, "content", kept);
        }
        if ("web_fetch_tool_result".equals(type)) {
            String kind = block.optString("kind", "");
            if (stringOrNull(block, "tool_use_id") == null
                || !("read_link".equals(kind) || "deep_search".equals(kind))) {
                return null;
            }
            JSONObject content = block.optJSONObject("content");
            if (content == null) {
                return null;
            }
            String contentType = content.optString("type", "");
            if ("web_fetch_tool_result_error".equals(contentType)) {
                if (stringOrNull(content, "error_code") == null) {
                    return null;
                }
            } else if (!"web_fetch_result".equals(contentType)
                || stringOrNull(content, "url") == null
                || content.optJSONObject("content") == null) {
                return null;
            }
            return block;
        }
        if ("tool_use".equals(type)) {
            // 客户端工具内容不展示；保留下来让合并层关掉续写能力
            return block;
        }
        return null;
    }

    private static boolean isToolKind(String kind) {
        return "search".equals(kind) || "deep_search".equals(kind) || "read_link".equals(kind)
            || "image_search".equals(kind) || "deep_image_search".equals(kind);
    }

    private static boolean isSearchKind(String kind) {
        return "search".equals(kind) || "deep_search".equals(kind) || "image_search".equals(kind)
            || "deep_image_search".equals(kind);
    }

    private static JSONObject readDelta(JSONObject delta) {
        if (delta == null) {
            return null;
        }
        String type = delta.optString("type", "");
        if ("text_delta".equals(type)) {
            return stringOrNull(delta, "text") == null ? null : delta;
        }
        if ("thinking_delta".equals(type)) {
            return stringOrNull(delta, "thinking") == null ? null : delta;
        }
        if ("signature_delta".equals(type)) {
            return stringOrNull(delta, "signature") == null ? null : delta;
        }
        if ("citations_delta".equals(type)) {
            JSONObject citation = delta.optJSONObject("citation");
            if (citation == null || !"web_search_result_location".equals(citation.optString("type"))
                || stringOrNull(citation, "url") == null) {
                return null;
            }
            return delta;
        }
        return null;
    }

    private static final String[] USAGE_KEYS = {"input_tokens", "cache_creation_input_tokens",
        "cache_read_input_tokens", "output_tokens"};

    /** Accept only integral non-negative token counts representable as long. */
    private static Long usageCount(JSONObject usage, String key) {
        if (usage == null || !(usage.opt(key) instanceof Number)) return null;
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(usage.opt(key).toString());
            long count = number.longValueExact();
            return count < 0 ? null : count;
        } catch (RuntimeException error) { return null; }
    }

    private static boolean hasValidUsage(JSONObject usage) {
        for (String key : USAGE_KEYS) if (usageCount(usage, key) != null) return true;
        return false;
    }

    /** Merge only reported valid counters; initial output is a placeholder, not final usage. */
    private static JSONObject mergeUsage(JSONObject current, JSONObject incoming, boolean initial) {
        if (incoming == null) return current;
        JSONObject result = current;
        for (String key : USAGE_KEYS) {
            if (initial && "output_tokens".equals(key)) continue;
            Long count = usageCount(incoming, key);
            if (count == null) continue;
            if (result == null) result = new JSONObject();
            try { result.put(key, count); } catch (Exception ignored) { }
        }
        return result;
    }

    // ================================================================ 事件合并

    /**
     * 把一个流式事件合并进回复：只追加可识别的内容块，用本次请求的临时索引映射处理增量。
     * 跳过的块不占位；续写直接追加到已有内容之后。
     */
    public static void applyEvent(Msg reply, JSONObject event, long now,
                                  Map<Integer, Integer> blockIndexes) {
        if (!Msg.STREAMING.equals(reply.status)) {
            reply.status = Msg.STREAMING;
        }
        String type = event.optString("type", "");
        if ("message_start".equals(type)) {
            JSONObject message = event.optJSONObject("message");
            JSONObject usage = message == null ? null : message.optJSONObject("usage");
            // A continuation is another API request: never mix its counters with prior usage.
            reply.usage = mergeUsage(null, usage, true);
            return;
        }
        if ("content_block_start".equals(type)) {
            int index = event.optInt("index");
            blockIndexes.remove(index);
            JSONObject block = toReplyBlock(event.optJSONObject("content_block"), now);
            if (block == null) {
                // 客户端工具调用不能作为续写前缀
                if ("tool_use".equals(event.optJSONObject("content_block") == null ? ""
                    : event.optJSONObject("content_block").optString("type", ""))) {
                    reply.prefix = false;
                }
                return;
            }
            blockIndexes.put(index, reply.blocks.size());
            reply.blocks.add(block);
            return;
        }
        if ("content_block_delta".equals(type)) {
            Integer at = blockIndexes.get(event.optInt("index"));
            if (at == null || at >= reply.blocks.size()) {
                return;
            }
            JSONObject block = reply.blocks.get(at);
            JSONObject delta = event.optJSONObject("delta");
            if (delta == null) {
                return;
            }
            String deltaType = delta.optString("type", "");
            String blockType = block.optString("type", "");
            try {
                if ("text".equals(blockType) && "text_delta".equals(deltaType)) {
                    block.put("text", block.optString("text", "") + delta.optString("text", ""));
                } else if ("thinking".equals(blockType) && "thinking_delta".equals(deltaType)) {
                    block.put("thinking",
                        block.optString("thinking", "") + delta.optString("thinking", ""));
                } else if ("thinking".equals(blockType) && "signature_delta".equals(deltaType)) {
                    block.put("signature",
                        block.optString("signature", "") + delta.optString("signature", ""));
                } else if ("text".equals(blockType) && "citations_delta".equals(deltaType)) {
                    JSONArray citations = block.optJSONArray("citations");
                    if (citations == null) {
                        citations = new JSONArray();
                        block.put("citations", citations);
                    }
                    citations.put(delta.optJSONObject("citation"));
                }
            } catch (Exception ignored) {
            }
            return;
        }
        if ("content_block_stop".equals(type)) {
            Integer at = blockIndexes.remove(event.optInt("index"));
            if (at == null || at >= reply.blocks.size()) {
                return;
            }
            JSONObject block = reply.blocks.get(at);
            if ("thinking".equals(block.optString("type")) && block.has("startedAt")
                && !block.has("endedAt")) {
                putQuietly(block, "endedAt", now);
            }
            return;
        }
        if ("message_delta".equals(type)) {
            JSONObject delta = event.optJSONObject("delta");
            if (delta != null) {
                String stop = stringOrNull(delta, "stop_reason");
                if ("end_turn".equals(stop) || "max_tokens".equals(stop)
                    || "tool_use".equals(stop) || "refusal".equals(stop)) reply.stopReason = stop;
                JSONObject details = delta.optJSONObject("stop_details");
                if (details != null) {
                    reply.stopDetails = details;
                }
            }
            JSONObject usage = event.optJSONObject("usage");
            reply.usage = mergeUsage(reply.usage, usage, false);
        }
    }

    private static JSONObject toReplyBlock(JSONObject block, long now) {
        if (block == null) {
            return null;
        }
        String type = block.optString("type", "");
        if ("text".equals(type)) {
            JSONObject copy = new JSONObject();
            try {
                copy.put("type", "text");
                copy.put("text", block.optString("text", ""));
                JSONArray citations = block.optJSONArray("citations");
                if (citations != null && citations.length() > 0) {
                    copy.put("citations", citations);
                }
            } catch (Exception ignored) {
            }
            return copy;
        }
        if ("thinking".equals(type)) {
            JSONObject copy = new JSONObject();
            try {
                copy.put("type", "thinking");
                copy.put("thinking", block.optString("thinking", ""));
                String signature = stringOrNull(block, "signature");
                if (signature != null) {
                    copy.put("signature", signature);
                }
                copy.put("startedAt", now);
            } catch (Exception ignored) {
            }
            return copy;
        }
        if ("server_tool_use".equals(type) || "web_search_tool_result".equals(type)
            || "web_fetch_tool_result".equals(type)) {
            return block;
        }
        return null;
    }

    /**
     * 回复结束：未结束的思考块以当前时间收尾。
     * 安全拒绝（stop_reason: refusal）时已下发的思考与正文按协议要求由客户端丢弃，只保留工具块。
     */
    public static void settleReply(Msg reply, String status, long now, JSONObject error) {
        reply.status = status;
        reply.error = error;
        List<JSONObject> kept = new ArrayList<>();
        boolean refused = "refusal".equals(reply.stopReason);
        for (JSONObject block : reply.blocks) {
            String type = block.optString("type", "");
            if (refused && ("thinking".equals(type) || "text".equals(type))) {
                continue;
            }
            if ("thinking".equals(type) && block.has("startedAt") && !block.has("endedAt")) {
                putQuietly(block, "endedAt", now);
            }
            kept.add(block);
        }
        reply.blocks.clear();
        reply.blocks.addAll(kept);
    }

    /** 思考用时（秒），至少 1 秒。 */
    public static int thinkingSeconds(JSONObject block) {
        if (block == null) {
            return 1;
        }
        long started = block.optLong("startedAt", 0L);
        long ended = block.optLong("endedAt", 0L);
        if (started <= 0 || ended <= started) {
            return 1;
        }
        return (int) Math.max(1, Math.round((ended - started) / 1000.0));
    }

    // ================================================================ 上下文

    /** 只有正常达到输出上限或被停止的回复可续写；报错回复不能续写。 */
    public static boolean canContinueReply(Msg reply) {
        if (reply == null || reply.isUser() || !reply.prefix
            || "refusal".equals(reply.stopReason) || "tool_use".equals(reply.stopReason)) {
            return false;
        }
        for (JSONObject block : reply.blocks) {
            String type = block.optString("type", "");
            if ("server_tool_use".equals(type) || "web_search_tool_result".equals(type)
                || "web_fetch_tool_result".equals(type)
                || ("text".equals(type) && block.optBoolean("isTemplate", false))) {
                return false;
            }
        }
        return Msg.STOPPED.equals(reply.status)
            || (Msg.DONE.equals(reply.status) && "max_tokens".equals(reply.stopReason));
    }

    /** 已结束或停止、有正文、非拒绝、非服务端模板的回复才进入上下文。 */
    public static boolean isUsableReply(Msg message) {
        if (message == null || message.isUser()) {
            return false;
        }
        if (!Msg.DONE.equals(message.status) && !Msg.STOPPED.equals(message.status)) {
            return false;
        }
        if ("refusal".equals(message.stopReason) || message.hasTemplateBlock()) {
            return false;
        }
        return !message.replyText().isEmpty();
    }

    /** 历史中会进入请求上下文的轮次：提问后紧跟可用回答的才保留。 */
    public static List<Msg[]> getContextRounds(List<Msg> history) {
        List<Msg[]> rounds = new ArrayList<>();
        for (int index = 0; index + 1 < history.size(); index++) {
            Msg user = history.get(index);
            Msg reply = history.get(index + 1);
            if (user.isUser() && isUsableReply(reply)) {
                rounds.add(new Msg[]{user, reply});
                index++;
            }
        }
        return rounds;
    }

    /** 有 serverId 的附件才进入请求：图片用 image 块，其余用 document 块。 */
    private static List<JSONObject> toFileBlocks(List<Msg.FileAtt> files) {
        List<JSONObject> blocks = new ArrayList<>();
        for (Msg.FileAtt file : files) {
            if (file.serverId != null && !file.serverId.isEmpty()) {
                blocks.add(file.isImage ? Msg.imageBlock(file.serverId)
                    : Msg.documentBlock(file.serverId));
            }
        }
        return blocks;
    }

    private static JSONObject toUserParam(Msg message) {
        return toUserParam(message, true);
    }

    /** withFiles=false 用于历史轮次：只发正文，附件不上传也不引用。 */
    private static JSONObject toUserParam(Msg message, boolean withFiles) {
        List<JSONObject> files = withFiles ? toFileBlocks(message.files)
            : new ArrayList<JSONObject>();
        JSONObject param = new JSONObject();
        try {
            param.put("role", "user");
            if (files.isEmpty()) {
                param.put("content", message.content);
            } else {
                JSONArray content = new JSONArray();
                for (JSONObject block : files) {
                    content.put(block);
                }
                if (message.content != null && !message.content.isEmpty()) {
                    content.put(Msg.textBlock(message.content));
                }
                param.put("content", content);
            }
        } catch (Exception ignored) {
        }
        return param;
    }

    /**
     * assistant 的内容块按历史与续写场景转换：
     * - 历史思考及其签名不回传；开启思考时续写只保留本轮非空白思考及其签名；
     * - 文本块去掉 citations；相邻文本直接拼接，被思考或工具隔开的文本用空行连接；
     * - 工具调用和结果只保留 kind 为 read_link 的块；
     * - 阅读链接结果原样回传，保留 signature：服务端解密后将页面拼回该轮提问末尾。
     */
    public static JSONArray toAssistantContent(Msg message, boolean prefix, boolean thinking) {
        List<JSONObject> content = new ArrayList<>();
        java.util.Set<String> unsignedReadLinks = new java.util.HashSet<>();
        for (JSONObject block : message.blocks) {
            if ("web_fetch_tool_result".equals(block.optString("type"))
                && "read_link".equals(block.optString("kind"))) {
                JSONObject inner = block.optJSONObject("content");
                boolean success = inner != null
                    && "web_fetch_result".equals(inner.optString("type"));
                String signature = block.optString("signature", "");
                if (success && signature.trim().isEmpty()) {
                    unsignedReadLinks.add(block.optString("tool_use_id", ""));
                }
            }
        }

        JSONObject text = null;
        for (JSONObject block : message.blocks) {
            String type = block.optString("type", "");
            // 空思考不改变续写位置，也不应把两段正文隔开
            if ("thinking".equals(type)
                && block.optString("thinking", "").trim().isEmpty()) {
                continue;
            }
            if ("text".equals(type)) {
                if (text != null) {
                    try {
                        text.put("text", text.optString("text", "") + block.optString("text", ""));
                    } catch (Exception ignored) {
                    }
                } else {
                    String lead = "";
                    if (!content.isEmpty()
                        && "text".equals(content.get(content.size() - 1).optString("type"))) {
                        lead = "\n\n";
                    }
                    text = Msg.textBlock(lead + block.optString("text", ""));
                    content.add(text);
                }
                continue;
            }
            text = null;
            // 思考块只有带签名才能回传（服务端会校验），所以缺签名的历史思考直接丢弃。
            if (thinking && "thinking".equals(type)
                && !block.optString("signature", "").trim().isEmpty()) {
                JSONObject copy = new JSONObject();
                try {
                    copy.put("type", "thinking");
                    copy.put("thinking", block.optString("thinking", ""));
                    copy.put("signature", block.optString("signature", ""));
                } catch (Exception ignored) {
                }
                content.add(copy);
            } else if ("server_tool_use".equals(type) && "read_link".equals(block.optString("kind"))
                && !unsignedReadLinks.contains(block.optString("id", ""))) {
                JSONObject copy = new JSONObject();
                try {
                    copy.put("type", "server_tool_use");
                    copy.put("id", block.optString("id", ""));
                    copy.put("name", block.optString("name", ""));
                    copy.put("input", block.optJSONObject("input"));
                    copy.put("kind", block.optString("kind", ""));
                } catch (Exception ignored) {
                }
                content.add(copy);
            } else if ("web_fetch_tool_result".equals(type)
                && "read_link".equals(block.optString("kind"))
                && !unsignedReadLinks.contains(block.optString("tool_use_id", ""))) {
                content.add(block);
            }
        }

        JSONArray result = new JSONArray();
        for (JSONObject block : content) {
            if ("text".equals(block.optString("type")) && block.optString("text", "").isEmpty()) {
                continue;
            }
            result.put(block);
        }
        return result;
    }

    /**
     * 从最新一轮往前取，直到预算用完；返回的都是最近的轮次，顺序不变。
     * token 用量按字符数估算（中文约 1 字 0.6~0.7 token，这里取 0.7 偏保守）。
     */
    private static List<Msg[]> trimRounds(List<Msg[]> rounds, int budgetTokens) {
        long used = 0;
        int keepFrom = rounds.size();
        for (int index = rounds.size() - 1; index >= 0; index--) {
            Msg[] round = rounds.get(index);
            long cost = estimateTokens(round[0]) + estimateTokens(round[1]);
            if (used + cost > budgetTokens && keepFrom < rounds.size()) {
                break;
            }
            used += cost;
            keepFrom = index;
            if (used > budgetTokens) {
                break;
            }
        }
        return keepFrom == 0 ? rounds : rounds.subList(keepFrom, rounds.size());
    }

    public static long estimateTokens(Msg message) {
        if (message == null) {
            return 0;
        }
        String text = message.isUser() ? (message.content == null ? "" : message.content)
            : message.replyText();
        return (long) (text.length() * 0.7) + 8;
    }

    /** 粗估一份请求的 token 数，用于日志与预警。 */
    public static long estimateTokens(JSONArray messages) {
        if (messages == null) {
            return 0;
        }
        return (long) (messages.toString().length() * 0.7);
    }

    /**
     * 把会话转成请求的 messages：只保留「提问 + 有回答的回复」成对的轮次，最后接上本轮提问。
     * 失败或没有回答的轮次不进上下文。续写时末尾再带上本轮 assistant。
     */
    public static JSONArray toRequestMessages(List<Msg> history, Msg question, Msg prefix,
                                              boolean thinking, String latestReminder) {
        return toRequestMessages(history, question, prefix, thinking, latestReminder, false);
    }

    /**
     * @param historyThinking 是否把历史轮次的思考块（含签名）一起发回模型；
     *                        关闭时只发正文，长会话能省下大量 token。
     */
    public static JSONArray toRequestMessages(List<Msg> history, Msg question, Msg prefix,
                                              boolean thinking, String latestReminder,
                                              boolean historyThinking) {
        return toRequestMessages(history, question, prefix, thinking, latestReminder,
            historyThinking, null, 0);
    }

    public static JSONArray toRequestMessages(List<Msg> history, Msg question, Msg prefix,
                                              boolean thinking, String latestReminder,
                                              boolean historyThinking,
                                              Compactor.Summary summary) {
        return toRequestMessages(history, question, prefix, thinking, latestReminder,
            historyThinking, summary, 0);
    }

    /**
     * @param summary 已压缩的历史摘要；只保留它之后的消息，开头补一对「摘要 + 收到」。
     * @param budgetTokens 本请求可用的上下文 token 预算；超过就从最旧的轮次开始丢，
     *                     保证不会在服务端被「user input too long」整条拒绝。
     */
    public static JSONArray toRequestMessages(List<Msg> history, Msg question, Msg prefix,
                                              boolean thinking, String latestReminder,
                                              boolean historyThinking,
                                              Compactor.Summary summary, int budgetTokens) {
        JSONArray messages = new JSONArray();
        String[] previousReminder = {null};

        int start = 0;
        if (summary != null && summary.covers()) {
            for (int index = 0; index < history.size(); index++) {
                if (summary.upto.equals(history.get(index).id)) {
                    start = index + 1;
                    break;
                }
            }
            if (start > 0) {
                try {
                    JSONObject note = new JSONObject();
                    note.put("role", "user");
                    note.put("content", "【此前对话的摘要，供你继续时参考】\n" + summary.text);
                    messages.put(note);
                    JSONObject ack = new JSONObject();
                    ack.put("role", "assistant");
                    JSONArray content = new JSONArray();
                    content.put(Msg.textBlock("收到，我按这份背景继续。"));
                    ack.put("content", content);
                    messages.put(ack);
                } catch (Exception ignored) {
                }
            } else {
                start = 0;
            }
        }
        List<Msg> tail = history.subList(Math.min(start, history.size()), history.size());

        List<Msg[]> rounds = getContextRounds(tail);
        if (budgetTokens > 0) {
            rounds = trimRounds(rounds, budgetTokens);
        }
        for (Msg[] round : rounds) {
            Msg user = round[0];
            Msg reply = round[1];
            // 历史轮次只带正文：图片/文件一律不发，避免上千张图把请求撑爆或撞上文件数上限。
            // 纯附件的历史提问（没写字）也就没有可发的内容，连同回答一起省略。
            if (user.content == null || user.content.isEmpty()) {
                continue;
            }
            appendReminder(messages, previousReminder, reply.latestReminder);
            messages.put(toUserParam(user, false));
            // 带附件的历史轮次默认把思考发回去：模型对图片的理解就写在思考里，
            // 既然原图不再上传，这段思考是唯一还能说明「图里是什么」的线索。
            boolean roundThinking = historyThinking || !user.files.isEmpty();
            messages.put(assistantParam(reply, false, roundThinking));
        }
        appendReminder(messages, previousReminder, latestReminder);
        messages.put(toUserParam(question));

        if (prefix != null && prefix.prefix) {
            JSONArray assistant = toAssistantContent(prefix, true, thinking);
            // 没有非空白正文或有效思考时直接重发原问题，避免服务端拒绝空 Prefill
            boolean usable = false;
            for (int index = 0; index < assistant.length(); index++) {
                JSONObject block = assistant.optJSONObject(index);
                if (block == null) {
                    continue;
                }
                String type = block.optString("type");
                if (("text".equals(type) && !block.optString("text", "").trim().isEmpty())
                    || ("thinking".equals(type)
                    && !block.optString("thinking", "").trim().isEmpty())) {
                    usable = true;
                    break;
                }
            }
            if (usable) {
                messages.put(assistantParam(prefix, true, thinking));
            }
        }
        return messages;
    }

    private static void appendReminder(JSONArray messages, String[] previous, String reminder) {
        // 仅在当前分支首次出现或内容变化时插入快照；缺少快照的消息保持原有内容
        if (reminder != null && !reminder.isEmpty() && !reminder.equals(previous[0])) {
            JSONObject param = new JSONObject();
            try {
                param.put("role", "latest_reminder");
                param.put("content", reminder);
            } catch (Exception ignored) {
            }
            messages.put(param);
            previous[0] = reminder;
        }
    }

    private static JSONObject assistantParam(Msg message, boolean prefix, boolean thinking) {
        JSONObject param = new JSONObject();
        try {
            param.put("role", "assistant");
            param.put("content", toAssistantContent(message, prefix, thinking));
        } catch (Exception ignored) {
        }
        return param;
    }

    // ================================================================ 快照

    /** 形如「2026-11-19 23:20（UTC+08:00，Asia/Shanghai）」。 */
    public static String timeText(long now) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(now);
        String stamp = String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d",
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE));
        java.util.TimeZone zone = calendar.getTimeZone();
        int offset = zone.getOffset(now) / 60000;
        String sign = offset < 0 ? "-" : "+";
        String shift = String.format(java.util.Locale.US, "UTC%s%02d:%02d", sign,
            Math.abs(offset) / 60, Math.abs(offset) % 60);
        String id = zone.getID();
        return stamp + "（" + shift + (id == null || id.isEmpty() ? "" : "，" + id) + "）";
    }

    /**
     * 模型约定的五项信息：日期、星期、位置、平台、语言。
     *
     * 格式由服务端强校验（字段数、日期 YYYY-MM-DD、平台只能是 App/Web），
     * 多一个逗号就会把后面的字段挤错位，所以这里严格保持 5 段。
     */
    public static String createLatestReminder(String location, String language, long now) {
        return Util.dateYmd(now) + "," + Util.weekdayZh(now) + ","
            + (location == null ? "" : location) + "," + PLATFORM + ","
            + (language == null || language.isEmpty() ? DEFAULT_LANGUAGE : language);
    }

    /** 一次请求实际占用的上下文 token：输入 + 缓存读 + 缓存写 + 输出。 */
    public static long contextTokens(JSONObject usage) {
        if (usage == null) {
            return 0;
        }
        return (long) usage.optInt("input_tokens", 0)
            + usage.optInt("cache_creation_input_tokens", 0)
            + usage.optInt("cache_read_input_tokens", 0)
            + usage.optInt("output_tokens", 0);
    }

    /** 供界面展示的用量摘要。 */
    public static String usageText(JSONObject usage) {
        if (usage == null) {
            return "";
        }
        Long input = usageCount(usage, "input_tokens");
        Long read = usageCount(usage, "cache_read_input_tokens");
        Long write = usageCount(usage, "cache_creation_input_tokens");
        Long output = usageCount(usage, "output_tokens");
        String cache = "?";
        if (read != null && write != null) {
            try { cache = String.valueOf(Math.addExact(read, write)); }
            catch (ArithmeticException ignored) { }
        }
        return "in " + (input == null ? "?" : input.toString())
            + " / cache " + cache + " / out " + (output == null ? "?" : output.toString());
    }
}
