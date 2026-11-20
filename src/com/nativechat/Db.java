package com.nativechat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地存储（SQLite）：会话、消息树、附件。
 *
 * 消息内容是整块 JSON（见 Msg.toJson），因为回复里要原样保留服务端下发的
 * 内容块和签名；拆成列反而会丢字段。只有参与查询/排序的字段单独成列。
 */
public final class Db extends SQLiteOpenHelper {
    private static final String NAME = "nativechat.db";
    private static final int VERSION = 3;

    public Db(Context context) {
        super(context, NAME, null, VERSION);
    }

    /**
     * 升级前给旧库留一份整份备份（只在第一次升级时做一次）。
     * 迁移是覆盖安装里唯一可能动到旧数据的动作，备份放在 databases 目录下，
     * 命名为 nativechat.db.pre-v3.bak，出问题时可以用它手工恢复。
     */
    public static void backupBeforeUpgrade(Context context) {
        try {
            java.io.File db = context.getDatabasePath(NAME);
            if (db == null || !db.exists() || db.length() == 0) {
                return;
            }
            java.io.File backup = new java.io.File(db.getParentFile(),
                NAME + ".pre-v3.bak");
            if (backup.exists()) {
                return;
            }
            byte[] buffer = new byte[256 * 1024];
            try (java.io.InputStream in = new java.io.FileInputStream(db);
                 java.io.OutputStream out = new java.io.FileOutputStream(backup)) {
                int count;
                while ((count = in.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                }
                out.flush();
            }
        } catch (Exception ignored) {
            // 备份失败不阻断启动，迁移本身仍是单事务
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createV3(db);
    }

    private static void createV3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY,"
            + " title TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,"
            + " updated_at INTEGER NOT NULL, root_message_id TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY,"
            + " session_id TEXT NOT NULL, parent_id TEXT, role TEXT NOT NULL,"
            + " created_at INTEGER NOT NULL, selected_child TEXT, data TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session"
            + " ON messages (session_id, created_at)");
        db.execSQL("CREATE TABLE IF NOT EXISTS files (id TEXT PRIMARY KEY, name TEXT,"
            + " mime TEXT, size INTEGER, is_image INTEGER, path TEXT, server_id TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            migrateV2(db, oldVersion);
        }
    }

    /**
     * v2（正文一列纯文本 + parent_id/active）迁移到 v3：
     * - 正文转成 text 内容块，状态按「已完成」处理，并允许续写；
     * - active=1 的回答成为父节点选中的分支，其余保留为可切换的兄弟版本；
     * - 消息树根取最早一条消息。
     */
    private void migrateV2(SQLiteDatabase db, int oldVersion) {
        List<Object[]> sessions = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
            "SELECT id, title, updated_at FROM sessions ORDER BY updated_at DESC", null)) {
            while (cursor.moveToNext()) {
                sessions.add(new Object[]{cursor.getString(0), cursor.getString(1),
                    cursor.getLong(2)});
            }
        }

        db.beginTransaction();
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS sessions_v3 (id TEXT PRIMARY KEY,"
                + " title TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,"
                + " updated_at INTEGER NOT NULL, root_message_id TEXT)");
            db.execSQL("CREATE TABLE IF NOT EXISTS messages_v3 (id TEXT PRIMARY KEY,"
                + " session_id TEXT NOT NULL, parent_id TEXT, role TEXT NOT NULL,"
                + " created_at INTEGER NOT NULL, selected_child TEXT, data TEXT NOT NULL)");

            for (Object[] row : sessions) {
                String sessionId = (String) row[0];
                String title = (String) row[1];
                long updatedAt = (Long) row[2];
                List<String[]> messages = new ArrayList<>();
                String legacyColumns = oldVersion < 2 ? "NULL, 1" : "parent_id, active";
                try (Cursor cursor = db.rawQuery("SELECT id, role, content, created_at, "
                    + legacyColumns + " FROM messages WHERE session_id = ? ORDER BY created_at ASC, rowid ASC",
                    new String[]{sessionId})) {
                    while (cursor.moveToNext()) {
                        messages.add(new String[]{cursor.getString(0), cursor.getString(1),
                            cursor.getString(2) == null ? "" : cursor.getString(2),
                            String.valueOf(cursor.getLong(3)), cursor.getString(4),
                            String.valueOf(cursor.getInt(5))});
                    }
                }
                // v1 和从 v1 升级来的旧记录没有 parent_id；它们原本是一条时间线。
                // 保留 v2 中明确记录的分支，仅补齐无父节点的历史消息。
                String previousActive = null;
                for (int i = 0; i < messages.size(); i++) {
                    String[] message = messages.get(i);
                    if (i > 0 && message[4] == null) {
                        message[4] = previousActive;
                    }
                    if ("1".equals(message[5])) {
                        previousActive = message[0];
                    }
                }
                long createdAt = messages.isEmpty() ? updatedAt
                    : Long.parseLong(messages.get(0)[3]);

                Map<String, List<String[]>> children = new LinkedHashMap<>();
                for (String[] message : messages) {
                    String key = message[4] == null ? "" : message[4];
                    List<String[]> list = children.get(key);
                    if (list == null) {
                        list = new ArrayList<>();
                        children.put(key, list);
                    }
                    list.add(message);
                }
                Map<String, String> selected = new HashMap<>();
                for (Map.Entry<String, List<String[]>> entry : children.entrySet()) {
                    String chosen = null;
                    for (String[] child : entry.getValue()) {
                        if ("1".equals(child[5])) {
                            chosen = child[0];
                        }
                    }
                    if (chosen == null && !entry.getValue().isEmpty()) {
                        chosen = entry.getValue().get(entry.getValue().size() - 1)[0];
                    }
                    selected.put(entry.getKey(), chosen);
                }

                ContentValues sessionValues = new ContentValues();
                sessionValues.put("id", sessionId);
                sessionValues.put("title", title == null ? "" : title);
                sessionValues.put("created_at", createdAt);
                sessionValues.put("updated_at", updatedAt);
                sessionValues.put("root_message_id",
                    messages.isEmpty() ? null : messages.get(0)[0]);
                db.insertOrThrow("sessions_v3", null, sessionValues);

                for (String[] message : messages) {
                    Msg local = new Msg();
                    local.id = message[0];
                    local.sessionId = sessionId;
                    local.role = message[1];
                    local.createdAt = Long.parseLong(message[3]);
                    local.parentId = message[4];
                    local.selectedChild = selected.get(message[0]);
                    if (local.isUser()) {
                        local.content = message[2];
                    } else {
                        if (!message[2].isEmpty()) {
                            local.blocks.add(Msg.textBlock(message[2]));
                        }
                        local.status = Msg.DONE;
                        local.prefix = true;
                    }
                    ContentValues values = new ContentValues();
                    values.put("id", local.id);
                    values.put("session_id", sessionId);
                    values.put("parent_id", local.parentId);
                    values.put("role", local.role);
                    values.put("created_at", local.createdAt);
                    values.put("selected_child", local.selectedChild);
                    values.put("data", local.toJson().toString());
                    db.insertOrThrow("messages_v3", null, values);
                }
            }

            // 包括孤儿消息在内，任何未复制的记录都会中止升级并保留旧表。
            try (Cursor counts = db.rawQuery("SELECT (SELECT COUNT(*) FROM sessions),"
                + " (SELECT COUNT(*) FROM sessions_v3), (SELECT COUNT(*) FROM messages),"
                + " (SELECT COUNT(*) FROM messages_v3)", null)) {
                if (!counts.moveToFirst() || counts.getLong(0) != counts.getLong(1)
                    || counts.getLong(2) != counts.getLong(3)) {
                    throw new IllegalStateException("Legacy database migration lost rows");
                }
            }
            db.execSQL("DROP TABLE messages");
            db.execSQL("DROP TABLE IF EXISTS sessions");
            db.execSQL("ALTER TABLE sessions_v3 RENAME TO sessions");
            db.execSQL("ALTER TABLE messages_v3 RENAME TO messages");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_session"
                + " ON messages (session_id, created_at)");
            createV3(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ================================================================ 会话

    public static final class Session {
        public String id = "";
        public String title = "";
        public long createdAt;
        public long updatedAt;
        public String rootMessageId;
    }

    public List<Session> sessions() {
        List<Session> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("sessions", null, null, null, null, null,
            "updated_at DESC, created_at DESC, rowid DESC")) {
            while (cursor.moveToNext()) {
                list.add(readSession(cursor));
            }
        }
        return list;
    }

    /** 搜索命中的一条结果：标题命中或正文命中。 */
    public static final class Hit {
        public String sessionId;
        public String title;
        public String snippet;
        public long createdAt;
        public boolean titleMatch;
    }

    /**
     * 全库搜索：先按标题匹配，再按正文匹配。
     * 正文只取命中行的 data 并在内存里抽出片段，条数有上限，不会把整库读进来。
     */
    public List<Hit> search(String keyword, int limit) {
        List<Hit> hits = new ArrayList<>();
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) {
            return hits;
        }
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        java.util.Set<String> seen = new java.util.HashSet<>();

        try (Cursor cursor = getReadableDatabase().query("sessions",
            new String[]{"id", "title", "updated_at"},
            "title LIKE ? ESCAPE '\\'", new String[]{like}, null, null,
            "updated_at DESC", String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                Hit hit = new Hit();
                hit.sessionId = cursor.getString(0);
                hit.title = cursor.getString(1);
                hit.createdAt = cursor.getLong(2);
                hit.titleMatch = true;
                hit.snippet = "";
                hits.add(hit);
                seen.add(hit.sessionId);
            }
        }

        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT m.session_id, m.data, m.created_at, s.title FROM messages m "
                + "LEFT JOIN sessions s ON s.id = m.session_id "
                + "WHERE m.data LIKE ? ESCAPE '\\' ORDER BY m.created_at DESC LIMIT ?",
            new String[]{like, String.valueOf(limit)})) {
            while (cursor.moveToNext()) {
                String sessionId = cursor.getString(0);
                if (sessionId == null) {
                    continue;
                }
                String text = textOf(cursor.getString(1));
                int at = text.indexOf(q);
                if (at < 0) {
                    continue;
                }
                Hit hit = new Hit();
                hit.sessionId = sessionId;
                hit.title = cursor.getString(3);
                hit.createdAt = cursor.getLong(2);
                hit.snippet = snippet(text, at, q.length());
                hits.add(hit);
                if (hits.size() >= limit * 2) {
                    break;
                }
            }
        }
        return hits;
    }

    /** 只解析出可搜索的正文，避免整条消息对象常驻。 */
    private static String textOf(String data) {
        if (data == null) {
            return "";
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject(data);
            String content = json.optString("content", "");
            if (!content.isEmpty()) {
                return content;
            }
            org.json.JSONArray blocks = json.optJSONArray("blocks");
            StringBuilder text = new StringBuilder();
            for (int index = 0; blocks != null && index < blocks.length(); index++) {
                org.json.JSONObject block = blocks.optJSONObject(index);
                if (block != null && "text".equals(block.optString("type"))) {
                    text.append(block.optString("text", ""));
                }
            }
            return text.toString();
        } catch (Exception error) {
            return "";
        }
    }

    private static String snippet(String text, int at, int length) {
        int from = Math.max(0, at - 40);
        int to = Math.min(text.length(), at + length + 60);
        String cut = text.substring(from, to).replace('\n', ' ').replace('\r', ' ');
        return (from > 0 ? "…" : "") + cut + (to < text.length() ? "…" : "");
    }

    private static Session readSession(Cursor cursor) {
        Session session = new Session();
        session.id = cursor.getString(cursor.getColumnIndexOrThrow("id"));
        session.title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
        session.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
        session.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
        session.rootMessageId = cursor.getString(cursor.getColumnIndexOrThrow("root_message_id"));
        return session;
    }

    public Session session(String id) {
        if (id == null) {
            return null;
        }
        try (Cursor cursor = getReadableDatabase().query("sessions", null, "id = ?",
            new String[]{id}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return readSession(cursor);
            }
        }
        return null;
    }

    public void saveSession(Session session) {
        ContentValues values = new ContentValues();
        values.put("id", session.id);
        values.put("title", session.title);
        values.put("created_at", session.createdAt);
        values.put("updated_at", session.updatedAt);
        values.put("root_message_id", session.rootMessageId);
        if (getWritableDatabase().insertWithOnConflict("sessions", null, values,
            SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new android.database.SQLException("Failed to save session");
        }
    }

    public void deleteSession(String id) {
        SQLiteDatabase database = getWritableDatabase();
        database.delete("messages", "session_id = ?", new String[]{id});
        database.delete("sessions", "id = ?", new String[]{id});
    }

    public void renameSession(String id, String title) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("sessions", values, "id = ?", new String[]{id});
    }

    public void touchSession(String id) {
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("sessions", values, "id = ?", new String[]{id});
    }

    public void setSessionRoot(String id, String rootMessageId) {
        ContentValues values = new ContentValues();
        values.put("root_message_id", rootMessageId);
        getWritableDatabase().update("sessions", values, "id = ?", new String[]{id});
    }

    // ================================================================ 消息

    public Msg message(String id) {
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"data", "session_id"},
            "id = ?", new String[]{id}, null, null, null)) {
            if (cursor.moveToFirst()) {
                Msg message = Msg.fromJson(cursor.getString(0));
                message.sessionId = cursor.getString(1);
                return message;
            }
        }
        return null;
    }

    /** 会话里的全部节点（含未选中的兄弟版本），按创建时间升序。 */
    public List<Msg> messages(String sessionId) {
        List<Msg> list = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"data"},
            "session_id = ?", new String[]{sessionId}, null, null, "created_at ASC")) {
            while (cursor.moveToNext()) {
                Msg message = Msg.fromJson(cursor.getString(0));
                message.sessionId = sessionId;
                list.add(message);
            }
        }
        return list;
    }

    /** 当前选中的分支路径：从根出发，沿每条消息的 selected_child 走到底。 */
    public List<Msg> visibleMessages(String sessionId) {
        long since = Diag.now();
        Session session = session(sessionId);
        List<Msg> all = messages(sessionId);
        List<Msg> path = visiblePath(session, all);
        Diag.log("DB", "会话 " + ChatActivity.shortId(sessionId) + " 节点 " + all.size()
            + " · 可见 " + path.size() + " · " + Diag.millis(since) + "ms");
        return path;
    }

    private List<Msg> visiblePath(Session session, List<Msg> all) {
        if (session == null || session.rootMessageId == null) {
            return all;
        }
        Map<String, Msg> byId = new LinkedHashMap<>();
        for (Msg message : all) {
            byId.put(message.id, message);
        }
        List<Msg> path = new ArrayList<>();
        String id = session.rootMessageId;
        while (id != null) {
            Msg message = byId.remove(id);
            if (message == null) {
                break;
            }
            path.add(message);
            id = message.selectedChild;
        }
        return path;
    }

    public void saveMessage(Msg message) {
        ContentValues values = new ContentValues();
        values.put("id", message.id);
        values.put("session_id", message.sessionId);
        values.put("parent_id", message.parentId);
        values.put("role", message.role);
        values.put("created_at", message.createdAt);
        values.put("selected_child", message.selectedChild);
        values.put("data", message.toJson().toString());
        if (getWritableDatabase().insertWithOnConflict("messages", null, values,
            SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new android.database.SQLException("Failed to save message");
        }
    }

    public void saveMessages(List<Msg> messages) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            SQLiteStatement statement = database.compileStatement(
                "INSERT OR REPLACE INTO messages (id, session_id, parent_id, role, created_at,"
                    + " selected_child, data) VALUES (?, ?, ?, ?, ?, ?, ?)");
            try {
                for (Msg message : messages) {
                    bind(statement, message);
                    statement.executeInsert();
                }
            } finally {
                statement.close();
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static void bind(SQLiteStatement statement, Msg message) {
        statement.clearBindings();
        statement.bindString(1, message.id);
        statement.bindString(2, message.sessionId);
        if (message.parentId == null) {
            statement.bindNull(3);
        } else {
            statement.bindString(3, message.parentId);
        }
        statement.bindString(4, message.role);
        statement.bindLong(5, message.createdAt);
        if (message.selectedChild == null) {
            statement.bindNull(6);
        } else {
            statement.bindString(6, message.selectedChild);
        }
        statement.bindString(7, message.toJson().toString());
    }

    /** 将新分支、父节点选择和会话根/时间作为一个整体提交。 */
    public void appendBranch(String sessionId, String parentId, List<Msg> messages) {
        if (messages.isEmpty()) return;
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            if (parentId != null) {
                Msg parent = message(parentId);
                if (parent == null || !sessionId.equals(parent.sessionId)) {
                    throw new IllegalStateException("Missing branch parent: " + parentId);
                }
                parent.selectedChild = messages.get(0).id;
                saveMessage(parent);
            }
            SQLiteStatement statement = database.compileStatement(
                "INSERT INTO messages (id, session_id, parent_id, role, created_at,"
                    + " selected_child, data) VALUES (?, ?, ?, ?, ?, ?, ?)");
            try {
                for (int i = 0; i < messages.size(); i++) {
                    Msg message = messages.get(i);
                    message.sessionId = sessionId;
                    message.parentId = i == 0 ? parentId : messages.get(i - 1).id;
                    message.selectedChild = i + 1 == messages.size() ? null : messages.get(i + 1).id;
                    bind(statement, message);
                    statement.executeInsert();
                }
            } finally {
                statement.close();
            }
            ContentValues values = new ContentValues();
            if (parentId == null) values.put("root_message_id", messages.get(0).id);
            values.put("updated_at", System.currentTimeMillis());
            if (database.update("sessions", values, "id = ?", new String[]{sessionId}) != 1) {
                throw new IllegalStateException("Missing session: " + sessionId);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /** 同一提问下的所有回答（含未选中的版本），按创建时间升序。 */
    public List<Msg> repliesTo(String sessionId, String parentId) {
        List<Msg> list = new ArrayList<>();
        String where = parentId == null
            ? "session_id = ? AND parent_id IS NULL AND role = ?"
            : "session_id = ? AND parent_id = ? AND role = ?";
        String[] args = parentId == null
            ? new String[]{sessionId, Msg.ASSISTANT}
            : new String[]{sessionId, parentId, Msg.ASSISTANT};
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"data"},
            where, args, null, null, "created_at ASC")) {
            while (cursor.moveToNext()) {
                Msg message = Msg.fromJson(cursor.getString(0));
                message.sessionId = sessionId;
                list.add(message);
            }
        }
        return list;
    }

    public void selectChild(String parentId, String childId) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            try (Cursor cursor = database.query("messages", new String[]{"data"}, "id = ?",
                new String[]{parentId}, null, null, null)) {
                if (cursor.moveToFirst()) {
                    JSONObject json;
                    try {
                        json = new JSONObject(cursor.getString(0));
                        if (childId == null) {
                            json.remove("selectedChild");
                        } else {
                            json.put("selectedChild", childId);
                        }
                    } catch (JSONException error) {
                        throw new IllegalStateException("Invalid message JSON: " + parentId, error);
                    }
                    ContentValues values = new ContentValues();
                    values.put("selected_child", childId);
                    values.put("data", json.toString());
                    if (database.update("messages", values, "id = ?", new String[]{parentId}) != 1) {
                        throw new IllegalStateException("Failed to select child for " + parentId);
                    }
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /** 会话最后一条消息的正文，用于列表预览。 */
    public String lastMessagePreview(String sessionId) {
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"data"},
            "session_id = ?", new String[]{sessionId}, null, null, "created_at DESC", "1")) {
            if (cursor.moveToFirst()) {
                Msg message = Msg.fromJson(cursor.getString(0));
                String text = message.isUser() ? message.content : message.replyText();
                return text == null ? "" : text.replace('\n', ' ').trim();
            }
        }
        return "";
    }

    // ================================================================ 附件

    public void saveFile(Msg.FileAtt file) {
        ContentValues values = new ContentValues();
        values.put("id", file.id);
        values.put("name", file.name);
        values.put("mime", file.mimeType);
        values.put("size", file.size);
        values.put("is_image", file.isImage ? 1 : 0);
        values.put("path", file.path);
        values.put("server_id", file.serverId);
        getWritableDatabase().insertWithOnConflict("files", null, values,
            SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Msg.FileAtt file(String id) {
        try (Cursor cursor = getReadableDatabase().query("files", null, "id = ?",
            new String[]{id}, null, null, null)) {
            if (cursor.moveToFirst()) {
                Msg.FileAtt file = new Msg.FileAtt();
                file.id = cursor.getString(cursor.getColumnIndexOrThrow("id"));
                file.name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                file.mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime"));
                file.size = cursor.getLong(cursor.getColumnIndexOrThrow("size"));
                file.isImage = cursor.getInt(cursor.getColumnIndexOrThrow("is_image")) == 1;
                file.path = cursor.getString(cursor.getColumnIndexOrThrow("path"));
                file.serverId = cursor.getString(cursor.getColumnIndexOrThrow("server_id"));
                return file;
            }
        }
        return null;
    }

    public void setFileServerId(String fileId, String serverId) {
        ContentValues values = new ContentValues();
        values.put("server_id", serverId);
        getWritableDatabase().update("files", values, "id = ?", new String[]{fileId});
    }

    // ================================================================ 导入导出

    /**
     * 原始数据包导入：把网页版本地记录**合并**进本库，单事务提交，不动其他会话。
     * 与本机已有记录同 id 时覆盖，因此重复导入同一份数据是幂等的。
     */
    public void mergeSessions(List<Session> sessions, Map<String, List<Msg>> messages,
                              List<Msg.FileAtt> files) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            SQLiteStatement messageStatement = database.compileStatement(
                "INSERT OR REPLACE INTO messages (id, session_id, parent_id, role, created_at,"
                    + " selected_child, data) VALUES (?, ?, ?, ?, ?, ?, ?)");
            SQLiteStatement fileStatement = database.compileStatement(
                "INSERT OR REPLACE INTO files (id, name, mime, size, is_image, path, server_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)");
            try {
                for (Session session : sessions) {
                    ContentValues values = new ContentValues();
                    values.put("id", session.id);
                    values.put("title", session.title);
                    values.put("created_at", session.createdAt);
                    values.put("updated_at", session.updatedAt);
                    values.put("root_message_id", session.rootMessageId);
                    if (database.insertWithOnConflict("sessions", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE) == -1) {
                        throw new android.database.SQLException(
                            "Failed to import session " + session.id);
                    }
                    List<Msg> list = messages.get(session.id);
                    if (list == null) {
                        continue;
                    }
                    for (Msg message : list) {
                        bind(messageStatement, message);
                        messageStatement.executeInsert();
                    }
                }
                for (Msg.FileAtt file : files) {
                    fileStatement.clearBindings();
                    fileStatement.bindString(1, file.id);
                    fileStatement.bindString(2, file.name);
                    fileStatement.bindString(3, file.mimeType == null ? "" : file.mimeType);
                    fileStatement.bindLong(4, file.size);
                    fileStatement.bindLong(5, file.isImage ? 1 : 0);
                    if (file.path == null) {
                        fileStatement.bindNull(6);
                    } else {
                        fileStatement.bindString(6, file.path);
                    }
                    if (file.serverId == null) {
                        fileStatement.bindNull(7);
                    } else {
                        fileStatement.bindString(7, file.serverId);
                    }
                    fileStatement.executeInsert();
                }
            } finally {
                messageStatement.close();
                fileStatement.close();
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /**
     * 原子替换全部会话与附件：先清空再写入，同一个事务，失败就整体回滚。
     * 导入路径必须走这里，不能边解析边写。
     */
    public void replaceAll(List<Session> sessions, Map<String, List<Msg>> messages,
                           List<Msg.FileAtt> files) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("messages", null, null);
            database.delete("sessions", null, null);
            database.delete("files", null, null);
            SQLiteStatement messageStatement = database.compileStatement(
                "INSERT INTO messages (id, session_id, parent_id, role, created_at,"
                    + " selected_child, data) VALUES (?, ?, ?, ?, ?, ?, ?)");
            SQLiteStatement fileStatement = database.compileStatement(
                "INSERT INTO files (id, name, mime, size, is_image, path, server_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)");
            try {
                for (Session session : sessions) {
                    ContentValues values = new ContentValues();
                    values.put("id", session.id);
                    values.put("title", session.title);
                    values.put("created_at", session.createdAt);
                    values.put("updated_at", session.updatedAt);
                    values.put("root_message_id", session.rootMessageId);
                    database.insertOrThrow("sessions", null, values);
                    List<Msg> list = messages.get(session.id);
                    if (list == null) {
                        continue;
                    }
                    for (Msg message : list) {
                        bind(messageStatement, message);
                        messageStatement.executeInsert();
                    }
                }
                for (Msg.FileAtt file : files) {
                    fileStatement.clearBindings();
                    fileStatement.bindString(1, file.id);
                    fileStatement.bindString(2, file.name);
                    fileStatement.bindString(3, file.mimeType);
                    fileStatement.bindLong(4, file.size);
                    fileStatement.bindLong(5, file.isImage ? 1 : 0);
                    if (file.path == null) {
                        fileStatement.bindNull(6);
                    } else {
                        fileStatement.bindString(6, file.path);
                    }
                    if (file.serverId == null) {
                        fileStatement.bindNull(7);
                    } else {
                        fileStatement.bindString(7, file.serverId);
                    }
                    fileStatement.executeInsert();
                }
            } finally {
                messageStatement.close();
                fileStatement.close();
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /** 会话的整棵消息树，导出时用。 */
    public JSONArray sessionTree(String sessionId) {
        JSONArray array = new JSONArray();
        for (Msg message : messages(sessionId)) {
            array.put(message.toJson());
        }
        return array;
    }
}
