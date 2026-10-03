package com.ragagent.im.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.runtime.ThinkDisplay;

/**
 * Telegram Bot 适配器。
 *
 * <p>三面齐备：{@code Adapter}（验签/解析/发送）+ {@code StreamSender}
 * （"正在思考..." 占位消息 + editMessageText 原地替换）+ {@code FileDownloader}
 * （getFile + file/bot 下载）。</p>
 *
 * <h2>实现差异（备案）</h2>
 * <ul>
 *   <li><b>API 基址可注入</b>：常量 {@link #DEFAULT_API_BASE} 作生产默认，
 *       包内构造可注入基址，测试用本地 stub 服务器（与 OTLP 导出器同一套路）。</li>
 *   <li><b>孤儿流的回收是惰性的</b>：在每次 {@link #startStream} 时顺带清理
 *       5 分钟前的孤儿流（无后台线程）。</li>
 * </ul>
 */
public class TelegramAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.StreamSender, AdapterInterfaces.FileDownloader {

    private static final Logger log = LoggerFactory.getLogger(TelegramAdapter.class);

    /** 生产基址。 */
    public static final String DEFAULT_API_BASE = "https://api.telegram.org";

    /** 两次 editMessageText 的最小间隔。 */
    static final long MIN_EDIT_INTERVAL_MS = 500;
    /** 孤儿流 TTL。 */
    static final long STREAM_ORPHAN_TTL_MS = 5 * 60 * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 全局流表，key = {@code {chatId}:{msgId}}。 */
    static final Map<String, StreamState> STREAMS = new ConcurrentHashMap<>();

    /** 一条流的状态。 */
    static final class StreamState {
        final String chatId;
        final String msgId;
        final long createdAt = System.currentTimeMillis();
        volatile long lastEdit;

        StreamState(String chatId, String msgId) {
            this.chatId = chatId;
            this.msgId = msgId;
        }
    }

    private final String botToken;
    private final String secretToken;
    private final String apiBase;
    private final HttpClient http;

    public TelegramAdapter(String botToken, String secretToken) {
        this(botToken, secretToken, DEFAULT_API_BASE, defaultClient());
    }

    /** 测试用：注入 API 基址（生产走 {@link #DEFAULT_API_BASE}）。 */
    TelegramAdapter(String botToken, String secretToken, String apiBase) {
        this(botToken, secretToken, apiBase, defaultClient());
    }

    TelegramAdapter(String botToken, String secretToken, String apiBase, HttpClient http) {
        this.botToken = botToken == null ? "" : botToken;
        this.secretToken = secretToken == null ? "" : secretToken;
        this.apiBase = apiBase == null || apiBase.isEmpty() ? DEFAULT_API_BASE : apiBase;
        this.http = http;
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    @Override
    public String platform() {
        return ImTypes.PLATFORM_TELEGRAM;
    }

    /** 无 secret_token 直接放行；有则常量时间比较。 */
    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        if (secretToken.isEmpty()) {
            return null;
        }
        String token = exchange.header("X-Telegram-Bot-Api-Secret-Token");
        boolean ok = MessageDigest.isEqual(
                (token == null ? "" : token).getBytes(StandardCharsets.UTF_8),
                secretToken.getBytes(StandardCharsets.UTF_8));
        return ok ? null : new AdapterInterfaces.VerifyException("invalid secret token");
    }

    /** Telegram 没有 URL verification 挑战（恒 false）。 */
    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return false;
    }

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        byte[] body = exchange.body();
        JsonNode update = body == null || body.length == 0
                ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        return parseUpdate(update);
    }

    /** 非消息事件（无 message 字段）→ null。 */
    static IncomingMessage parseUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode() || message.isNull()) {
            return null;
        }
        return parseTelegramMessage(message);
    }

    /** 群聊剥 @bot 前缀 + document/photo 映射。 */
    static IncomingMessage parseTelegramMessage(JsonNode msg) {
        if (msg == null || msg.isMissingNode() || msg.isNull()) {
            return null;
        }
        String chatType = ImTypes.CHAT_TYPE_DIRECT;
        String chatId = "";
        JsonNode chat = msg.path("chat");
        String type = chat.path("type").asText("");
        if ("group".equals(type) || "supergroup".equals(type)) {
            chatType = ImTypes.CHAT_TYPE_GROUP;
            chatId = chat.path("id").asText("");
        }

        String userId = "";
        String userName = "";
        JsonNode from = msg.path("from");
        if (!from.isMissingNode() && !from.isNull()) {
            userId = from.path("id").asText("");
            userName = (from.path("first_name").asText("") + " " + from.path("last_name").asText("")).trim();
            if (userName.isEmpty()) {
                userName = from.path("username").asText("");
            }
        }

        String threadId = "";
        if (msg.path("message_thread_id").asInt(0) != 0) {
            threadId = String.valueOf(msg.path("message_thread_id").asInt(0));
        }

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_TELEGRAM;
        incoming.userId = userId;
        incoming.userName = userName;
        incoming.chatId = chatId;
        incoming.chatType = chatType;
        incoming.messageId = msg.path("message_id").asText("");
        incoming.threadId = threadId;
        incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        incoming.content = msg.path("text").asText("");

        // 群聊：剥掉 @bot 前缀（"/command@botname text" → "text"）
        if (ImTypes.CHAT_TYPE_GROUP.equals(chatType)) {
            String content = incoming.content.trim();
            int idx = content.indexOf(' ');
            if (idx > 0 && content.substring(0, idx).contains("@")) {
                content = content.substring(idx + 1).trim();
            }
            incoming.content = content;
        }

        JsonNode document = msg.path("document");
        if (!document.isMissingNode() && !document.isNull()) {
            incoming.messageType = ImTypes.MESSAGE_TYPE_FILE;
            incoming.fileKey = document.path("file_id").asText("");
            incoming.fileName = document.path("file_name").asText("");
            incoming.fileSize = document.path("file_size").asLong(0);
        }

        JsonNode photo = msg.path("photo");
        if (photo.isArray() && !photo.isEmpty()) {
            JsonNode largest = photo.get(photo.size() - 1);
            incoming.messageType = ImTypes.MESSAGE_TYPE_IMAGE;
            incoming.fileKey = largest.path("file_id").asText("");
            incoming.fileName = "photo.jpg";
            incoming.fileSize = largest.path("file_size").asLong(0);
        }

        return incoming;
    }

    /** 群聊用 chat_id，私聊回落 user_id。 */
    static String resolveChatId(IncomingMessage incoming) {
        return incoming.chatId == null || incoming.chatId.isEmpty()
                ? (incoming.userId == null ? "" : incoming.userId) : incoming.chatId;
    }

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String chatId = resolveChatId(incoming);
        String text = ThinkDisplay.formatIMDisplayContent(reply.content, ThinkDisplay.STREAM_DISPLAY_FINAL);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "Markdown");
        Integer threadId = threadIdOf(incoming);
        if (threadId != null) {
            body.put("message_thread_id", threadId);
        }
        callApi("sendMessage", body);
    }

    private static Integer threadIdOf(IncomingMessage incoming) {
        if (incoming.threadId == null || incoming.threadId.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(incoming.threadId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** parse_mode 为空则不带该键。 */
    private void editMessage(String chatId, String messageId, String text, String parseMode)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        if (parseMode != null && !parseMode.isEmpty()) {
            body.put("parse_mode", parseMode);
        }
        callApi("editMessageText", body);
    }

    /** POST JSON，解 {@code {ok, result}}。 */
    private JsonNode callApi(String method, Object body) throws Exception {
        URI uri = URI.create(apiBase + "/bot" + botToken + "/" + method);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        JsonNode apiResp = raw == null || raw.length == 0
                ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
        if (!apiResp.path("ok").asBoolean(false)) {
            throw new IllegalStateException("telegram API " + method + " failed: "
                    + apiResp.path("result"));
        }
        return apiResp.path("result");
    }

    // ── StreamSender（editMessageText 原地替换） ─────────────────────────────

    @Override
    public String startStream(IncomingMessage incoming) throws Exception {
        purgeOrphans();
        String chatId = resolveChatId(incoming);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", "正在思考...");
        Integer threadId = threadIdOf(incoming);
        if (threadId != null) {
            body.put("message_thread_id", threadId);
        }
        JsonNode result = callApi("sendMessage", body);

        String msgId = result.path("message_id").asText("");
        String streamId = chatId + ":" + msgId;
        STREAMS.put(streamId, new StreamState(chatId, msgId));
        log.info("[Telegram] Streaming started: stream_id={}", streamId);
        return streamId;
    }

    @Override
    public void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        if (fullContent == null || fullContent.isEmpty()) {
            return;
        }
        StreamState state = STREAMS.get(streamId);
        if (state == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        synchronized (state) {
            if (System.currentTimeMillis() - state.lastEdit < MIN_EDIT_INTERVAL_MS) {
                // 节流窗口内只记内容、不发请求
                return;
            }
            state.lastEdit = System.currentTimeMillis();
        }
        try {
            editMessage(state.chatId, state.msgId, fullContent, "");
        } catch (Exception e) {
            // 更新失败只告警，不抛（流式期间的一次编辑失败不该中断回答）
            log.warn("[Telegram] Failed to update stream content: {}", e.toString());
        }
    }

    @Override
    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        StreamState state = STREAMS.get(streamId);
        if (state == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        try {
            editMessage(state.chatId, state.msgId, finalContent, "Markdown");
        } catch (Exception e) {
            log.warn("[Telegram] Markdown finalize failed, retrying plain: {}", e.toString());
            try {
                editMessage(state.chatId, state.msgId, finalContent, "");
            } catch (Exception retryErr) {
                log.warn("[Telegram] Failed to finalize stream: {}", retryErr.toString());
            }
        }
    }

    @Override
    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        if (STREAMS.remove(streamId) != null) {
            log.info("[Telegram] Streaming ended: stream_id={}", streamId);
        }
    }

    /** 惰性清掉超过 TTL 的孤儿流。 */
    static void purgeOrphans() {
        long cutoff = System.currentTimeMillis() - STREAM_ORPHAN_TTL_MS;
        STREAMS.entrySet().removeIf(e -> e.getValue().createdAt < cutoff);
    }

    // ── FileDownloader ──────────────────────────────────────────────────────

    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()) {
            throw new IllegalArgumentException("file_key is required");
        }
        JsonNode fileInfo = callApi("getFile", Map.of("file_id", msg.fileKey));
        String filePath = fileInfo.path("file_path").asText("");

        URI uri = URI.create(apiBase + "/file/bot" + botToken + "/" + filePath);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            throw new IOException("download file: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("download failed with status " + response.statusCode());
        }
        return new DownloadedFile(response.body(), msg.fileName == null ? "" : msg.fileName);
    }
}
