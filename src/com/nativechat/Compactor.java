package com.nativechat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 上下文压缩：把较早的轮次交给模型压成一段摘要，后续请求只带「摘要 + 最近的轮次」。
 *
 * 三种风格各用一套提示词：
 * - 通用：结论与关键事实优先；
 * - 技术：保留代码、命令、参数、报错、结论，允许压得更狠；
 * - 陪伴：保留人物关系、称呼、情绪脉络、约定与偏好，不逐句复述。
 *
 * 摘要按会话存在本地文件里，不动数据库结构。
 */
public final class Compactor {
    /** 通用 / 技术 / 陪伴 / 自动判断。 */
    public static final int STYLE_GENERAL = 0;
    public static final int STYLE_TECH = 1;
    public static final int STYLE_COMPANION = 2;
    /** 由模型先判断这段对话属于哪一类，再按对应规则压缩。 */
    public static final int STYLE_AUTO = -1;

    private static final int MAX_CHARS = 4000;

    public static final class Summary {
        public String upto = "";      // 已折叠到的最后一条消息 id
        public String text = "";
        public int rounds;            // 折叠了多少轮
        public int style;
        public long createdAt;

        public boolean covers() {
            return upto != null && !upto.isEmpty() && text != null && !text.isEmpty();
        }
    }

    private Compactor() {
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "compact");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    private static File file(Context context, String sessionId) {
        File root = dir(context);
        return root == null ? null : new File(root, sessionId + ".json");
    }

    public static Summary load(Context context, String sessionId) {
        File file = file(context, sessionId);
        if (file == null || !file.exists()) {
            return null;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file),
            StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                text.append(buffer, 0, count);
            }
            JSONObject json = new JSONObject(text.toString());
            Summary summary = new Summary();
            summary.upto = json.optString("upto", "");
            summary.text = json.optString("text", "");
            summary.rounds = json.optInt("rounds", 0);
            summary.style = json.optInt("style", STYLE_GENERAL);
            summary.createdAt = json.optLong("createdAt", 0);
            return summary.covers() ? summary : null;
        } catch (Exception error) {
            return null;
        }
    }

    public static void save(Context context, String sessionId, Summary summary) {
        File file = file(context, sessionId);
        if (file == null) {
            return;
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file),
            StandardCharsets.UTF_8)) {
            JSONObject json = new JSONObject();
            json.put("upto", summary.upto);
            json.put("text", summary.text);
            json.put("rounds", summary.rounds);
            json.put("style", summary.style);
            json.put("createdAt", summary.createdAt);
            writer.write(json.toString());
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context context, String sessionId) {
        File file = file(context, sessionId);
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** 摘要是否仍然对得上（被折叠的消息还在） */
    public static boolean stillValid(Summary summary, List<Msg> history) {
        if (summary == null || !summary.covers()) {
            return false;
        }
        for (Msg message : history) {
            if (summary.upto.equals(message.id)) {
                return true;
            }
        }
        return false;
    }

    public static String systemPrompt(int style) {
        if (style == STYLE_TECH) {
            return "你是一个技术对话压缩器。把用户提供的对话记录压缩成高密度摘要，供后续对话继续使用。"
                + "必须保留：涉及的代码与命令原文、文件路径、报错信息、关键参数与数值、已确认的结论与决策、未解决的问题。"
                + "可以丢弃：客套、重复确认、探索过程中被否决的方案（除非结论重要）。"
                + "用要点列表输出，不要寒暄，不要复述原文，控制在 800 字以内。";
        }
        if (style == STYLE_COMPANION) {
            return "你是一个长期陪伴对话的记忆整理者。把用户提供的聊天记录整理成一份「关系记忆」，"
                + "供后续继续这段关系时使用。必须覆盖以下四块，缺一不可：\n"
                + "1. 你该怎么说话：你在对话中的身份设定、语气、称呼对方的方式、常用口癖与句式、"
                + "能开的玩笑、明确的边界与禁忌，以及对方希望你保持或避免的表达方式。\n"
                + "2. 对方是个什么样的人：称呼、身份、工作与家庭、健康与作息、性格、在乎的事、"
                + "雷区、偏好与厌恶。\n"
                + "3. 已经发生过什么：重要事件、情绪走向的变化节点、彼此答应过的事、你给过的承诺、"
                + "一起建立的小习惯或暗号。\n"
                + "4. 还没结束什么：未完成的约定、当前正困扰对方的事、下次该主动关心什么。\n"
                + "不要保留：逐句对话、寒暄、重复的安慰话术。用平实的中文分条写，"
                + "保留原有的语气和关系温度，不要写成会议纪要，控制在 1000 字以内。";
        }
        return "你是一个对话压缩器。把用户提供的对话记录压缩成摘要，供后续对话继续使用。"
            + "必须保留：用户的目标与诉求、已达成的结论、重要事实与数据、未完成的事项。"
            + "可以丢弃：寒暄、重复内容、过程性试探。"
            + "用要点列表输出，控制在 800 字以内。";
    }

    /** 让模型判断对话类型；判断失败退回通用。 */
    public static int detectStyle(List<Msg[]> rounds, String origin, String apiKey, String model,
                                  Api.Call call) {
        StringBuilder sample = new StringBuilder();
        for (Msg[] round : rounds) {
            sample.append("用户：").append(trim(round[0].content, 200)).append('\n');
            sample.append("助手：").append(trim(round[1].replyText(), 300)).append("\n\n");
            if (sample.length() > 6000) {
                break;
            }
        }
        if (sample.length() == 0) {
            return STYLE_GENERAL;
        }
        String text;
        try {
            text = ask(origin, apiKey, model,
                "判断下面这段对话的类型，只输出一个词：技术 / 陪伴 / 普通。"
                    + "技术=讨论代码、系统、参数、排错；陪伴=情感支持、闲聊、倾诉、角色扮演；"
                    + "普通=其余日常问答。只输出那个词，不要解释。",
                sample.toString(), call);
        } catch (Api.ApiError error) {
            Diag.log("COMPACT", "类型判断失败 · " + error.kind + "，按普通处理");
            return STYLE_GENERAL;
        }
        if (text.contains("技术")) {
            return STYLE_TECH;
        }
        if (text.contains("陪伴") || text.contains("情感")) {
            return STYLE_COMPANION;
        }
        return STYLE_GENERAL;
    }

    /** 把若干轮对话压成摘要；失败抛 ApiError，由调用方决定退回截断。 */
    public static Summary summarize(Context context, String sessionId, List<Msg[]> folded,
                                    String origin, String apiKey, String model, int style,
                                    Api.Call call) throws Api.ApiError {
        String transcript = transcript(folded, 60000);
        if (transcript.isEmpty()) {
            return null;
        }
        String text = ask(origin, apiKey, model, systemPrompt(style),
            "以下是需要压缩的对话记录：\n\n" + transcript, call);
        if (text.isEmpty()) {
            return null;
        }
        Summary result = new Summary();
        result.text = cap(text);
        result.upto = folded.get(folded.size() - 1)[0].id;
        result.rounds = folded.size();
        result.style = style;
        result.createdAt = System.currentTimeMillis();
        return result;
    }

    /**
     * 压缩校验：让新摘要去问旧上下文自己没弄懂的地方，说清自己的理解，
     * 再交回旧上下文修订补充。多花三次调用，换来更少的信息丢失。
     */
    public static Summary refine(Summary draft, List<Msg[]> folded, String origin, String apiKey,
                                 String model, Api.Call call) {
        String transcript = transcript(folded, 30000);
        if (transcript.isEmpty()) {
            return draft;
        }
        try {
            String questions = ask(origin, apiKey, model,
                "你是这段对话的亲历者。有人把这段对话压成了一份摘要。"
                    + "请指出摘要里含糊、缺失或被误解的地方，提出具体问题要求澄清，"
                    + "最多 6 条，直接列问题，不要客套。",
                "原始对话节选：\n" + transcript + "\n\n摘要：\n" + draft.text, call);

            String understanding = ask(origin, apiKey, model,
                "你是这份摘要的作者。",
                "原始对话节选：\n" + transcript + "\n\n你写的摘要：\n" + draft.text
                    + "\n\n亲历者的问题：\n" + questions
                    + "\n\n请分两点回答：1) 你现在对这段对话的理解；"
                    + "2) 哪些细节你确实没把握、需要亲历者补充。",
                call);

            String revised = ask(origin, apiKey, model,
                systemPrompt(draft.style)
                    + "\n这是一次修订：已有初稿、亲历者的疑问、作者自己的理解说明。"
                    + "请输出修订后的最终摘要，补齐被问到但确实存在于对话中的信息，"
                    + "纠正误解，删掉不确定的推测。只输出摘要本身。",
                "原始对话节选：\n" + transcript + "\n\n初稿摘要：\n" + draft.text
                    + "\n\n亲历者的疑问：\n" + questions
                    + "\n\n作者的理解与缺口：\n" + understanding,
                call);

            if (revised.isEmpty()) {
                Diag.log("COMPACT", "校验没有产出，保留初稿");
                return draft;
            }
            Summary result = new Summary();
            result.text = cap(revised);
            result.upto = draft.upto;
            result.rounds = draft.rounds;
            result.style = draft.style;
            result.createdAt = System.currentTimeMillis();
            Diag.log("COMPACT", "校验完成 · 初稿 " + draft.text.length() + " 字 → 终稿 "
                + result.text.length() + " 字");
            return result;
        } catch (Api.ApiError error) {
            Diag.log("COMPACT", "校验失败，保留初稿 · " + error.kind);
            return draft;
        }
    }

    /** 一次不带工具的纯文本补全，返回输出文本。 */
    private static String ask(String origin, String apiKey, String model, String system,
                              String user, Api.Call call) throws Api.ApiError {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("system", system);
            JSONArray messages = new JSONArray();
            JSONObject turn = new JSONObject();
            turn.put("role", "user");
            turn.put("content", user);
            messages.put(turn);
            body.put("messages", messages);
            JSONObject thinking = new JSONObject();
            thinking.put("type", "disabled");
            body.put("thinking", thinking);
            body.put("stream", true);
        } catch (Exception error) {
            throw new Api.ApiError(Api.PROTOCOL, "请求构造失败", -1);
        }
        final StringBuilder answer = new StringBuilder();
        Api.streamMessages(origin, apiKey, body, false, call, event -> {
            if (!"content_block_delta".equals(event.optString("type", ""))) {
                return;
            }
            JSONObject delta = event.optJSONObject("delta");
            if (delta != null && "text_delta".equals(delta.optString("type"))
                && answer.length() < 12000) {
                answer.append(delta.optString("text", ""));
            }
        });
        return answer.toString().trim();
    }

    /** 把轮次拼成纯文本记录，超出上限就截断。 */
    private static String transcript(List<Msg[]> rounds, int budget) {
        StringBuilder text = new StringBuilder();
        for (Msg[] round : rounds) {
            String asked = round[0].content == null ? "" : round[0].content.trim();
            String answered = round[1].replyText().trim();
            if (asked.isEmpty() && answered.isEmpty()) {
                continue;
            }
            text.append("用户：").append(trim(asked, 800)).append('\n');
            text.append("助手：").append(trim(answered, 1200)).append("\n\n");
            if (text.length() > budget) {
                break;
            }
        }
        return text.toString();
    }

    private static String cap(String text) {
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String clean = text.replace('\r', ' ');
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
