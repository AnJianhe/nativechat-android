package com.nativechat;

import org.json.JSONObject;

/**
 * 回复出错或未正常结束时展示/导出用的文案，对应 `chat/describe.ts`。
 * 界面用红字展示错误，模板回复用正文样式；导出时把模板文案写进 TEMPLATE_RESPONSE，
 * 这样导入后即使拿不到错误结构也还能显示原因。
 */
public final class Describe {
    private Describe() {
    }

    public static final class Notice {
        public final String text;
        /** 模板答复使用正文样式；其余统一为错误样式 */
        public final boolean response;

        Notice(String text, boolean response) {
            this.text = text;
            this.response = response;
        }
    }

    public static final String NETWORK = "网络异常，请检查你的网络状况";
    public static final String INVALID_KEY = "API Key 无效或已被删除，请更换后重试";
    public static final String NO_BALANCE = "账户余额不足，请充值后重试";
    public static final String RATE_LIMITED = "请求过于频繁，请稍后再试";
    public static final String SERVER_BUSY = "服务器繁忙，请稍后再试";
    public static final String REFUSED = "你好，这个问题我暂时无法回答，让我们换个话题再聊聊吧。";
    public static final String UPLOAD_FAILED = "上传失败";
    public static final String SERVER_BUSY_SHORT = "服务器繁忙";
    public static final String PARSE_FAILED = "解析失败";
    public static final String INTERRUPTED = "连接中断，回答可能不完整";

    private static String templateText(Msg reply) {
        StringBuilder text = new StringBuilder();
        for (JSONObject block : reply.blocks) {
            if ("text".equals(block.optString("type")) && block.optBoolean("isTemplate", false)) {
                text.append(block.optString("text", ""));
            }
        }
        return text.toString();
    }

    /** 回复出错、被拒绝、或带服务端模板时的提示；正常回复返回 null。 */
    public static Notice describeReply(Msg reply) {
        if (reply == null || reply.isUser()) {
            return null;
        }
        if (Msg.ERROR.equals(reply.status) && reply.error != null) {
            String kind = reply.error.optString("kind", "");
            String message = reply.error.optString("message", "");
            if (Api.UPLOAD.equals(kind)) {
                return new Notice(message.isEmpty() ? UPLOAD_FAILED : message, false);
            }
            if (Api.NETWORK.equals(kind)) {
                return new Notice(NETWORK, false);
            }
            int status = reply.error.optInt("status", 0);
            String condition = Api.condition(status);
            if ("unauthorized".equals(condition)) {
                return new Notice(INVALID_KEY, false);
            }
            if ("insufficientBalance".equals(condition)) {
                return new Notice(NO_BALANCE, false);
            }
            if ("rateLimited".equals(condition)) {
                return new Notice(RATE_LIMITED, false);
            }
            if ("serverBusy".equals(condition)) {
                return new Notice(SERVER_BUSY, false);
            }
            if (Api.INTERRUPTED.equals(kind) && message.isEmpty()) {
                return new Notice(INTERRUPTED, false);
            }
            return message.isEmpty() ? null : new Notice(message, false);
        }
        String template = templateText(reply);
        if (Msg.ERROR.equals(reply.status) && !template.isEmpty()) {
            return new Notice(template, false);
        }
        if ("refusal".equals(reply.stopReason)) {
            return new Notice(template.isEmpty() ? REFUSED : template, true);
        }
        if (!template.isEmpty()) {
            return new Notice(template, true);
        }
        return null;
    }

    /** 上传失败在附件卡片上显示的文案。 */
    public static String describeUpload(Api.ApiError error) {
        String condition = Api.condition(error);
        if ("serverBusy".equals(condition)) {
            return SERVER_BUSY_SHORT;
        }
        return UPLOAD_FAILED;
    }

    /** 停止生成但一个块都没收到时的占位。 */
    public static final String STOPPED_EMPTY = "（已停止）";
}
