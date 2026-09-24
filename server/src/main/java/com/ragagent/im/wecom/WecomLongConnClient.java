package com.ragagent.im.wecom;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 企业微信智能机器人长连接（对照 Go {@code internal/im/wecom/longconn.go} L129-836）。
 *
 * <p>协议：连 {@code wss://openws.work.weixin.qq.com} → 发 {@code aibot_subscribe}
 * （bot_id + secret）→ 收 {@code aibot_msg_callback}/{@code aibot_event_callback}
 * → 用 {@code aibot_respond_msg} 回帧 → 每 30s {@code ping} 心跳（照 Go）。</p>
 *
 * <p>照抄点：读超时 = 3×心跳（单次丢 pong 不误判）；心跳失败即关连接触发重连；
 * 重连退避 1s·2^(n-1) 上限 30s，且"连接活过 30s"就重置退避；<b>流缓冲跨重连保留</b>
 * （WeCom 是替换语义，重连后下次 update 会把全量内容重发）；{@code EndStream} 失败
 * 重试 3 次 × 500ms；回复帧把 {@code req_id} 从消息 extra 带上；
 * {@code disconnected_event} 主动关连接触发重连；群聊 @提及剥离带"机器人名学习"
 * （双空格形态学名，配置的 bot_name 优先）。</p>
 */
public class WecomLongConnClient {

    private static final Logger log = LoggerFactory.getLogger(WecomLongConnClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String CMD_SUBSCRIBE = "aibot_subscribe";
    static final String CMD_PING = "ping";
    static final String CMD_MSG_CALLBACK = "aibot_msg_callback";
    static final String CMD_EVENT_CALLBACK = "aibot_event_callback";
    static final String CMD_RESPONSE = "aibot_respond_msg";

    static final long HEARTBEAT_INTERVAL_MS = 30_000;
    static final long RECONNECT_BASE_MS = 1000;
    static final long RECONNECT_MAX_MS = 30_000;
    static final long READ_TIMEOUT_MS = 3 * HEARTBEAT_INTERVAL_MS;
    static final long AUTH_TIMEOUT_MS = 10_000;
    static final long END_STREAM_RETRY_DELAY_MS = 500;

    /** 连接关闭时投给收件箱的哨兵（唤醒等待的接收循环）。 */
    private static final String CLOSED_SENTINEL = "\u0000closed";

    private final String botId;
    private final String secret;
    private final String endpoint;
    private final String extraAllowedHost;
    private final String channelId;
    private final BiConsumer<IncomingMessage, String> handler;
    private final HttpClient http;
    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    private final AtomicLong reqSeq = new AtomicLong();

    /** 流缓冲（跨重连保留——对照 Go 的 streamBufs）。 */
    final Map<String, StringBuilder> streamBufs = new ConcurrentHashMap<>();

    private volatile WebSocket socket;
    private volatile boolean closed;
    /** 机器人显示名（配置或从双空格形态学到）。 */
    volatile String botDisplayName = "";

    public WecomLongConnClient(String botId, String secret, String wsEndpoint, String botName,
                               String channelId, BiConsumer<IncomingMessage, String> handler,
                               SsrfGuard ssrfGuard) {
        this.botId = botId == null ? "" : botId;
        this.secret = secret == null ? "" : secret;
        String ws = wsEndpoint == null || wsEndpoint.isEmpty()
                ? WecomSupport.DEFAULT_WS_ENDPOINT : wsEndpoint;
        while (ws.endsWith("/")) {
            ws = ws.substring(0, ws.length() - 1);
        }
        WecomSupport.validateEndpointUrl(ws, WecomSupport.DEFAULT_WS_ENDPOINT, "wss", ssrfGuard);
        this.endpoint = ws;
        this.extraAllowedHost = WecomSupport.extraHostFromEndpoint(
                ws, WecomSupport.DEFAULT_WS_ENDPOINT);
        this.botDisplayName = botName == null ? "" : botName;
        this.channelId = channelId;
        this.handler = handler;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 对照 {@code reconnectDelay}：1s·2^(n-1)，上限 30s，n&lt;1 取基值。 */
    static long reconnectDelayMs(int attempt) {
        if (attempt < 1) {
            return RECONNECT_BASE_MS;
        }
        int shift = Math.min(attempt - 1, 30);
        long delay = RECONNECT_BASE_MS << shift;
        return Math.min(delay, RECONNECT_MAX_MS);
    }

    /** 对照 {@code Stop}：置关闭标记并断开（读循环随即退出）。 */
    public void stop() {
        closed = true;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "stopped").join();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
        }
        inbox.offer(CLOSED_SENTINEL);
    }

    /** 阻塞重连循环（对照 {@code Start}）；由工厂放进守护线程跑。 */
    public void start() {
        log.info("[IM] WeCom WebSocket connecting (bot_id={})...", botId);
        int attempts = 0;
        while (!closed) {
            long connectedAt = System.currentTimeMillis();
            try {
                connectAndRun();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                log.warn("[WeCom] Connection lost ({}), reconnecting...", e.toString());
            }
            if (closed) {
                return;
            }
            if (System.currentTimeMillis() - connectedAt > RECONNECT_MAX_MS) {
                attempts = 0; // 连接活过了退避窗口 → 视为偶发断线，快速重试
            }
            attempts++;
            if (!sleep(reconnectDelayMs(attempts))) {
                return;
            }
        }
    }

    private void connectAndRun() throws Exception {
        WebSocket ws = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(endpoint), new Listener())
                .join();
        socket = ws;
        if (closed) {
            throw new IllegalStateException("client stopped");
        }
        authenticate(ws);
        log.info("[IM] WeCom WebSocket connected successfully (bot_id={})", botId);

        Thread heartbeat = new Thread(() -> heartbeatLoop(ws),
                "im-wecom-heartbeat-" + channelId);
        heartbeat.setDaemon(true);
        heartbeat.start();

        try {
            while (true) {
                String raw = inbox.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (raw == null) {
                    throw new IllegalStateException("read message: timeout");
                }
                if (CLOSED_SENTINEL.equals(raw)) {
                    throw new IllegalStateException("read message: connection closed");
                }
                handleFrame(raw);
            }
        } finally {
            closeConnIf(ws);
        }
    }

    /** 对照 {@code authenticate}：发 subscribe，10 秒内等下发的响应并查 errcode。 */
    private void authenticate(WebSocket ws) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("bot_id", botId);
        body.put("secret", secret);
        String reqId = CMD_SUBSCRIBE + "_" + System.nanoTime();
        ws.sendText(buildFrame(CMD_SUBSCRIBE, reqId, body), true).join();

        String raw = inbox.poll(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (raw == null) {
            throw new IllegalStateException("read auth response: timeout");
        }
        if (CLOSED_SENTINEL.equals(raw)) {
            throw new IllegalStateException("read auth response: connection closed");
        }
        JsonNode resp = MAPPER.readTree(raw);
        int errCode = resp.path("errcode").asInt(0);
        if (errCode != 0) {
            throw new IllegalStateException("auth failed: code=" + errCode + " msg="
                    + resp.path("errmsg").asText(""));
        }
    }

    /** 对照 {@code heartbeatLoop}：每 30s 发 ping；失败即关连接触发重连。 */
    private void heartbeatLoop(WebSocket ws) {
        while (!closed) {
            if (!sleep(HEARTBEAT_INTERVAL_MS)) {
                return;
            }
            try {
                String reqId = CMD_PING + "_" + System.nanoTime();
                ws.sendText(buildFrame(CMD_PING, reqId, null), true).join();
            } catch (RuntimeException e) {
                log.warn("[WeCom] Heartbeat failed: {}, closing connection to trigger reconnect",
                        e.toString());
                closeConnIf(ws);
                return;
            }
        }
    }

    private void handleFrame(String raw) {
        JsonNode frame;
        try {
            frame = MAPPER.readTree(raw);
        } catch (Exception e) {
            log.warn("[WeCom] Failed to unmarshal frame: {}", e.toString());
            return;
        }
        String cmd = frame.path("cmd").asText("");
        if (CMD_MSG_CALLBACK.equals(cmd) || CMD_EVENT_CALLBACK.equals(cmd)) {
            // 照 Go：回调处理与连接生命周期解耦（重连不影响在飞消息）
            Thread worker = new Thread(() -> handleCallback(frame),
                    "im-wecom-callback-" + channelId);
            worker.setDaemon(true);
            worker.start();
        }
        // 其它（pong 等控制帧）忽略
    }

    /** 对照 {@code handleCallback}：event 分流 + 五种消息类型 + 引用上下文。 */
    void handleCallback(JsonNode frame) {
        IncomingMessage incoming = parseCallbackBody(frame);
        if (incoming == null) {
            return;
        }
        try {
            handler.accept(incoming, channelId);
        } catch (Exception e) {
            log.error("[WeCom] Handle message error: {}", e.toString());
        }
    }

    /**
     * 解析回调帧体（对照 {@code handleCallback} 的主体）——包内可见以便单测。
     * 事件帧（如 {@code disconnected_event}）返回 null 并顺带关连接（照 Go）。
     */
    IncomingMessage parseCallbackBody(JsonNode frame) {
        JsonNode msg = frame.path("body");
        String msgType = msg.path("msgtype").asText("");
        if ("event".equals(msgType)) {
            String eventType = msg.path("event").path("eventtype").asText("");
            if ("disconnected_event".equals(eventType)) {
                log.warn("[WeCom] Server sent disconnected_event, closing connection to trigger"
                        + " reconnect");
                WebSocket ws = socket;
                if (ws != null) {
                    closeConnIf(ws);
                }
            } else {
                log.info("[WeCom] Ignoring event type: {}", eventType);
            }
            return null;
        }

        String reqId = frame.path("headers").path("req_id").asText("");
        String chatTypeRaw = msg.path("chattype").asText("");
        boolean isGroup = "group".equals(chatTypeRaw);
        String chatType = isGroup ? ImTypes.CHAT_TYPE_GROUP : ImTypes.CHAT_TYPE_DIRECT;
        String chatId = isGroup ? msg.path("chatid").asText("") : "";
        String userId = msg.path("from").path("userid").asText("");
        String msgId = msg.path("msgid").asText("");

        IncomingMessage incoming = switch (msgType) {
            case "text" -> {
                String content = msg.path("text").path("content").asText("");
                if (isGroup) {
                    content = stripAtMention(content);
                }
                yield textMessage(userId, chatId, chatType, msgId, content, reqId, null);
            }
            case "voice" -> {
                String content = msg.path("voice").path("content").asText("");
                if (content.isEmpty()) {
                    log.info("[WeCom] Ignoring voice message with empty content");
                    yield null;
                }
                yield textMessage(userId, chatId, chatType, msgId, content, reqId, null);
            }
            case "image" -> {
                String url = msg.path("image").path("url").asText("");
                if (url.isEmpty()) {
                    log.info("[WeCom] Ignoring image message with empty URL");
                    yield null;
                }
                yield fileMessage(ImTypes.MESSAGE_TYPE_IMAGE, userId, chatId, chatType, msgId,
                        url, msgId + ".png", msg.path("image").path("aeskey").asText(""), reqId);
            }
            case "file" -> {
                String url = msg.path("file").path("url").asText("");
                if (url.isEmpty()) {
                    log.info("[WeCom] Ignoring file message with empty URL");
                    yield null;
                }
                yield fileMessage(ImTypes.MESSAGE_TYPE_FILE, userId, chatId, chatType, msgId,
                        url, msgId, msg.path("file").path("aeskey").asText(""), reqId);
            }
            case "mixed" -> convertMixedMessage(msg, chatId, chatType, reqId);
            default -> {
                log.info("[WeCom] Ignoring unsupported message type: {}", msgType);
                yield null;
            }
        };
        if (incoming == null) {
            return null;
        }
        JsonNode quote = msg.path("quote");
        if (!quote.isMissingNode() && !quote.isNull()) {
            incoming.quote = buildQuotedMessage(quote, msg.path("aibotid").asText(""));
        }
        return incoming;
    }

    private IncomingMessage textMessage(String userId, String chatId, String chatType,
                                        String msgId, String content, String reqId, String unused) {
        IncomingMessage msg = baseMessage(userId, chatId, chatType, msgId, reqId);
        msg.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        msg.content = content == null ? "" : content.trim();
        return msg;
    }

    private IncomingMessage fileMessage(String messageType, String userId, String chatId,
                                        String chatType, String msgId, String url, String fileName,
                                        String aesKey, String reqId) {
        IncomingMessage msg = baseMessage(userId, chatId, chatType, msgId, reqId);
        msg.messageType = messageType;
        msg.fileKey = url; // 存加密下载 URL（照 Go）
        msg.fileName = fileName;
        if (aesKey != null && !aesKey.isEmpty()) {
            msg.extra.put("aes_key", aesKey);
        }
        return msg;
    }

    private IncomingMessage baseMessage(String userId, String chatId, String chatType,
                                        String msgId, String reqId) {
        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_WECOM;
        msg.userId = userId;
        msg.userName = userId;
        msg.chatId = chatId;
        msg.chatType = chatType;
        msg.messageId = msgId;
        msg.extra.put("req_id", reqId);
        return msg;
    }

    /** 对照 {@code convertMixedMessage}：有文本按文本（换行连接），纯图按图。 */
    IncomingMessage convertMixedMessage(JsonNode msg, String chatId, String chatType,
                                        String reqId) {
        boolean isGroup = ImTypes.CHAT_TYPE_GROUP.equals(chatType);
        List<String> textParts = new java.util.ArrayList<>();
        String firstImageUrl = "";
        String firstImageAesKey = "";
        for (JsonNode item : msg.path("mixed").path("msg_item")) {
            String itemType = item.path("msgtype").asText("");
            if ("text".equals(itemType)) {
                String text = item.path("text").path("content").asText("").trim();
                if (isGroup) {
                    text = stripAtMention(text);
                }
                if (!text.isEmpty()) {
                    textParts.add(text);
                }
            } else if ("image".equals(itemType) && firstImageUrl.isEmpty()) {
                String url = item.path("image").path("url").asText("");
                if (!url.isEmpty()) {
                    firstImageUrl = url;
                    firstImageAesKey = item.path("image").path("aeskey").asText("");
                }
            }
        }
        String userId = msg.path("from").path("userid").asText("");
        String msgId = msg.path("msgid").asText("");
        if (!textParts.isEmpty()) {
            return textMessage(userId, chatId, chatType, msgId, String.join("\n", textParts),
                    reqId, null);
        }
        if (!firstImageUrl.isEmpty()) {
            return fileMessage(ImTypes.MESSAGE_TYPE_IMAGE, userId, chatId, chatType, msgId,
                    firstImageUrl, msgId + ".png", firstImageAesKey, reqId);
        }
        return null;
    }

    // ── 引用上下文（对照 quote.go） ─────────────────────────────────────────

    /** 对照 {@code buildQuotedMessage}：非文本引用只给 NonTextType，不给占位内容。 */
    static IncomingMessage.QuotedMessage buildQuotedMessage(JsonNode quote, String aiBotId) {
        if (quote == null || quote.isMissingNode() || quote.isNull()) {
            return null;
        }
        String msgType = quote.path("msgtype").asText("");
        String content = switch (msgType) {
            case "text" -> quote.path("text").path("content").asText("");
            case "voice" -> quote.path("voice").path("content").asText("");
            case "mixed" -> {
                List<String> parts = new java.util.ArrayList<>();
                for (JsonNode item : quote.path("mixed").path("msg_item")) {
                    if ("text".equals(item.path("msgtype").asText(""))) {
                        String text = item.path("text").path("content").asText("");
                        if (!text.isEmpty()) {
                            parts.add(text);
                        }
                    }
                }
                yield String.join("\n", parts);
            }
            default -> "";
        };
        String fromUserId = quote.path("from").path("userid").asText("");
        String quoteAiBotId = quote.path("aibotid").asText("");
        boolean isBot = (!fromUserId.isEmpty() && !aiBotId.isEmpty() && fromUserId.equals(aiBotId))
                || (!quoteAiBotId.isEmpty() && quoteAiBotId.equals(aiBotId));

        IncomingMessage.QuotedMessage result = new IncomingMessage.QuotedMessage();
        result.messageId = quote.path("msgid").asText("");
        result.content = content;
        result.senderId = fromUserId;
        result.isBotMessage = isBot;
        if (content.isEmpty()) {
            result.nonTextType = msgType;
        }
        return result;
    }

    // ── 出站 ────────────────────────────────────────────────────────────────

    /** 对照 {@code SendReply}：一次性流帧（finish=true）。 */
    public void sendReply(IncomingMessage incoming, String content) throws Exception {
        String reqId = reqIdOf(incoming);
        if (reqId.isEmpty()) {
            throw new IllegalStateException("missing req_id in incoming message extra");
        }
        String streamId = "stream_" + reqSeq.incrementAndGet();
        writeJson(buildStreamFrame(streamId, content, true, reqId));
    }

    /** 对照 {@code StartStream}：只建缓冲，不发帧。 */
    public String startStream(IncomingMessage incoming) throws Exception {
        String reqId = reqIdOf(incoming);
        if (reqId.isEmpty()) {
            throw new IllegalStateException("missing req_id in incoming message extra");
        }
        String streamId = "stream_" + reqSeq.incrementAndGet();
        streamBufs.put(streamId, new StringBuilder());
        return streamId;
    }

    /** 对照 {@code UpdateStreamContent}：替换语义——发全量内容。 */
    public void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        if (fullContent == null || fullContent.isEmpty()) {
            return;
        }
        StringBuilder buffer = streamBufs.get(streamId);
        if (buffer == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        buffer.setLength(0);
        buffer.append(fullContent);
        writeJson(buildStreamFrame(streamId, fullContent, false, reqIdOf(incoming)));
    }

    /** 对照 {@code FinalizeStream}：即 UpdateStreamContent。 */
    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        updateStreamContent(incoming, streamId, finalContent);
    }

    /** 对照 {@code EndStream}：发收尾帧（带累积内容），失败重试 3 次 × 500ms。 */
    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        StringBuilder buffer = streamBufs.remove(streamId);
        String fullContent = buffer == null ? "" : buffer.toString();
        Exception last;
        try {
            writeJson(buildStreamFrame(streamId, fullContent, true, reqIdOf(incoming)));
            return;
        } catch (Exception e) {
            last = e;
        }
        for (int i = 0; i < 3; i++) {
            if (!sleep(END_STREAM_RETRY_DELAY_MS)) {
                return;
            }
            try {
                writeJson(buildStreamFrame(streamId, fullContent, true, reqIdOf(incoming)));
                return;
            } catch (Exception retryErr) {
                last = retryErr;
            }
        }
        throw last;
    }

    /** 对照 {@code sendStreamFrame}：{@code aibot_respond_msg} + stream 体。 */
    static String buildStreamFrame(String streamId, String content, boolean finish, String reqId) {
        ObjectNode stream = MAPPER.createObjectNode();
        stream.put("id", streamId);
        stream.put("finish", finish);
        stream.put("content", content == null ? "" : content);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("msgtype", "stream");
        body.set("stream", stream);
        return buildFrame(CMD_RESPONSE, reqId, body);
    }

    /** 组帧：{@code {cmd, headers:{req_id}, body}}（body 为对象，照 Go 的 RawMessage）。 */
    static String buildFrame(String cmd, String reqId, JsonNode body) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("cmd", cmd);
        ObjectNode headers = MAPPER.createObjectNode();
        headers.put("req_id", reqId == null ? "" : reqId);
        frame.set("headers", headers);
        if (body != null) {
            frame.set("body", body);
        }
        return frame.toString();
    }

    private static String reqIdOf(IncomingMessage incoming) {
        if (incoming.extra == null) {
            return "";
        }
        String reqId = incoming.extra.get("req_id");
        return reqId == null ? "" : reqId;
    }

    /** 对照 {@code writeJSON}：连接不在即报错；写超时 10s。 */
    private void writeJson(String payload) throws Exception {
        WebSocket ws = socket;
        if (ws == null || closed) {
            throw new IllegalStateException("connection closed");
        }
        ws.sendText(payload, true).get(10, TimeUnit.SECONDS);
    }

    // ── @提及剥离（有状态：会学机器人名） ───────────────────────────────────

    /** 对照 {@code stripAtMention}：双空格（学名）→ 缓存名前缀 → 无状态兜底。 */
    String stripAtMention(String content) {
        String value = content == null ? "" : content.trim();
        if (!value.startsWith("@")) {
            return value;
        }
        int doubleSpace = value.indexOf("  ");
        if (doubleSpace > 0) {
            String learned = value.substring(1, doubleSpace);
            if (botDisplayName.isEmpty() && !learned.isEmpty()) {
                botDisplayName = learned;
            }
            return value.substring(doubleSpace + 2).trim();
        }
        if (!botDisplayName.isEmpty()) {
            String prefix = "@" + botDisplayName;
            if (value.startsWith(prefix)
                    && (value.length() == prefix.length() || value.charAt(prefix.length()) == ' ')) {
                return value.substring(prefix.length()).trim();
            }
        }
        return WecomWebhookAdapter.stripAtMentionBasic(value);
    }

    // ── 连接管理 ────────────────────────────────────────────────────────────

    /** 对照 {@code closeConnIf}：只关"仍是当前活跃"的那条连接。 */
    private void closeConnIf(WebSocket ws) {
        if (ws == null) {
            return;
        }
        boolean active = socket == ws;
        if (active) {
            socket = null;
        }
        if (active) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").join();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
        }
        inbox.offer(CLOSED_SENTINEL);
    }

    /** 返回 false 表示应退出循环（stop 被调用或线程被中断）。 */
    private boolean sleep(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (!closed && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.min(100, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !closed;
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                inbox.offer(payload);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            inbox.offer(CLOSED_SENTINEL);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("[WeCom] WebSocket error: {}", error.toString());
            inbox.offer(CLOSED_SENTINEL);
        }
    }

    /** 供装配/测试观察。 */
    String endpoint() {
        return endpoint;
    }

    String extraAllowedHost() {
        return extraAllowedHost;
    }
}
