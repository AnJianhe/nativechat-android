package com.nativechat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek 的 Anthropic 兼容接口（不是 OpenAI 的 /chat/completions）：
 * - 发消息：POST {origin}/anthropic/v1/messages，头 x-api-key、Accept: text/event-stream
 * - 附件：POST/DELETE {origin}/anthropic/v1/files，额外头 anthropic-beta: files-api-2025-04-14
 * - 校验 Key：GET {origin}/models（Bearer，不产生费用）
 *
 * 错误五分类与网页版 `api/client.ts` 的 ApiError.kind 一致：
 * http（非 2xx）/ stream（流内错误帧）/ network（请求没到服务端）/
 * interrupted（message_stop 之前断流）/ protocol（响应不是 SSE）。
 */
public final class Api {
    public static final String HTTP = "http";
    public static final String STREAM = "stream";
    public static final String NETWORK = "network";
    public static final String INTERRUPTED = "interrupted";
    public static final String PROTOCOL = "protocol";
    /** 附件补齐阶段失败（请求还没发出），网页版记为 upload */
    public static final String UPLOAD = "upload";

    private static final String BETA_FILES = "files-api-2025-04-14";
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 180000;

    private Api() {
    }

    public static final class ApiError extends Exception {
        public final String kind;
        /** 没有状态码时为 -1 */
        public final int status;

        public ApiError(String kind, String message, int status) {
            super(message == null ? "" : message);
            this.kind = kind;
            this.status = status;
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("kind", kind);
                json.put("message", getMessage());
                if (status > 0) {
                    json.put("status", status);
                }
            } catch (Exception ignored) {
            }
            return json;
        }
    }

    /** 取消不等待底层网络实现；断开连接可能需要更长时间。 */
    public static final class Call {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private HttpURLConnection connection;

        public void cancel() {
            cancelled.set(true);
            final HttpURLConnection current;
            synchronized (this) {
                current = connection;
                connection = null;
            }
            if (current != null) {
                Thread closer = new Thread(() -> disconnect(current), "nativechat-cancel");
                closer.setDaemon(true);
                closer.start();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        synchronized boolean attach(HttpURLConnection value) {
            if (cancelled.get()) return false;
            connection = value;
            return true;
        }

        void release(HttpURLConnection value) {
            synchronized (this) {
                if (connection != value) return;
                connection = null;
            }
            disconnect(value);
        }
    }

    private static void disconnect(HttpURLConnection connection) {
        try {
            connection.disconnect();
        } catch (Exception ignored) {
        }
    }

    private static ApiError cancelledError() {
        return new ApiError(INTERRUPTED, "Cancelled", -1);
    }

    public interface EventHandler {
        void onEvent(JSONObject event);
    }

    // ------------------------------------------------------------------ 发消息

    /** 逐条产出 SSE 事件，收到 message_stop 返回；其余情况按五分类抛出。 */
    public static void streamMessages(String origin, String apiKey, JSONObject body, boolean withFiles,
                                      Call call, EventHandler handler) throws ApiError {
        if (call.isCancelled()) throw cancelledError();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                streamMessagesBlocking(origin, apiKey, body, withFiles, call, handler);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        }, "nativechat-stream");
        worker.setDaemon(true);
        worker.start();
        try {
            while (!done.await(50, TimeUnit.MILLISECONDS)) {
                if (call.isCancelled()) throw cancelledError();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            call.cancel();
            throw cancelledError();
        }
        if (call.isCancelled()) throw cancelledError();
        Throwable error = failure.get();
        if (error instanceof ApiError) throw (ApiError) error;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        if (error != null) throw new ApiError(NETWORK, String.valueOf(error.getMessage()), -1);
    }

    private static void streamMessagesBlocking(String origin, String apiKey, JSONObject body, boolean withFiles,
                                               Call call, EventHandler handler) throws ApiError {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(origin + "/anthropic/v1/messages", "POST", call);
        try {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("x-api-key", apiKey);
            if (withFiles) {
                connection.setRequestProperty("anthropic-beta", BETA_FILES);
            }
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            if (call.isCancelled()) throw cancelledError();
            write(connection, payload);

            int status = responseCode(connection);
            Diag.log("HTTP", "响应 " + status + " · 请求体 " + payload.length + " 字节");
            if (status < 200 || status >= 300) {
                throw httpError(connection, status, HTTP);
            }
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.contains("text/event-stream")) {
                throw httpError(connection, status, PROTOCOL);
            }

            Sse sse = new Sse(connection.getInputStream());
            for (;;) {
                if (call.isCancelled()) throw cancelledError();
                String[] event = sse.next();
                if (call.isCancelled()) throw cancelledError();
                if (event == null) {
                    break;
                }
                String name = event[0];
                String data = event[1];
                JSONObject json;
                try {
                    json = new JSONObject(data);
                } catch (Exception ignored) {
                    json = null;
                }
                if (json != null && isErrorBody(json)) {
                    throw new ApiError(STREAM, errorMessage(json), -1);
                }
                if ("error".equals(name)) {
                    throw new ApiError(STREAM, data, -1);
                }
                if (json == null) {
                    continue;
                }
                if (call.isCancelled()) throw cancelledError();
                handler.onEvent(json);
                if ("message_stop".equals(json.optString("type"))) {
                    return;
                }
            }
            // 服务端没给错误内容时保持为空，不推测断流原因
            throw new ApiError(INTERRUPTED, "", -1);
        } catch (IOException error) {
            throw new ApiError(NETWORK, describeNetwork(error), -1);
        } finally {
            call.release(connection);
        }
    }

    // ------------------------------------------------------------------ 附件

    /**
     * POST {origin}/anthropic/v1/files（multipart）；purpose 必须排在 file 之前。
     * 原件直接从磁盘流进连接：大图不再被打包成两三份 byte[]。
     */
    public static JSONObject uploadFile(String origin, String apiKey, String name, String mime,
                                        java.io.File source, String purpose, Call call)
        throws ApiError {
        String boundary = "----nativechat" + Long.toHexString(System.nanoTime());
        byte[] head;
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        try {
            StringBuilder text = new StringBuilder();
            if (purpose != null) {
                text.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n")
                    .append(purpose).append("\r\n");
            }
            String safe = name == null ? "file" : name.replace("\"", "%22").replace("\r", " ")
                .replace("\n", " ");
            String type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            text.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(safe).append("\"\r\n")
                .append("Content-Type: ").append(type).append("\r\n\r\n");
            head = text.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new ApiError(UPLOAD, String.valueOf(error.getMessage()), -1);
        }
        long length = head.length + source.length() + tail.length;

        HttpURLConnection connection = open(origin + "/anthropic/v1/files", "POST", call);
        try {
            connection.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setRequestProperty("anthropic-beta", BETA_FILES);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(length);
            if (call.isCancelled()) throw cancelledError();
            try (java.io.OutputStream out = connection.getOutputStream()) {
                out.write(head);
                try (java.io.FileInputStream in = new java.io.FileInputStream(source)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) > 0) {
                        if (call.isCancelled()) throw cancelledError();
                        out.write(buffer, 0, count);
                    }
                }
                out.write(tail);
                out.flush();
            }

            int status = responseCode(connection);
            if (status < 200 || status >= 300) {
                throw httpError(connection, status, HTTP);
            }
            String text = readAll(connection.getInputStream());
            JSONObject json = new JSONObject(text);
            if (json.optString("id", "").isEmpty()) {
                throw new ApiError(PROTOCOL, text, status);
            }
            return json;
        } catch (ApiError error) {
            throw error;
        } catch (IOException error) {
            throw new ApiError(NETWORK, describeNetwork(error), -1);
        } catch (Exception error) {
            throw new ApiError(PROTOCOL, String.valueOf(error.getMessage()), -1);
        } finally {
            call.release(connection);
        }
    }

    public static void deleteFile(String origin, String apiKey, String fileId) throws ApiError {
        HttpURLConnection connection = open(
            origin + "/anthropic/v1/files/" + encode(fileId), "DELETE", new Call());
        try {
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setRequestProperty("anthropic-beta", BETA_FILES);
            int status = responseCode(connection);
            if (status < 200 || status >= 300) {
                throw httpError(connection, status, HTTP);
            }
        } finally {
            disconnect(connection);
        }
    }

    /** GET /models，只为校验 Key 是否有效，不产生费用。 */
    public static void verifyKey(String origin, String apiKey) throws ApiError {
        HttpURLConnection connection = open(origin + "/models", "GET", new Call());
        try {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            int status = responseCode(connection);
            if (status < 200 || status >= 300) {
                throw httpError(connection, status, HTTP);
            }
        } finally {
            disconnect(connection);
        }
    }

    // ------------------------------------------------------------------ 错误归类

    /** 与 `api/errors.ts` 的 classifyStatus 一致。 */
    public static String condition(int status) {
        if (status == 401) {
            return "unauthorized";
        }
        if (status == 402) {
            return "insufficientBalance";
        }
        if (status == 413) {
            return "tooLarge";
        }
        if (status == 429) {
            return "rateLimited";
        }
        if (status >= 500) {
            return "serverBusy";
        }
        return "other";
    }

    public static String condition(ApiError error) {
        if (error.status <= 0) {
            return NETWORK.equals(error.kind) ? "network" : "other";
        }
        return condition(error.status);
    }

    /** 网络、限流、服务端繁忙值得重试；鉴权与参数类错误重试无用。 */
    public static boolean isRetryable(ApiError error) {
        String condition = condition(error);
        return "network".equals(condition) || "rateLimited".equals(condition)
            || "serverBusy".equals(condition);
    }

    private static final Pattern[] FILE_ERROR_PATTERNS = {
        Pattern.compile("file_ids do not exist or are not created under your account",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("file data not found for file_id", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\.source\\.file_id: invalid file_id", Pattern.CASE_INSENSITIVE),
    };

    /** 服务端判定 file_id 失效的 400：本地有原件时重新上传即可自愈。 */
    public static boolean isFileInvalidated(ApiError error) {
        if (error.status != 400) {
            return false;
        }
        for (Pattern pattern : FILE_ERROR_PATTERNS) {
            if (pattern.matcher(error.getMessage()).find()) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern FILE_ID = Pattern.compile("file-api-[\\w-]+");

    /** 从报错文案里取出服务端点名的 file_id，用于只重置真正失效的附件。 */
    public static java.util.List<String> extractFileIds(String message) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (message == null) {
            return ids;
        }
        Matcher matcher = FILE_ID.matcher(message);
        while (matcher.find()) {
            if (!ids.contains(matcher.group())) {
                ids.add(matcher.group());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ 底层

    private static HttpURLConnection open(String url, String method, Call call) throws ApiError {
        if (call.isCancelled()) throw cancelledError();
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setUseCaches(false);
            if (!call.attach(connection)) {
                disconnect(connection);
                throw cancelledError();
            }
            return connection;
        } catch (IOException error) {
            throw new ApiError(NETWORK, describeNetwork(error), -1);
        }
    }

    private static void write(HttpURLConnection connection, byte[] payload) throws IOException {
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
            out.flush();
        }
    }

    private static int responseCode(HttpURLConnection connection) throws ApiError {
        try {
            return connection.getResponseCode();
        } catch (IOException error) {
            throw new ApiError(NETWORK, describeNetwork(error), -1);
        }
    }

    private static ApiError httpError(HttpURLConnection connection, int status, String kind) {
        String text = "";
        try {
            InputStream error = connection.getErrorStream();
            if (error == null && PROTOCOL.equals(kind)) {
                error = connection.getInputStream();
            }
            text = error == null ? "" : readAll(error);
        } catch (Exception ignored) {
        }
        String message = text;
        try {
            JSONObject json = new JSONObject(text);
            if (isErrorBody(json)) {
                message = errorMessage(json);
            }
        } catch (Exception ignored) {
            // 非 JSON 响应体直接展示原文
        }
        if (message == null || message.isEmpty()) {
            message = "HTTP " + status;
        }
        return new ApiError(kind, message, status);
    }

    private static boolean isErrorBody(JSONObject json) {
        JSONObject error = json.optJSONObject("error");
        return error != null && error.optString("message", null) != null;
    }

    private static String errorMessage(JSONObject json) {
        JSONObject error = json.optJSONObject("error");
        return error == null ? "" : error.optString("message", "");
    }

    private static String describeNetwork(IOException error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        return message;
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception error) {
            return value;
        }
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) > 0) {
            out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * SSE 解析（WHATWG 规范）：按行累积 data / event，空行派发。
     * BufferedReader.readLine 已经按 CRLF / LF / CR 切行；注释行（`: keep-alive`）忽略。
     */
    private static final class Sse {
        private final BufferedReader reader;
        private StringBuilder data = new StringBuilder();
        private boolean hasData;
        private String eventType = "";
        private boolean first = true;

        Sse(InputStream input) {
            reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 8192);
        }

        /** 返回 {event, data}；流结束返回 null。 */
        String[] next() throws IOException {
            for (;;) {
                String line = reader.readLine();
                if (line == null) {
                    return null;
                }
                if (first) {
                    first = false;
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                }
                if (line.isEmpty()) {
                    if (hasData) {
                        String name = eventType.isEmpty() ? "message" : eventType;
                        String[] result = {name, data.toString()};
                        data = new StringBuilder();
                        hasData = false;
                        eventType = "";
                        return result;
                    }
                    eventType = "";
                    continue;
                }
                if (line.charAt(0) == ':') {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if ("data".equals(field)) {
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                } else if ("event".equals(field)) {
                    eventType = value;
                }
            }
        }
    }
}
