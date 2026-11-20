package com.nativechat;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 导入旧版原生 App / 网页版「原始数据包」，两种来源都支持：
 *
 * 1. `chat-demo-raw-*.zip`（网页版「原始导出」）：meta.json + sessions.jsonl
 *    + sessionSummaries.jsonl（忽略）+ files.jsonl + files/{id} 原件；
 * 2. `/__migrate` 的 NDJSON 迁移流：每行 {"store":"sessions|sessionSummaries|files","value":{...}}，
 *    files 的 value 是 {id, type, data(base64)}。
 *
 * 与旧版 `RawImport` 只取正文不同，这里按网页版的本地记录原样搬进 v3 模型：
 * 思考块、工具块、分支（parentId / selectedChild）、附件原件和 serverId 都保留。
 * 写入用 {@link Db#mergeSessions} 单事务合并，失败不改动已有数据。
 */
public final class RawImport {
    private RawImport() {
    }

    public static final class Result {
        public int sessions;
        public int messages;
        public int files;
        /** 识别出的格式：zip / ndjson */
        public String format = "";
    }

    public static final class Invalid extends Exception {
        public Invalid(String message) {
            super(message);
        }
    }

    public static Result importStream(Context context, Db db, InputStream input) throws Exception {
        BufferedInputStream buffered = new BufferedInputStream(input, 64 * 1024);
        buffered.mark(4);
        int a = buffered.read();
        int b = buffered.read();
        buffered.reset();
        boolean zip = a == 'P' && b == 'K';
        return zip ? importZip(context, db, buffered) : importNdjson(context, db, buffered);
    }

    // ================================================================ 原始 ZIP

    private static Result importZip(Context context, Db db, InputStream input) throws Exception {
        File tempDir = new File(context.getCacheDir(), "raw-import");
        deleteTree(tempDir);
        if (!tempDir.mkdirs() && !tempDir.exists()) {
            throw new Invalid("无法创建临时目录");
        }
        Map<String, JSONObject> records = new LinkedHashMap<>();
        Map<String, File> blobs = new HashMap<>();
        Map<String, String> mimes = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("sessions.jsonl".equals(name)) {
                    readSessionLines(zip, records);
                } else if ("meta.json".equals(name)) {
                    checkMeta(readAll(zip));
                } else if ("files.jsonl".equals(name)) {
                    readFileMeta(zip, mimes);
                } else if (name.startsWith("files/") && name.length() > "files/".length()) {
                    String id = name.substring("files/".length());
                    File target = new File(tempDir, safeId(id));
                    copy(zip, target);
                    blobs.put(id, target);
                }
            }
        }
        Result result = new Result();
        result.format = "zip";
        commit(context, db, records, blobs, mimes, result);
        deleteTree(tempDir);
        return result;
    }

    private static void checkMeta(byte[] raw) throws Invalid {
        try {
            JSONObject meta = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String format = meta.optString("format", "");
            if (!format.isEmpty() && !"chat-demo-raw".equals(format)) {
                throw new Invalid("不是网页版原始数据包（" + format + "）");
            }
        } catch (Invalid error) {
            throw error;
        } catch (Exception ignored) {
            // meta.json 只用于提示，解析失败不阻断
        }
    }

    private static void readSessionLines(InputStream input, Map<String, JSONObject> records)
        throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JSONObject record = new JSONObject(line);
                String id = record.optString("id", "");
                if (!id.isEmpty()) {
                    records.put(id, record);
                }
            } catch (Exception ignored) {
                // 单行损坏跳过，其余照常
            }
        }
    }

    /** files.jsonl 每行 {id,type,size}，只有 MIME 有用（原件在 files/{id}）。 */
    private static void readFileMeta(InputStream input, Map<String, String> mimes) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JSONObject item = new JSONObject(line);
                String id = item.optString("id", "");
                String type = item.optString("type", "");
                if (!id.isEmpty() && !type.isEmpty()) {
                    mimes.put(id, type);
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ================================================================ NDJSON

    private static Result importNdjson(Context context, Db db, InputStream input) throws Exception {
        File tempDir = new File(context.getCacheDir(), "raw-import");
        deleteTree(tempDir);
        if (!tempDir.mkdirs() && !tempDir.exists()) {
            throw new Invalid("无法创建临时目录");
        }
        Map<String, JSONObject> records = new LinkedHashMap<>();
        Map<String, File> blobs = new HashMap<>();
        Map<String, String> mimes = new HashMap<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JSONObject item = new JSONObject(line);
                String store = item.optString("store", "");
                JSONObject value = item.optJSONObject("value");
                if (value == null) {
                    continue;
                }
                if ("sessions".equals(store)) {
                    String id = value.optString("id", "");
                    if (!id.isEmpty()) {
                        records.put(id, value);
                    }
                } else if ("files".equals(store)) {
                    String id = value.optString("id", "");
                    if (id.isEmpty()) {
                        continue;
                    }
                    String type = value.optString("type", "");
                    if (!type.isEmpty()) {
                        mimes.put(id, type);
                    }
                    String data = value.optString("data", "");
                    if (data.isEmpty()) {
                        continue;
                    }
                    byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                    File target = new File(tempDir, safeId(id));
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write(bytes);
                    }
                    blobs.put(id, target);
                }
            } catch (Exception ignored) {
                // 单行损坏跳过
            }
        }
        if (records.isEmpty()) {
            throw new Invalid("文件里没有会话记录（既不是原始 ZIP，也不是迁移流）");
        }
        Result result = new Result();
        result.format = "ndjson";
        commit(context, db, records, blobs, mimes, result);
        deleteTree(tempDir);
        return result;
    }

    // ================================================================ 写库

    private static void commit(Context context, Db db, Map<String, JSONObject> records,
                               Map<String, File> blobs, Map<String, String> mimes, Result result)
        throws Exception {
        Attachments attachments = new Attachments(context);
        List<Db.Session> sessions = new ArrayList<>();
        Map<String, List<Msg>> messages = new LinkedHashMap<>();
        List<Msg.FileAtt> files = new ArrayList<>();
        Map<String, Msg.FileAtt> byId = new LinkedHashMap<>();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, JSONObject> entry : records.entrySet()) {
            String sessionId = entry.getKey();
            JSONObject record = entry.getValue();
            Db.Session session = new Db.Session();
            session.id = sessionId;
            session.title = record.optString("title", "");
            session.createdAt = record.optLong("createdAt", now);
            session.updatedAt = record.optLong("updatedAt", session.createdAt);

            List<Msg> list = readMessages(sessionId, record);
            if (list.isEmpty()) {
                continue;
            }
            normalizeTree(record, session, list);
            sessions.add(session);
            messages.put(sessionId, list);
            result.sessions++;
            result.messages += list.size();

            for (Msg message : list) {
                if (!message.isUser()) {
                    continue;
                }
                for (Msg.FileAtt file : message.files) {
                    if (file.id.isEmpty() || byId.containsKey(file.id)) {
                        continue;
                    }
                    File blob = blobs.get(file.id);
                    if (blob != null && blob.exists()) {
                        String name = file.name.isEmpty() ? file.id : file.name;
                        String stored = file.id + "_" + Util.safeName(name);
                        File target = new File(attachments.rawDir(), stored);
                        copyFile(blob, target);
                        file.path = stored;
                        file.size = target.length();
                        if (file.mimeType == null || file.mimeType.isEmpty()) {
                            file.mimeType = mimes.get(file.id);
                        }
                    }
                    byId.put(file.id, file);
                    files.add(file);
                    result.files++;
                }
            }
        }
        if (sessions.isEmpty()) {
            throw new Invalid("没有可导入的会话");
        }
        db.mergeSessions(sessions, messages, files);
    }

    /** web 的本地 Message 记录 → v3 消息（字段几乎一一对应）。 */
    private static List<Msg> readMessages(String sessionId, JSONObject record) {
        List<Msg> list = new ArrayList<>();
        JSONArray array = record.optJSONArray("messages");
        if (array == null) {
            return list;
        }
        long fallback = record.optLong("createdAt", System.currentTimeMillis());
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            Msg message = new Msg();
            message.id = item.optString("id", "");
            if (message.id.isEmpty()) {
                message.id = sessionId + ":" + index;
            }
            message.sessionId = sessionId;
            message.role = "user".equals(item.optString("role")) ? Msg.USER : Msg.ASSISTANT;
            message.createdAt = item.optLong("createdAt", fallback + index);
            message.parentId = item.has("parentId") && !item.isNull("parentId")
                ? item.optString("parentId", null) : null;
            message.selectedChild = item.has("selectedChild") && !item.isNull("selectedChild")
                ? item.optString("selectedChild", null) : null;
            if (message.isUser()) {
                message.content = item.optString("content", "");
                JSONArray files = item.optJSONArray("files");
                for (int at = 0; files != null && at < files.length(); at++) {
                    JSONObject raw = files.optJSONObject(at);
                    if (raw == null) {
                        continue;
                    }
                    Msg.FileAtt file = new Msg.FileAtt();
                    file.id = raw.optString("id", "");
                    file.name = raw.optString("name", "");
                    file.size = raw.optLong("size", 0);
                    file.isImage = raw.optBoolean("isImage", false);
                    file.mimeType = raw.optString("mimeType", "");
                    file.serverId = raw.has("serverId") && !raw.isNull("serverId")
                        ? raw.optString("serverId", null) : null;
                    if (!file.id.isEmpty()) {
                        message.files.add(file);
                    }
                }
            } else {
                JSONArray blocks = item.optJSONArray("blocks");
                for (int at = 0; blocks != null && at < blocks.length(); at++) {
                    JSONObject block = blocks.optJSONObject(at);
                    if (block != null) {
                        message.blocks.add(block);
                    }
                }
                message.status = item.optString("status", Msg.DONE);
                if (!Msg.PENDING.equals(message.status) && !Msg.STREAMING.equals(message.status)) {
                    // 旧版本可能留下 pending/streaming，导入时按已停止收尾
                } else {
                    message.status = Msg.STOPPED;
                }
                message.stopReason = item.has("stopReason") && !item.isNull("stopReason")
                    ? item.optString("stopReason", null) : null;
                message.stopDetails = item.optJSONObject("stopDetails");
                message.model = item.has("model") && !item.isNull("model")
                    ? item.optString("model", null) : null;
                message.thinking = item.optBoolean("thinking", false);
                message.search = item.optBoolean("search", false);
                message.prefix = item.optBoolean("prefix", false);
                message.usage = item.optJSONObject("usage");
                message.error = item.optJSONObject("error");
                message.latestReminder = item.has("latestReminder") && !item.isNull("latestReminder")
                    ? item.optString("latestReminder", null) : null;
            }
            list.add(message);
        }
        return list;
    }

    /** 网页版老记录可能没写 rootMessageId / parentId：按数组顺序补成一条线性树。 */
    private static void normalizeTree(JSONObject record, Db.Session session, List<Msg> list) {
        String root = record.has("rootMessageId") && !record.isNull("rootMessageId")
            ? record.optString("rootMessageId", null) : null;
        Map<String, Msg> byId = new LinkedHashMap<>();
        for (Msg message : list) {
            byId.put(message.id, message);
        }
        boolean hasParents = false;
        for (Msg message : list) {
            if (message.parentId != null) {
                hasParents = true;
                break;
            }
        }
        if (!hasParents) {
            for (int index = 0; index < list.size(); index++) {
                Msg message = list.get(index);
                message.parentId = index == 0 ? null : list.get(index - 1).id;
                message.selectedChild = index == list.size() - 1 ? null : list.get(index + 1).id;
            }
            session.rootMessageId = list.get(0).id;
            return;
        }
        // 有父子关系：没被任何消息选中的节点作为起点（网页版的 default）
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (Msg message : list) {
            if (message.parentId != null) {
                referenced.add(message.parentId);
            }
        }
        for (Msg message : list) {
            if (!referenced.contains(message.id)) {
                root = message.id;
                break;
            }
        }
        if (root == null || !byId.containsKey(root)) {
            root = list.get(0).id;
        }
        session.rootMessageId = root;
        // 未指定子节点的，默认选第一个孩子，保证路径能走到底
        Map<String, Msg> children = new LinkedHashMap<>();
        for (Msg message : list) {
            if (message.parentId != null && byId.containsKey(message.parentId)) {
                Msg parent = byId.get(message.parentId);
                if (parent.selectedChild == null) {
                    parent.selectedChild = message.id;
                }
                children.put(parent.id, message);
            }
        }
    }

    // ================================================================ 工具

    private static String safeId(String id) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < id.length() && out.length() < 96; index++) {
            char ch = id.charAt(index);
            out.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' ? ch : '_');
        }
        return out.length() == 0 ? "blob" : out.toString();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) > 0) {
            out.write(buffer, 0, count);
            if (out.size() > 16 * 1024 * 1024) {
                throw new IOException("meta.json 过大");
            }
        }
        return out.toByteArray();
    }

    private static void copy(InputStream input, File target) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) > 0) {
                total += count;
                if (total > 256L * 1024 * 1024) {
                    throw new IOException("单个附件超过 256MB");
                }
                out.write(buffer, 0, count);
            }
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new java.io.FileInputStream(source)) {
            copy(in, target);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
