package com.nativechat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息模型。与网页版 `src/services/chat/types.ts` 一一对应：
 * - 提问（user）：正文 + 附件列表；
 * - 回复（assistant）：有序内容块数组（原样保存服务端下发的 JSONObject，签名等字段不丢），
 *   外加状态、结束原因、思考/搜索开关、思考耗时、用量、错误、本轮系统快照、能否续写。
 *
 * 内容块用 JSONObject 而不是强类型，是因为 READ_LINK 结果块需要原样回传
 * （signature / content / kind 一个字都不能改），强类型转换迟早丢字段。
 */
public final class Msg {
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    /** pending → streaming → done | stopped | error */
    public static final String PENDING = "pending";
    public static final String STREAMING = "streaming";
    public static final String DONE = "done";
    public static final String STOPPED = "stopped";
    public static final String ERROR = "error";

    public String id = "";
    public String sessionId = "";
    public String parentId;
    public String role = USER;
    public long createdAt;
    /** 当前选中的子节点；null 表示这条消息是最新一条 */
    public String selectedChild;

    // ---- 提问 ----
    public String content = "";
    public final List<FileAtt> files = new ArrayList<>();

    // ---- 回复 ----
    public final List<JSONObject> blocks = new ArrayList<>();
    public String status = PENDING;
    public String stopReason;
    /** 仅 refusal 时下发，原样保留 */
    public JSONObject stopDetails;
    public String model;
    public boolean thinking;
    public boolean search;
    /** 是否允许作为末尾 assistant 续写前缀 */
    public boolean prefix;
    public JSONObject usage;
    /** {kind, message, status, retryable} */
    public JSONObject error;
    /** 本轮 user 前插入的系统信息快照 */
    public String latestReminder;

    public boolean isUser() {
        return USER.equals(role);
    }

    /** 附件：本地原件 + Files API 的 file_id。 */
    public static final class FileAtt {
        public String id = "";
        public String name = "";
        public long size;
        public boolean isImage;
        public String mimeType = "";
        /** 应用私有目录下的相对文件名；导入的历史附件可能没有原件，此时为 null */
        public String path;
        /** POST /anthropic/v1/files 拿到的 id；null 表示还没上传 */
        public String serverId;
        /** 仅导入过程使用：归档里的条目名，落盘后不再需要 */
        public String exportPath;

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("name", name);
                json.put("size", size);
                json.put("isImage", isImage);
                json.put("mimeType", mimeType);
                if (path != null) {
                    json.put("path", path);
                }
                if (serverId != null) {
                    json.put("serverId", serverId);
                }
            } catch (Exception ignored) {
                // JSONObject.put 只在 key 为 null 时抛异常，这里不会发生
            }
            return json;
        }

        public static FileAtt fromJson(JSONObject json) {
            FileAtt file = new FileAtt();
            file.id = json.optString("id", "");
            file.name = json.optString("name", "");
            file.size = json.optLong("size", 0);
            file.isImage = json.optBoolean("isImage", false);
            file.mimeType = json.optString("mimeType", "");
            file.path = json.has("path") ? json.optString("path", null) : null;
            file.serverId = json.has("serverId") ? json.optString("serverId", null) : null;
            return file;
        }

        /** 纯文本/代码用 user_data，PDF 与 Office 需要版面解析。 */
        public String purpose() {
            if (isImage) {
                return null;
            }
            String ext = Util.ext(name);
            if ("pdf".equals(ext) || "doc".equals(ext) || "docx".equals(ext)
                || "ppt".equals(ext) || "pptx".equals(ext)) {
                return "rich_layout_recognition";
            }
            return "user_data";
        }
    }

    // ---------------- 内容块构造 ----------------

    public static JSONObject textBlock(String text) {
        JSONObject block = new JSONObject();
        try {
            block.put("type", "text");
            block.put("text", text);
        } catch (Exception ignored) {
        }
        return block;
    }

    public static JSONObject imageBlock(String fileId) {
        return fileBlock("image", fileId);
    }

    public static JSONObject documentBlock(String fileId) {
        return fileBlock("document", fileId);
    }

    private static JSONObject fileBlock(String type, String fileId) {
        JSONObject block = new JSONObject();
        try {
            JSONObject source = new JSONObject();
            source.put("type", "file");
            source.put("file_id", fileId);
            block.put("type", type);
            block.put("source", source);
        } catch (Exception ignored) {
        }
        return block;
    }

    // ---------------- 读取辅助 ----------------

    /** 所有正文块拼起来（复制、判断是否有回答都用它）。 */
    public String replyText() {
        StringBuilder text = new StringBuilder();
        for (JSONObject block : blocks) {
            if ("text".equals(block.optString("type"))) {
                text.append(block.optString("text", ""));
            }
        }
        return text.toString();
    }

    public boolean hasTemplateBlock() {
        for (JSONObject block : blocks) {
            if ("text".equals(block.optString("type")) && block.optBoolean("isTemplate", false)) {
                return true;
            }
        }
        return false;
    }

    /** 思考块（按出现顺序），用于展示。 */
    public List<JSONObject> thinkingBlocks() {
        List<JSONObject> list = new ArrayList<>();
        for (JSONObject block : blocks) {
            if ("thinking".equals(block.optString("type"))) {
                list.add(block);
            }
        }
        return list;
    }

    // ---------------- JSON 编解码 ----------------

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("role", role);
            json.put("createdAt", createdAt);
            if (parentId != null) {
                json.put("parentId", parentId);
            }
            if (selectedChild != null) {
                json.put("selectedChild", selectedChild);
            }
            if (isUser()) {
                json.put("content", content);
                if (!files.isEmpty()) {
                    JSONArray array = new JSONArray();
                    for (FileAtt file : files) {
                        array.put(file.toJson());
                    }
                    json.put("files", array);
                }
            } else {
                JSONArray array = new JSONArray();
                for (JSONObject block : blocks) {
                    array.put(block);
                }
                json.put("blocks", array);
                json.put("status", status);
                if (stopReason != null) {
                    json.put("stopReason", stopReason);
                }
                if (stopDetails != null) {
                    json.put("stopDetails", stopDetails);
                }
                if (model != null) {
                    json.put("model", model);
                }
                json.put("thinking", thinking);
                json.put("search", search);
                json.put("prefix", prefix);
                if (usage != null) {
                    json.put("usage", usage);
                }
                if (error != null) {
                    json.put("error", error);
                }
                if (latestReminder != null) {
                    json.put("latestReminder", latestReminder);
                }
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    public static Msg fromJson(String raw) {
        Msg message = new Msg();
        JSONObject json;
        try {
            json = new JSONObject(raw);
        } catch (Exception error) {
            return message;
        }
        message.id = json.optString("id", "");
        message.role = json.optString("role", USER);
        message.createdAt = json.optLong("createdAt", 0L);
        message.parentId = json.has("parentId") ? json.optString("parentId", null) : null;
        message.selectedChild = json.has("selectedChild") ? json.optString("selectedChild", null) : null;
        if (message.isUser()) {
            message.content = json.optString("content", "");
            JSONArray files = json.optJSONArray("files");
            if (files != null) {
                for (int index = 0; index < files.length(); index++) {
                    JSONObject item = files.optJSONObject(index);
                    if (item != null) {
                        message.files.add(FileAtt.fromJson(item));
                    }
                }
            }
        } else {
            JSONArray blocks = json.optJSONArray("blocks");
            if (blocks != null) {
                for (int index = 0; index < blocks.length(); index++) {
                    JSONObject item = blocks.optJSONObject(index);
                    if (item != null) {
                        message.blocks.add(item);
                    }
                }
            }
            message.status = json.optString("status", DONE);
            message.stopReason = json.has("stopReason") ? json.optString("stopReason", null) : null;
            message.stopDetails = json.optJSONObject("stopDetails");
            message.model = json.has("model") ? json.optString("model", null) : null;
            message.thinking = json.optBoolean("thinking", false);
            message.search = json.optBoolean("search", false);
            message.prefix = json.optBoolean("prefix", false);
            message.usage = json.optJSONObject("usage");
            message.error = json.optJSONObject("error");
            message.latestReminder = json.has("latestReminder")
                ? json.optString("latestReminder", null) : null;
        }
        return message;
    }

    /** 深拷贝，流式刷新时让界面拿到独立快照。 */
    public Msg copy() {
        Msg clone = fromJson(toJson().toString());
        clone.sessionId = sessionId;
        return clone;
    }
}
