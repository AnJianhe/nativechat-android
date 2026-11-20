package com.nativechat;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 历史会话导入导出，格式与网页版完全一致（`export_format_version: 3`）：
 *
 *   manifest.json                                  格式版本，第一个条目
 *   chat_sessions/{session_id}.json                会话 JSON（含消息树）
 *   chat_session_index.json                        会话索引
 *   chat_sessions/{session_id}_files/{file_id}_{name}  图片原件
 *
 * 导出用 STORE（与网页版一致，不压缩）；导入先全量解析校验，再用一个事务原子替换本地数据，
 * 任何一步失败都保留原有记录。
 */
public final class Archive {
    public static final int VERSION = 3;

    private static final String MANIFEST = "manifest.json";
    private static final String INDEX = "chat_session_index.json";
    private static final String SESSION_DIR = "chat_sessions/";
    private static final long MAX_JSON = 16L * 1024 * 1024;
    private static final long MAX_IMAGE = Attachments.MAX_SIZE;

    private Archive() {
    }

    /** 导入校验失败：文案直接给用户看。 */
    public static final class Backup extends Exception {
        public Backup(String message) {
            super(message);
        }
    }

    public static final class Result {
        public int sessions;
        public int messages;
        public int files;
    }

    private static final String INCOMPLETE = "文件内容不完整，无法导入";
    private static final String INVALID_BRANCHES = "文件中的消息分支已损坏，无法导入";
    private static final String INVALID_FILES = "文件中的附件数据已损坏，无法导入";

    // ================================================================ 导出

    public static Result export(Context context, Db db, Uri target) throws Exception {
        Attachments attachments = new Attachments(context);
        List<Db.Session> sessions = db.sessions();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<JSONObject> index = new ArrayList<>();
        Map<String, File> images = new LinkedHashMap<>();

        for (Db.Session session : sessions) {
            JSONObject file = toSessionFile(session, db, attachments, images);
            entries.put(SESSION_DIR + session.id + ".json",
                file.toString().getBytes(StandardCharsets.UTF_8));
            index.add(toIndexEntry(file));
        }
        // manifest 必须是第一个条目：先占位再回填顺序
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        JSONObject manifest = new JSONObject();
        manifest.put("export_format_version", VERSION);
        ordered.put(MANIFEST, manifest.toString().getBytes(StandardCharsets.UTF_8));
        ordered.putAll(entries);
        ordered.put(INDEX, new JSONArray(index).toString().getBytes(StandardCharsets.UTF_8));

        Result result = new Result();
        result.sessions = sessions.size();
        result.messages = 0;
        for (Db.Session session : sessions) {
            result.messages += db.messages(session.id).size();
        }
        result.files = images.size();

        OutputStream raw = context.getContentResolver().openOutputStream(target, "w");
        if (raw == null) {
            throw new IOException("无法写入目标文件");
        }
        try (ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                writeEntry(zip, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, File> image : images.entrySet()) {
                writeFileEntry(zip, image.getKey(), image.getValue());
            }
        }
        return result;
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        entry.setTime(315532800000L); // 1980-01-01，与网页版写死的 mtime 一致
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void writeFileEntry(ZipOutputStream zip, String name, File file) throws IOException {
        // STORED 需要预先计算 CRC 和长度；分两遍读取，避免把图片载入内存。
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        long size = 0;
        byte[] buffer = new byte[65536];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                size += n;
                if (size > MAX_IMAGE) throw new IOException("图片条目过大");
                crc.update(buffer, 0, n);
            }
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
        entry.setTime(315532800000L);
        zip.putNextEntry(entry);
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
        }
        zip.closeEntry();
    }

    /** 单个会话 → SessionFile；图片原件顺手收集成待写入的条目。 */
    private static JSONObject toSessionFile(Db.Session session, Db db, Attachments attachments,
                                            Map<String, File> images) throws Exception {
        List<Msg> messages = db.messages(session.id);
        Map<String, Msg> byId = new HashMap<>();
        for (Msg message : messages) {
            if (message.id == null || byId.put(message.id, message) != null) {
                throw new Backup(INVALID_BRANCHES);
            }
        }
        Map<String, Integer> ids = new HashMap<>();
        List<Msg> ordered = new ArrayList<>();
        for (Msg message : messages) {
            visit(message, byId, ids, ordered);
        }
        // 提问的开关取首个回答的请求设置
        Map<String, Msg> firstReply = new HashMap<>();
        for (Msg message : ordered) {
            if (!message.isUser() && message.parentId != null
                && !firstReply.containsKey(message.parentId)) {
                firstReply.put(message.parentId, message);
            }
        }
        JSONArray array = new JSONArray();
        for (Msg message : ordered) {
            JSONObject item = new JSONObject();
            item.put("id", ids.get(message.id));
            item.put("parent_id", message.parentId == null ? JSONObject.NULL
                : (Object) ids.get(message.parentId));
            item.put("role", message.isUser() ? "USER" : "ASSISTANT");
            Msg reply = message.isUser() ? firstReply.get(message.id) : message;
            item.put("status", message.isUser() ? "FINISHED" : replyStatus(message));
            item.put("thinking_enabled", reply != null && reply.thinking);
            item.put("search_enabled", reply != null && reply.search);
            item.put("accumulated_token_usage", message.isUser() ? 0 : contextTokens(message));
            item.put("inserted_at", Util.iso(message.createdAt));
            item.put("fragments", message.isUser()
                ? userFragments(session.id, message, attachments, images)
                : assistantFragments(message));
            array.put(item);
        }
        List<Msg> path = db.visibleMessages(session.id);
        int current = 0;
        if (!path.isEmpty()) {
            Integer mapped = ids.get(path.get(path.size() - 1).id);
            current = mapped == null ? 0 : mapped;
        }
        JSONObject file = new JSONObject();
        file.put("id", session.id);
        file.put("title", session.title == null || session.title.isEmpty()
            ? JSONObject.NULL : session.title);
        file.put("pinned", false);
        file.put("current_message_id", current);
        file.put("inserted_at", Util.iso(session.createdAt));
        file.put("updated_at", Util.iso(session.updatedAt));
        file.put("messages", array);
        return file;
    }

    /** 父节点优先重编号为 1…n，保证 parent_id 小于 id。 */
    private static void visit(Msg message, Map<String, Msg> byId, Map<String, Integer> ids,
                              List<Msg> ordered) throws Backup {
        List<Msg> chain = new ArrayList<>();
        java.util.Set<String> visiting = new java.util.HashSet<>();
        Msg node = message;
        while (node != null && !ids.containsKey(node.id)) {
            if (node.id == null || !visiting.add(node.id)) {
                throw new Backup(INVALID_BRANCHES);
            }
            chain.add(node);
            if (node.parentId == null) break;
            node = byId.get(node.parentId);
            if (node == null) throw new Backup(INVALID_BRANCHES);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            Msg next = chain.get(i);
            ids.put(next.id, ordered.size() + 1);
            ordered.add(next);
        }
    }

    private static JSONObject toIndexEntry(JSONObject file) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", file.optString("id"));
            entry.put("title", file.isNull("title") ? JSONObject.NULL : file.opt("title"));
            entry.put("pinned", false);
            entry.put("message_count", file.optJSONArray("messages") == null
                ? 0 : file.optJSONArray("messages").length());
            entry.put("inserted_at", file.optString("inserted_at"));
            entry.put("updated_at", file.optString("updated_at"));
        } catch (Exception ignored) {
        }
        return entry;
    }

    private static String replyStatus(Msg message) {
        if ("refusal".equals(message.stopReason)) {
            return "CONTENT_FILTER";
        }
        if (Msg.DONE.equals(message.status)) {
            return "max_tokens".equals(message.stopReason) ? "INCOMPLETE" : "FINISHED";
        }
        if (Msg.STOPPED.equals(message.status)) {
            return "INCOMPLETE";
        }
        if (Msg.ERROR.equals(message.status)) {
            return "FAILED";
        }
        return "WIP";
    }

    private static int contextTokens(Msg message) {
        if (message.usage == null) {
            return 0;
        }
        return message.usage.optInt("input_tokens", 0)
            + message.usage.optInt("cache_creation_input_tokens", 0)
            + message.usage.optInt("cache_read_input_tokens", 0)
            + message.usage.optInt("output_tokens", 0);
    }

    private static JSONArray userFragments(String sessionId, Msg message, Attachments attachments,
                                           Map<String, File> images) throws Exception {
        JSONArray fragments = new JSONArray();
        if (!message.files.isEmpty()) {
            JSONArray files = new JSONArray();
            for (Msg.FileAtt file : message.files) {
                JSONObject entry = new JSONObject();
                entry.put("id", file.id);
                entry.put("file_name", file.name);
                entry.put("file_size", file.size);
                entry.put("token_usage", JSONObject.NULL);
                entry.put("inserted_at", Util.iso(message.createdAt));
                Object path = JSONObject.NULL;
                if (file.isImage) {
                    File data = attachments.local(file);
                    if (data != null) {
                        String name = SESSION_DIR + sessionId + "_files/" + file.id + "_"
                            + Util.safeName(file.name);
                        File previous = images.get(name);
                        if (previous != null && !previous.equals(data)) {
                            throw new Backup(INVALID_FILES);
                        }
                        images.put(name, data);
                        path = name;
                    }
                }
                entry.put("export_path", path);
                entry.put("is_image", file.isImage);
                entry.put("width", JSONObject.NULL);
                entry.put("height", JSONObject.NULL);
                files.put(entry);
            }
            JSONObject fragment = new JSONObject();
            fragment.put("type", "FILE");
            fragment.put("files", files);
            fragments.put(fragment);
        }
        JSONObject request = new JSONObject();
        request.put("type", "REQUEST");
        request.put("content", message.content == null ? "" : message.content);
        fragments.put(request);
        return fragments;
    }

    private static JSONArray assistantFragments(Msg message) throws Exception {
        Map<String, JSONObject> results = new HashMap<>();
        for (JSONObject block : message.blocks) {
            String type = block.optString("type");
            if ("web_search_tool_result".equals(type) || "web_fetch_tool_result".equals(type)) {
                results.put(block.optString("tool_use_id", ""), block);
            }
        }
        boolean usedWeb = false;
        for (JSONObject block : message.blocks) {
            if ("server_tool_use".equals(block.optString("type"))) {
                usedWeb = true;
                break;
            }
        }
        JSONArray fragments = new JSONArray();
        JSONObject response = null;
        for (JSONObject block : message.blocks) {
            String type = block.optString("type", "");
            if ("text".equals(type)) {
                String kind = block.optBoolean("isTemplate", false) ? "TEMPLATE_RESPONSE" : "RESPONSE";
                if (response == null || !kind.equals(response.optString("type"))) {
                    response = new JSONObject();
                    response.put("type", kind);
                    response.put("content", "");
                    fragments.put(response);
                }
                String text = block.optString("text", "");
                if (usedWeb) {
                    text = text.replaceAll("\\[citation:\\d+\\]", "");
                }
                response.put("content", response.optString("content", "") + text);
                continue;
            }
            JSONArray converted = new JSONArray();
            if ("thinking".equals(type)) {
                String thinking = block.optString("thinking", "");
                if (!thinking.isEmpty()) {
                    JSONObject fragment = new JSONObject();
                    fragment.put("type", "THINK");
                    fragment.put("content", thinking);
                    fragment.put("elapsed_secs", block.has("startedAt") && block.has("endedAt")
                        ? Prompt.thinkingSeconds(block) : JSONObject.NULL);
                    converted.put(fragment);
                }
            } else if ("server_tool_use".equals(type)) {
                converted = toolFragment(block, results.get(block.optString("id", "")));
            }
            if (converted.length() > 0) {
                for (int index = 0; index < converted.length(); index++) {
                    fragments.put(converted.get(index));
                }
                response = null;
            }
        }
        // 错误与拒绝提示作为模板保存，导入后仍不进上下文
        boolean hasTemplate = message.hasTemplateBlock();
        Describe.Notice notice = Describe.describeReply(message);
        if ((Msg.ERROR.equals(message.status) && message.error != null)
            || (!hasTemplate && "refusal".equals(message.stopReason))) {
            if (notice != null) {
                JSONObject template = new JSONObject();
                template.put("type", "TEMPLATE_RESPONSE");
                template.put("content", notice.text);
                fragments.put(template);
            }
        }
        return fragments;
    }

    private static JSONArray toolFragment(JSONObject tool, JSONObject result) throws Exception {
        JSONArray fragments = new JSONArray();
        JSONObject input = tool.optJSONObject("input");
        if (input == null) {
            return fragments;
        }
        String action = input.optString("action", "");
        if ("search".equals(action)) {
            JSONObject fragment = new JSONObject();
            fragment.put("type", "TOOL_SEARCH");
            JSONArray queries = input.optJSONArray("queries");
            fragment.put("queries", queries == null ? new JSONArray() : queries);
            JSONArray results = new JSONArray();
            boolean hasResult = result != null
                && "web_search_tool_result".equals(result.optString("type"));
            boolean failed = false;
            if (hasResult) {
                JSONArray content = result.optJSONArray("content");
                for (int index = 0; content != null && index < content.length(); index++) {
                    JSONObject item = content.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    if ("web_search_tool_result_error".equals(item.optString("type"))) {
                        failed = true;
                        continue;
                    }
                    JSONObject entry = new JSONObject();
                    entry.put("url", item.optString("url", ""));
                    entry.put("title", item.optString("title", ""));
                    entry.put("snippet", item.optString("snippet", ""));
                    entry.put("published_at", isoOrNull(item.optString("page_age", "")));
                    entry.put("site_name", item.isNull("site_name") ? JSONObject.NULL
                        : item.opt("site_name"));
                    entry.put("site_icon", item.isNull("site_icon") ? JSONObject.NULL
                        : item.opt("site_icon"));
                    results.put(entry);
                }
            }
            fragment.put("status", !hasResult ? "WIP" : failed ? "FAILED" : "FINISHED");
            fragment.put("results", results);
            fragments.put(fragment);
            return fragments;
        }
        if ("read_link".equals(action)) {
            if (result == null || !"web_fetch_tool_result".equals(result.optString("type"))
                || !"read_link".equals(result.optString("kind"))) {
                return fragments;
            }
            JSONObject content = result.optJSONObject("content");
            String signature = result.optString("signature", "");
            if (content == null || !"web_fetch_result".equals(content.optString("type"))
                || signature.trim().isEmpty()) {
                return fragments;
            }
            JSONObject entry = new JSONObject();
            String url = content.optString("url", "");
            if (url.isEmpty()) {
                url = input.optString("url", "");
            }
            if (!url.isEmpty()) {
                entry.put("url", url);
            }
            JSONObject inner = content.optJSONObject("content");
            entry.put("title", inner == null || inner.isNull("title") ? JSONObject.NULL
                : inner.opt("title"));
            entry.put("signature", signature);
            JSONArray list = new JSONArray();
            list.put(entry);
            JSONObject fragment = new JSONObject();
            fragment.put("type", "READ_LINK");
            fragment.put("results", list);
            fragments.put(fragment);
            return fragments;
        }
        JSONObject content = result == null ? null : result.optJSONObject("content");
        JSONObject page = content != null && "web_fetch_result".equals(content.optString("type"))
            ? content : null;
        String status = content == null ? "WIP" : page != null ? "FINISHED" : "FAILED";
        if ("find_in_page".equals(action)) {
            JSONObject fragment = new JSONObject();
            fragment.put("type", "TOOL_FIND");
            fragment.put("status", status);
            fragment.put("pattern", input.optString("pattern", ""));
            fragments.put(fragment);
            return fragments;
        }
        JSONObject fragment = new JSONObject();
        fragment.put("type", "TOOL_OPEN");
        fragment.put("status", status);
        String url = input.optString("url", "");
        if (url.isEmpty() && page != null) {
            url = page.optString("url", "");
        }
        fragment.put("url", url.isEmpty() ? JSONObject.NULL : url);
        Object title = JSONObject.NULL;
        if (page != null) {
            JSONObject inner = page.optJSONObject("content");
            if (inner != null && !inner.isNull("title")) {
                title = inner.opt("title");
            }
        }
        fragment.put("title", title);
        fragment.put("snippet", page != null ? "" : JSONObject.NULL);
        fragment.put("site_name", JSONObject.NULL);
        fragment.put("published_at", page == null ? JSONObject.NULL
            : isoOrNull(page.optString("published_at", "")));
        fragments.put(fragment);
        return fragments;
    }

    private static Object isoOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return JSONObject.NULL;
        }
        try {
            return Util.iso(Util.parseIso(value));
        } catch (Exception error) {
            return JSONObject.NULL;
        }
    }

    // ================================================================ 导入

    /** 判断选中的 ZIP 是不是 v3 历史包：有 manifest.json 就是。合并入口时用来分流。 */
    public static boolean isHistoryZip(Context context, Uri source) {
        File temp = null;
        try {
            temp = copyToCache(context, source);
            try (ZipFile zip = new ZipFile(temp)) {
                return zip.getEntry(MANIFEST) != null;
            }
        } catch (Exception error) {
            return false;
        } finally {
            if (temp != null) {
                temp.delete();
            }
        }
    }

    public static Result importArchive(Context context, Db db, Uri source) throws Exception {
        File temp = copyToCache(context, source);
        List<File> stagedFiles = new ArrayList<>();
        boolean committed = false;
        try (ZipFile zip = new ZipFile(temp)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null) {
                throw new Backup(INCOMPLETE + "（缺少 manifest.json）");
            }
            checkVersion(new JSONObject(new String(readEntry(zip, manifestEntry), StandardCharsets.UTF_8)));

            // 1. 逐个会话文件解析并立刻转换，不在内存里堆原始 JSON（导出可能几百 MB）
            Map<String, Local> locals = new LinkedHashMap<>();
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            java.util.Set<String> entryNames = new java.util.HashSet<>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entryNames.add(name)) {
                    throw new Backup(INCOMPLETE + "（ZIP 里有重名条目 " + name + "）");
                }
                if (!name.startsWith(SESSION_DIR) || !name.endsWith(".json")) {
                    continue;
                }
                String rest = name.substring(SESSION_DIR.length());
                if (rest.contains("/")) {
                    continue;
                }
                JSONObject json;
                try {
                    json = new JSONObject(new String(readEntry(zip, entry), StandardCharsets.UTF_8));
                } catch (Exception error) {
                    throw new Backup(INCOMPLETE + "（会话文件 " + name + " 不是合法 JSON）");
                }
                String id = requiredString(json, "id");
                if (!validSessionId(id) || !(SESSION_DIR + id + ".json").equals(name)) {
                    throw new Backup(INCOMPLETE + "（会话文件 " + name + " 的 id 与文件名不一致）");
                }
                if (locals.containsKey(id)) {
                    throw new Backup(INCOMPLETE + "（会话 " + id + " 重复）");
                }
                locals.put(id, toLocal(id, json));
            }

            // 2. 索引必须与会话一一对应；官方导出若不写索引，按会话文件自建
            ZipEntry indexEntry = zip.getEntry(INDEX);
            if (indexEntry == null) {
                // 官方导出可能不带索引，不因此拒绝整份文件
            } else {
                JSONArray index;
                try {
                    index = new JSONArray(new String(readEntry(zip, indexEntry), StandardCharsets.UTF_8));
                } catch (Exception error) {
                    throw new Backup(INCOMPLETE + "（chat_session_index.json 不是合法 JSON）");
                }
                java.util.Set<String> listed = new java.util.HashSet<>();
                for (int at = 0; at < index.length(); at++) {
                    JSONObject item = index.optJSONObject(at);
                    if (item == null) {
                        throw new Backup(INCOMPLETE + "（索引第 " + at + " 项不是对象）");
                    }
                    String id = requiredString(item, "id");
                    requiredNullableString(item, "title");
                    requiredCount(item, "message_count");
                    requiredTime(item, "inserted_at");
                    requiredTime(item, "updated_at");
                    if (!validSessionId(id) || !locals.containsKey(id)) {
                        throw new Backup(INCOMPLETE + "（索引里的会话 " + id + " 在 ZIP 中不存在）");
                    }
                    if (!listed.add(id)) {
                        throw new Backup(INCOMPLETE + "（索引里重复列出 " + id + "）");
                    }
                }
                if (listed.size() != locals.size()) {
                    throw new Backup(INCOMPLETE + "（索引 " + listed.size() + " 条，会话文件 "
                        + locals.size() + " 个，对不上）");
                }
            }

            // 3. 汇总（此时还没动本地数据）
            List<Db.Session> sessions = new ArrayList<>();
            Map<String, List<Msg>> messages = new LinkedHashMap<>();
            List<Msg.FileAtt> files = new ArrayList<>();
            Map<String, Msg.FileAtt> byFileId = new HashMap<>();
            Attachments attachments = new Attachments(context);
            for (Map.Entry<String, Local> entry : locals.entrySet()) {
                Local local = entry.getValue();
                sessions.add(local.session);
                messages.put(local.session.id, local.messages);
                for (Msg.FileAtt file : local.allFiles) {
                    if (!byFileId.containsKey(file.id)) {
                        byFileId.put(file.id, file);
                        files.add(file);
                    }
                }
                // 4. 图片原件写入独立暂存文件，绝不使用归档中的路径或 ID 命名。
                for (Map.Entry<String, Msg.FileAtt> file : local.images.entrySet()) {
                    ZipEntry image = zip.getEntry(file.getKey());
                    if (image == null) {
                        continue; // 与网页版一致：缺少原件时保留附件元数据
                    }
                    Msg.FileAtt target = byFileId.get(file.getValue().id);
                    if (target.path != null) {
                        continue;
                    }
                    File out = File.createTempFile("archive-", ".img", attachments.rawDir());
                    stagedFiles.add(out);
                    target.path = out.getName();
                    byte[] header = new byte[12];
                    int headerSize = 0;
                    long size = 0;
                    if (image.getSize() > MAX_IMAGE) throw new Backup(INVALID_FILES);
                    try (InputStream in = zip.getInputStream(image);
                         FileOutputStream stream = new FileOutputStream(out)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            size += count;
                            if (size > MAX_IMAGE) throw new Backup(INVALID_FILES);
                            int copy = Math.min(count, header.length - headerSize);
                            System.arraycopy(buffer, 0, header, headerSize, copy);
                            headerSize += copy;
                            stream.write(buffer, 0, count);
                        }
                    }
                    target.mimeType = sniffMime(java.util.Arrays.copyOf(header, headerSize));
                    target.size = size;
                }
            }
            // 消息 JSON 也保存附件信息，同 ID 的每个引用都要回填原件路径。
            for (List<Msg> list : messages.values()) {
                for (Msg message : list) {
                    for (Msg.FileAtt file : message.files) {
                        Msg.FileAtt stored = byFileId.get(file.id);
                        if (stored != null && stored.path != null) {
                            file.path = stored.path;
                            file.mimeType = stored.mimeType;
                            file.size = stored.size;
                        }
                    }
                }
            }
            // 5. 单事务原子替换；空索引和空会话集合也是有效备份。
            db.replaceAll(sessions, messages, files);
            committed = true;

            Result result = new Result();
            result.sessions = sessions.size();
            for (List<Msg> list : messages.values()) {
                result.messages += list.size();
            }
            result.files = files.size();
            return result;
        } finally {
            if (!committed) {
                for (File staged : stagedFiles) {
                    staged.delete();
                }
            }
            temp.delete();
        }
    }

    private static void checkVersion(JSONObject manifest) throws Backup {
        Object version = manifest.opt("export_format_version");
        double value = version instanceof Number ? ((Number) version).doubleValue() : -1;
        if (value > VERSION) {
            throw new Backup("文件版本过新，请升级后再导入");
        }
        if (value != VERSION) {
            throw new Backup("文件版本无效，无法导入");
        }
    }

    private static String requiredString(JSONObject object, String key) throws Backup {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new Backup(INCOMPLETE + "（缺少文本字段 " + key + "）");
        return (String) value;
    }

    private static void requiredNullableString(JSONObject object, String key) throws Backup {
        if (!object.has(key) || (!object.isNull(key) && !(object.opt(key) instanceof String)))
            throw new Backup(INCOMPLETE + "（缺少字段 " + key + "）");
    }

    private static int requiredCount(JSONObject object, String key) throws Backup {
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw new Backup(INCOMPLETE + "（缺少数字字段 " + key + "）");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < 0
            || number > Integer.MAX_VALUE || number != Math.floor(number))
            throw new Backup(INCOMPLETE + "（字段 " + key + " 不是非负整数）");
        return (int) number;
    }

    private static void requiredTime(JSONObject object, String key) throws Backup {
        String value = requiredString(object, key);
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"))
            throw new Backup(INCOMPLETE + "（字段 " + key + " 不是 UTC 时间：" + value + "）");
        try {
            Util.parseIso(value);
        } catch (Exception error) {
            throw new Backup(INCOMPLETE + "（字段 " + key + " 时间越界：" + value + "）");
        }
    }

    private static boolean validSessionId(String id) {
        if (id == null || id.isEmpty() || ".".equals(id) || "..".equals(id)) {
            return false;
        }
        return !id.contains("/") && !id.contains("\\");
    }

    private static final class Local {
        Db.Session session;
        final List<Msg> messages = new ArrayList<>();
        /** ZIP 条目名 → 附件（用于把图片原件回填到本地文件） */
        final Map<String, Msg.FileAtt> images = new LinkedHashMap<>();
        final List<Msg.FileAtt> allFiles = new ArrayList<>();
    }

    private static Local toLocal(String sessionId, JSONObject file) throws Backup {
        // title 允许缺省（空标题按「新对话」显示）；current_message_id 允许缺省，后面退化处理
        if (file.has("title") && !file.isNull("title") && !(file.opt("title") instanceof String)) {
            throw new Backup(INCOMPLETE + "（title 类型不对）");
        }
        if (file.has("current_message_id") && !file.isNull("current_message_id")) {
            requiredCount(file, "current_message_id");
        }
        requiredTime(file, "inserted_at");
        requiredTime(file, "updated_at");
        Local local = new Local();
        Db.Session session = new Db.Session();
        session.id = sessionId;
        session.title = file.isNull("title") ? "" : file.optString("title", "");
        try {
            session.createdAt = Util.parseIso(file.optString("inserted_at", ""));
            session.updatedAt = Util.parseIso(file.optString("updated_at", ""));
        } catch (Exception error) {
            throw new Backup(INCOMPLETE + "（会话 " + sessionId + " 的时间格式无法解析）");
        }
        local.session = session;

        JSONArray array = file.optJSONArray("messages");
        if (array == null) {
            throw new Backup(INCOMPLETE + "（会话 " + sessionId + " 没有 messages 数组）");
        }
        // 按 id 升序：父消息的 id 必须更小
        TreeMap<Integer, JSONObject> sorted = new TreeMap<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                throw new Backup(INCOMPLETE + "（会话 " + sessionId + " 第 " + index + " 条消息不是对象）");
            }
            int id = requiredCount(item, "id");
            if (!item.has("parent_id")) throw new Backup(INCOMPLETE + "（消息 " + id + " 缺少 parent_id）");
            if (!item.isNull("parent_id")) requiredCount(item, "parent_id");
            String role = requiredString(item, "role");
            if (!"USER".equals(role) && !"ASSISTANT".equals(role)) throw new Backup(INCOMPLETE + "（消息 " + id + " 的 role 不认识：" + role + "）");
            // 官方导出里状态、开关、token 统计可能缺省，缺了按默认处理，不因此拒绝整份文件
            String status = item.has("status") && !item.isNull("status")
                ? requiredString(item, "status") : "FINISHED";
            if (!java.util.Arrays.asList("WIP", "FINISHED", "CONTENT_FILTER", "FAILED",
                "INCOMPLETE", "CONTEXT_LENGTH_EXCEEDED", "TIMEOUT").contains(status)) {
                status = "FINISHED";
                try {
                    item.put("status", status);
                } catch (Exception ignored) {
                    // 后面读到的仍是原值，按 FINISHED 处理即可
                }
            }
            if (item.has("thinking_enabled") && !item.isNull("thinking_enabled")
                && !(item.opt("thinking_enabled") instanceof Boolean)) {
                throw new Backup(INCOMPLETE + "（消息 " + id + " 的 thinking_enabled 不是布尔值）");
            }
            if (item.has("search_enabled") && !item.isNull("search_enabled")
                && !(item.opt("search_enabled") instanceof Boolean)) {
                throw new Backup(INCOMPLETE + "（消息 " + id + " 的 search_enabled 不是布尔值）");
            }
            if (item.has("accumulated_token_usage") && !item.isNull("accumulated_token_usage")) {
                requiredCount(item, "accumulated_token_usage");
            }
            requiredTime(item, "inserted_at");
            if (!(item.opt("fragments") instanceof JSONArray)) throw new Backup(INCOMPLETE + "（消息 " + id + " 没有 fragments 数组）");
            if (id < 0 || sorted.containsKey(id)) {
                throw new Backup("消息分支已损坏（消息 " + id + " 重复或 id 非法）");
            }
            sorted.put(id, item);
        }
        Map<Integer, Msg> byId = new HashMap<>();
        // 两遍处理：先建节点，再连边。官方导出个别会话的 parent_id 会大于自己的 id，
        // 一遍扫描会把这类会话误判成「父节点不存在」。
        for (Map.Entry<Integer, JSONObject> entry : sorted.entrySet()) {
            JSONObject item = entry.getValue();
            boolean user = "USER".equals(item.optString("role"));
            String localId = java.util.UUID.randomUUID().toString();
            Msg localMessage = user ? toUser(localId, item, local) : toAssistant(localId, item);
            localMessage.sessionId = sessionId;
            byId.put(entry.getKey(), localMessage);
            local.messages.add(localMessage);
        }
        Msg root = null;
        for (Map.Entry<Integer, JSONObject> entry : sorted.entrySet()) {
            JSONObject item = entry.getValue();
            Msg localMessage = byId.get(entry.getKey());
            boolean user = localMessage.isUser();
            Integer parentKey = item.isNull("parent_id") ? null : (Integer) item.optInt("parent_id");
            Msg parent = parentKey == null ? null : byId.get(parentKey);
            if (parentKey == null) {
                if (!user) {
                    throw new Backup("消息分支已损坏（消息 " + entry.getKey() + " 是助手回复但被当作根节点）");
                }
                if (root == null) {
                    root = localMessage;
                }
            } else if (parent == null) {
                throw new Backup("消息分支已损坏（消息 " + entry.getKey() + " 的父节点 "
                    + parentKey + " 不存在）");
            } else if (parent.isUser() == user) {
                throw new Backup("消息分支已损坏（消息 " + entry.getKey() + " 与父节点角色相同）");
            } else {
                localMessage.parentId = parent.id;
                parent.selectedChild = localMessage.id;
            }
        }
        int currentKey = file.has("current_message_id") && !file.isNull("current_message_id")
            ? file.optInt("current_message_id", -1) : -1;
        Msg current = byId.get(currentKey);
        if (current == null && !local.messages.isEmpty()) {
            // 个别导出不带 current_message_id：退化成「按 id 最后一条」收尾，不整份拒绝
            current = local.messages.get(local.messages.size() - 1);
        }
        if (current != null) {
            current.selectedChild = null;
            while (current.parentId != null) {
                Msg parent = null;
                for (Msg candidate : local.messages) {
                    if (candidate.id.equals(current.parentId)) {
                        parent = candidate;
                        break;
                    }
                }
                if (parent == null) {
                    throw new Backup("消息分支已损坏（当前消息的父节点丢失）");
                }
                parent.selectedChild = current.id;
                current = parent;
            }
            root = current;
        }
        session.rootMessageId = root == null ? null : root.id;

        // 图片路径校验：必须在本人会话目录下，同一条目不能对应不同附件
        Map<String, String> seen = new HashMap<>();
        for (Msg.FileAtt entry : local.allFiles) {
            if (!entry.isImage) {
                continue;
            }
            String path = entry.exportPath;
            if (path == null) {
                continue;
            }
            String prefix = SESSION_DIR + sessionId + "_files/";
            if (!path.startsWith(prefix)) {
                throw new Backup(INVALID_FILES);
            }
            String leaf = path.substring(prefix.length());
            if (leaf.isEmpty() || ".".equals(leaf) || "..".equals(leaf)
                || leaf.contains("/") || leaf.contains("\\") || leaf.indexOf('\0') >= 0) {
                throw new Backup(INVALID_FILES);
            }
            String existing = seen.get(path);
            if (existing != null && !existing.equals(entry.id)) {
                throw new Backup(INVALID_FILES);
            }
            seen.put(path, entry.id);
            local.images.put(path, entry);
        }
        return local;
    }

    private static Msg toUser(String id, JSONObject item, Local local) throws Backup {
        Msg message = new Msg();
        message.id = id;
        message.role = Msg.USER;
        try {
            message.createdAt = Util.parseIso(item.optString("inserted_at", ""));
        } catch (Exception error) {
            throw new Backup(INCOMPLETE + "（提问消息 " + id + " 的时间格式无法解析）");
        }
        StringBuilder content = new StringBuilder();
        JSONArray fragments = item.optJSONArray("fragments");
        for (int index = 0; fragments != null && index < fragments.length(); index++) {
            JSONObject fragment = fragments.optJSONObject(index);
            if (fragment == null) {
                continue;
            }
            String type = fragment.optString("type", "");
            if ("REQUEST".equals(type)) {
                content.append(fragment.optString("content", ""));
            } else if ("FILE".equals(type)) {
                JSONArray files = fragment.optJSONArray("files");
                for (int at = 0; files != null && at < files.length(); at++) {
                    JSONObject entry = files.optJSONObject(at);
                    if (entry == null) {
                        continue;
                    }
                    Msg.FileAtt file = new Msg.FileAtt();
                    file.id = entry.optString("id", "");
                    file.name = entry.optString("file_name", "");
                    file.size = entry.optLong("file_size", 0);
                    file.isImage = entry.optBoolean("is_image", false);
                    file.mimeType = "";
                    if (file.id.isEmpty()) {
                        continue;
                    }
                    if (!entry.isNull("export_path")) {
                        file.exportPath = entry.optString("export_path");
                    }
                    if (local != null) {
                        local.allFiles.add(file);
                    }
                    message.files.add(file);
                }
            }
        }
        message.content = content.toString();
        return message;
    }

    private static Msg toAssistant(String id, JSONObject item) throws Backup {
        Msg message = new Msg();
        message.id = id;
        message.role = Msg.ASSISTANT;
        try {
            message.createdAt = Util.parseIso(item.optString("inserted_at", ""));
        } catch (Exception error) {
            throw new Backup(INCOMPLETE + "（回复消息 " + id + " 的时间格式无法解析）");
        }
        String status = item.optString("status", "FINISHED");
        message.thinking = item.optBoolean("thinking_enabled", false);
        message.search = item.optBoolean("search_enabled", false);
        message.prefix = false;
        message.status = "FINISHED".equals(status) || "CONTENT_FILTER".equals(status)
            ? Msg.DONE
            : "FAILED".equals(status) || "CONTEXT_LENGTH_EXCEEDED".equals(status)
            || "TIMEOUT".equals(status) ? Msg.ERROR : Msg.STOPPED;
        boolean refused = "CONTENT_FILTER".equals(status);
        if (refused) {
            message.stopReason = "refusal";
        }
        JSONArray fragments = item.optJSONArray("fragments");
        message.blocks.addAll(toBlocks(fragments, id));
        if (refused) {
            List<JSONObject> kept = new ArrayList<>();
            for (JSONObject block : message.blocks) {
                String type = block.optString("type");
                if ("thinking".equals(type)) {
                    continue;
                }
                if ("text".equals(type) && !block.optBoolean("isTemplate", false)) {
                    continue;
                }
                kept.add(block);
            }
            message.blocks.clear();
            message.blocks.addAll(kept);
        }
        return message;
    }

    private static List<JSONObject> toBlocks(JSONArray fragments, String messageId) throws Backup {
        List<JSONObject> blocks = new ArrayList<>();
        boolean deepSearch = false;
        for (int index = 0; fragments != null && index < fragments.length(); index++) {
            JSONObject fragment = fragments.optJSONObject(index);
            String type = fragment == null ? "" : fragment.optString("type", "");
            if ("TOOL_OPEN".equals(type) || "TOOL_FIND".equals(type)) {
                deepSearch = true;
            }
        }
        String searchKind = deepSearch ? "deep_search" : "search";
        for (int index = 0; fragments != null && index < fragments.length(); index++) {
            JSONObject fragment = fragments.optJSONObject(index);
            if (fragment == null) {
                continue;
            }
            String type = fragment.optString("type", "");
            String toolId = "history-" + messageId + "-" + index;
            if ("RESPONSE".equals(type)) {
                blocks.add(Msg.textBlock(fragment.optString("content", "")));
            } else if ("TEMPLATE_RESPONSE".equals(type)) {
                JSONObject block = Msg.textBlock(fragment.optString("content", ""));
                try {
                    block.put("isTemplate", true);
                } catch (Exception ignored) {
                }
                blocks.add(block);
            } else if ("THINK".equals(type)) {
                JSONObject block = new JSONObject();
                try {
                    block.put("type", "thinking");
                    block.put("thinking", fragment.optString("content", ""));
                    if (!fragment.isNull("elapsed_secs")) {
                        block.put("elapsedSeconds", fragment.optDouble("elapsed_secs", 0));
                    }
                } catch (Exception ignored) {
                }
                blocks.add(block);
            } else if ("TOOL_SEARCH".equals(type)) {
                JSONArray queries = fragment.optJSONArray("queries");
                JSONObject input = new JSONObject();
                try {
                    input.put("action", "search");
                    input.put("query", queries != null && queries.length() > 0
                        ? queries.optString(0, "") : "");
                    input.put("queries", queries == null ? new JSONArray() : queries);
                } catch (Exception ignored) {
                }
                blocks.add(serverToolUse(toolId, "deepseek_web_search", "search".equals(searchKind)
                    ? "search" : searchKind, input));
                String status = fragment.optString("status", "FINISHED");
                if (!"WIP".equals(status)) {
                    JSONObject result = new JSONObject();
                    JSONArray content = new JSONArray();
                    try {
                        result.put("type", "web_search_tool_result");
                        result.put("tool_use_id", toolId);
                        result.put("kind", searchKind);
                        if ("FAILED".equals(status)) {
                            JSONObject error = new JSONObject();
                            error.put("type", "web_search_tool_result_error");
                            error.put("error_code", "unavailable");
                            content.put(error);
                        } else {
                            JSONArray results = fragment.optJSONArray("results");
                            for (int at = 0; results != null && at < results.length(); at++) {
                                JSONObject item = results.optJSONObject(at);
                                if (item == null || !Util.isWebUrl(item.optString("url", ""))) {
                                    continue;
                                }
                                JSONObject entry = new JSONObject();
                                entry.put("type", "web_search_result");
                                entry.put("url", item.optString("url", ""));
                                entry.put("title", item.optString("title", ""));
                                entry.put("snippet", item.optString("snippet", ""));
                                Object icon = item.opt("site_icon");
                                entry.put("site_icon", icon instanceof String
                                    && Util.isWebUrl((String) icon) ? icon : JSONObject.NULL);
                                entry.put("site_name", item.isNull("site_name")
                                    ? JSONObject.NULL : item.opt("site_name"));
                                entry.put("page_age", item.isNull("published_at")
                                    ? JSONObject.NULL : item.opt("published_at"));
                                entry.put("encrypted_content", "");
                                content.put(entry);
                            }
                        }
                        result.put("content", content);
                    } catch (Exception ignored) {
                    }
                    blocks.add(result);
                }
            } else if ("TOOL_OPEN".equals(type)) {
                String url = fragment.optString("url", "");
                if (!Util.isWebUrl(url)) {
                    continue;
                }
                JSONObject input = new JSONObject();
                try {
                    input.put("action", "open_page");
                    input.put("url", url);
                } catch (Exception ignored) {
                }
                blocks.add(serverToolUse(toolId, "deepseek_web_search", "deep_search", input));
                String status = fragment.optString("status", "FINISHED");
                if (!"WIP".equals(status)) {
                    blocks.add(fetchResult(toolId, status, url,
                        fragment.isNull("title") ? null : fragment.optString("title", null),
                        fragment.isNull("published_at") ? null : fragment.optString("published_at")));
                }
            } else if ("TOOL_FIND".equals(type)) {
                JSONObject input = new JSONObject();
                try {
                    input.put("action", "find_in_page");
                    input.put("url", "");
                    input.put("pattern", fragment.optString("pattern", ""));
                } catch (Exception ignored) {
                }
                blocks.add(serverToolUse(toolId, "deepseek_web_search", "deep_search", input));
                String status = fragment.optString("status", "FINISHED");
                if (!"WIP".equals(status)) {
                    blocks.add(fetchResult(toolId, status, "", null, null));
                }
            } else if ("READ_LINK".equals(type)) {
                JSONArray results = fragment.optJSONArray("results");
                for (int at = 0; results != null && at < results.length(); at++) {
                    JSONObject item = results.optJSONObject(at);
                    if (item == null) {
                        continue;
                    }
                    String url = item.optString("url", "");
                    String signature = item.optString("signature", "");
                    if (!Util.isWebUrl(url) && signature.trim().isEmpty()) {
                        continue;
                    }
                    String id = toolId + "-" + at;
                    JSONObject input = new JSONObject();
                    try {
                        input.put("action", "read_link");
                        input.put("url", Util.isWebUrl(url) ? url : "");
                    } catch (Exception ignored) {
                    }
                    blocks.add(serverToolUse(id, "deepseek_read_url", "read_link", input));
                    JSONObject result = new JSONObject();
                    JSONObject content = new JSONObject();
                    try {
                        JSONObject source = new JSONObject();
                        source.put("type", "text");
                        source.put("media_type", "text/plain");
                        source.put("data", "");
                        content.put("type", "document");
                        content.put("source", source);
                        content.put("title", item.isNull("title") ? JSONObject.NULL
                            : item.opt("title"));
                        result.put("type", "web_fetch_tool_result");
                        result.put("tool_use_id", id);
                        result.put("kind", "read_link");
                        JSONObject fetched = new JSONObject();
                        fetched.put("type", "web_fetch_result");
                        fetched.put("url", Util.isWebUrl(url) ? url : "");
                        fetched.put("content", content);
                        fetched.put("published_at", JSONObject.NULL);
                        result.put("content", fetched);
                        result.put("signature", signature);
                    } catch (Exception ignored) {
                    }
                    blocks.add(result);
                }
            }
            // TIP / CONTENT_RISK / PARTIAL_FILE_READ / TOOL_IMAGE_SEARCH / FILE 没有对应展示，丢弃
        }
        return blocks;
    }

    private static JSONObject serverToolUse(String id, String name, String kind, JSONObject input) {
        JSONObject block = new JSONObject();
        try {
            block.put("type", "server_tool_use");
            block.put("id", id);
            block.put("name", name);
            block.put("input", input);
            block.put("kind", kind);
            JSONObject caller = new JSONObject();
            caller.put("type", "direct");
            block.put("caller", caller);
        } catch (Exception ignored) {
        }
        return block;
    }

    private static JSONObject fetchResult(String toolUseId, String status, String url, String title,
                                          String publishedAt) {
        JSONObject block = new JSONObject();
        try {
            block.put("type", "web_fetch_tool_result");
            block.put("tool_use_id", toolUseId);
            block.put("kind", "deep_search");
            if ("FINISHED".equals(status)) {
                JSONObject source = new JSONObject();
                source.put("type", "text");
                source.put("media_type", "text/plain");
                source.put("data", "");
                JSONObject content = new JSONObject();
                content.put("type", "document");
                content.put("source", source);
                content.put("title", title == null ? JSONObject.NULL : title);
                JSONObject fetched = new JSONObject();
                fetched.put("type", "web_fetch_result");
                fetched.put("url", url == null ? "" : url);
                fetched.put("content", content);
                fetched.put("published_at", publishedAt == null ? JSONObject.NULL : publishedAt);
                block.put("content", fetched);
            } else {
                JSONObject content = new JSONObject();
                content.put("type", "web_fetch_tool_result_error");
                content.put("error_code", "unavailable");
                block.put("content", content);
            }
        } catch (Exception ignored) {
        }
        return block;
    }

    // ================================================================ 工具

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_JSON) throw new IOException("JSON 条目过大");
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            long total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_JSON) throw new IOException("JSON 条目过大");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static File copyToCache(Context context, Uri source) throws IOException {
        File temp = File.createTempFile("import", ".zip", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
        }
        return temp;
    }

    /** 按文件头猜 MIME：导入的图片只有字节，靠它补回具体类型。 */
    private static String sniffMime(byte[] data) {
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
            && data[3] == 'G') {
            return "image/png";
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "image/gif";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') {
            return "image/bmp";
        }
        return "image/*";
    }
}
