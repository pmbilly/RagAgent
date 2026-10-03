package com.ragagent.im.yunzhijia;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 云之家长连接（websocket）客户端。
 *
 * <h2>行为要点</h2>
 * <ul>
 *   <li>常量：心跳 15s、读超时 45s、握手 10s、单帧上限 1MiB、<b>连续坏帧上限 3</b>、
 *       消息队列 64；重连间隔表 {@code [1s,2s,5s,10s,30s,60s]}（越界取末项），
 *       连接活过 <b>60s</b> 则计数清零；</li>
 *   <li>帧分类（{@code parseWebSocketFrame}）：空帧报错；裸串 {@code ping/pong} 或
 *       JSON 字符串 {@code "ping"/"pong"} 算控制帧；<b>先试业务消息</b>
 *       （六字符串字段 robotId/robotName/operatorOpenid/operatorName/msgId 齐全 + type 整数 +
 *       time 整数才算）；否则 {@code type=robotMessage} 取 {@code msg} 子对象再试；
 *       再否则从 {@code cmd}/{@code type}/{@code event} 取控制名；
 *       {@code cmd=directpush} 或 {@code type=msgchg} 且 {@code needAck=true} 时回
 *       {@code {"cmd":"ack","seq":<seq>}}；</li>
 *   <li>收到业务消息 → 复用 {@link YunzhijiaAdapter#toIncomingMessage} 转统一消息 → 队列 → 处理器；</li>
 *   <li>心跳：每 15s 发 WS 控制 ping（5s 等待），失败即关连接触发重连；</li>
 *   <li>关连接/写文本都加锁并校验"还是当前连接"。</li>
 * </ul>
 *
 * <h2>实现差异（备案）</h2>
 * <p>Java 的 {@code java.net.http.WebSocket} 没有读 deadline 与自定义拨号器：
 * 读超时改为由心跳线程检查"距上次收帧超过 45s 即判死"；公网 IP 校验在连接前解析
 * （见 {@link YunzhijiaUrl#resolvePublicAddress}）。</p>
 */
public class YunzhijiaLongConnClient {

    private static final Logger log = LoggerFactory.getLogger(YunzhijiaLongConnClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    static final long READ_TIMEOUT_MS = 45_000L;
    static final int HANDSHAKE_TIMEOUT_SECONDS = 10;
    static final int MAX_INVALID_FRAMES = 3;
    static final int QUEUE_SIZE = 64;
    static final long[] RECONNECT_DELAYS_MS = {1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L};
    private static final String CLOSED_SENTINEL = "\u0000closed";

    /** 一帧的解析结果。 */
    record ParsedFrame(YunzhijiaTypes.CallbackMessage message, byte[] ack, String control) {
    }

    private final String url;
    private final String channelId;
    private final BiConsumer<IncomingMessage, String> msgHandler;
    private final HttpClient http;
    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>(QUEUE_SIZE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile WebSocket ws;
    private volatile long lastFrameAt = System.currentTimeMillis();
    private volatile Thread loopThread;

    public YunzhijiaLongConnClient(String webSocketUrl, String channelId,
                                   BiConsumer<IncomingMessage, String> msgHandler) {
        this.url = webSocketUrl == null ? "" : webSocketUrl.trim();
        this.channelId = channelId == null ? "" : channelId;
        this.msgHandler = msgHandler;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HANDSHAKE_TIMEOUT_SECONDS))
                .build();
    }

    /** 无限重连 + 间隔表 + 活过 60s 计数清零。 */
    public void start() {
        loopThread = Thread.currentThread();
        int attempt = 0;
        while (!closed.get()) {
            long connectedAt = System.currentTimeMillis();
            try {
                connectAndRun();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[Yunzhijia] WebSocket error: {}", e.toString());
            }
            if (closed.get()) {
                return;
            }
            if (System.currentTimeMillis() - connectedAt >= RECONNECT_DELAYS_MS[
                    RECONNECT_DELAYS_MS.length - 1]) {
                attempt = 0;
            }
            long delay = webSocketReconnectDelayMs(attempt);
            attempt++;
            log.warn("[Yunzhijia] WebSocket connection lost; reconnecting in {}ms", delay);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void stop() {
        closed.set(true);
        closeConn();
        Thread loop = loopThread;
        if (loop != null) {
            loop.interrupt();
        }
    }

    /** 查表取重连间隔（越界取末项，负数取首项）。 */
    static long webSocketReconnectDelayMs(int attempt) {
        if (attempt < 0) {
            attempt = 0;
        }
        if (attempt >= RECONNECT_DELAYS_MS.length) {
            return RECONNECT_DELAYS_MS[RECONNECT_DELAYS_MS.length - 1];
        }
        return RECONNECT_DELAYS_MS[attempt];
    }

    /** 建连 → 心跳线程 → 读帧循环（直到出错/关闭）。 */
    void connectAndRun() throws Exception {
        WebSocket socket;
        try {
            socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(HANDSHAKE_TIMEOUT_SECONDS))
                    .buildAsync(URI.create(url), new Listener())
                    .get(HANDSHAKE_TIMEOUT_SECONDS + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("dial websocket: " + e.getMessage(), e);
        }
        this.ws = socket;
        this.lastFrameAt = System.currentTimeMillis();
        log.info("[Yunzhijia] WebSocket connected: channel={}", channelId);

        Thread heartbeat = new Thread(() -> heartbeatLoop(socket), "im-yzj-heartbeat-" + channelId);
        heartbeat.setDaemon(true);
        heartbeat.start();

        try {
            int invalidFrames = 0;
            while (!closed.get()) {
                String raw = inbox.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (raw == null) {
                    // 读超时
                    throw new IllegalStateException("read websocket message: timeout");
                }
                if (CLOSED_SENTINEL.equals(raw)) {
                    throw new IllegalStateException("websocket closed");
                }
                lastFrameAt = System.currentTimeMillis();

                ParsedFrame frame;
                try {
                    frame = parseWebSocketFrame(raw);
                } catch (RuntimeException e) {
                    invalidFrames++;
                    log.warn("[Yunzhijia] Invalid WebSocket frame: {}", e.getMessage());
                    if (invalidFrames >= MAX_INVALID_FRAMES) {
                        throw new IllegalStateException("too many invalid websocket frames");
                    }
                    continue;
                }
                invalidFrames = 0;

                if (frame.ack() != null && frame.ack().length > 0) {
                    writeText(socket, new String(frame.ack(), StandardCharsets.UTF_8));
                }
                if (frame.message() == null) {
                    continue;
                }
                IncomingMessage incoming = YunzhijiaAdapter.toIncomingMessage(frame.message());
                if (incoming == null) {
                    continue;
                }
                try {
                    if (msgHandler != null) {
                        msgHandler.accept(incoming, channelId);
                    }
                } catch (Exception e) {
                    log.error("[Yunzhijia] Handle WebSocket message failed: {}", e.toString());
                }
            }
        } finally {
            heartbeat.interrupt();
            closeConn();
        }
    }

    /** 每 15s 一次控制 ping（5s 等待），失败即关连接。 */
    private void heartbeatLoop(WebSocket socket) {
        try {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                if (socket != ws) {
                    return;
                }
                if (System.currentTimeMillis() - lastFrameAt > READ_TIMEOUT_MS) {
                    log.warn("[Yunzhijia] WebSocket read timeout, closing connection");
                    socket.abort();
                    return;
                }
                try {
                    socket.sendPing(ByteBuffer.wrap(new byte[0]))
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("[Yunzhijia] WebSocket heartbeat failed: {}", e.toString());
                    socket.abort();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 只往"当前连接"写。 */
    private void writeText(WebSocket socket, String data) {
        if (socket != ws) {
            throw new IllegalStateException("websocket connection changed");
        }
        socket.sendText(data, true).join();
    }

    private void closeConn() {
        WebSocket socket = ws;
        if (socket != null) {
            try {
                socket.abort();
            } catch (RuntimeException e) {
                log.debug("[Yunzhijia] abort failed: {}", e.toString());
            }
            ws = null;
        }
    }

    // ── 帧解析（纯逻辑，可单测） ─────────────────────────────────────────────

    static ParsedFrame parseWebSocketFrame(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty frame");
        }
        String plain = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (plain.equals("ping") || plain.equals("pong")) {
            return new ParsedFrame(null, null, plain);
        }

        // JSON 字符串形态：控制帧
        if (trimmed.startsWith("\"")) {
            try {
                String control = MAPPER.readValue(trimmed, String.class).trim()
                        .toLowerCase(java.util.Locale.ROOT);
                if (control.equals("ping") || control.equals("pong")) {
                    return new ParsedFrame(null, null, control);
                }
                throw new IllegalArgumentException("unknown string frame");
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("decode frame: " + e.getMessage(), e);
            }
        }

        JsonNode fields;
        try {
            fields = MAPPER.readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("decode frame: " + e.getMessage(), e);
        }
        if (fields == null || !fields.isObject()) {
            throw new IllegalArgumentException("decode frame: not an object");
        }

        YunzhijiaTypes.CallbackMessage message = decodeBusinessMessage(fields);
        if (message != null) {
            return new ParsedFrame(message, null, null);
        }

        String typeName = rawString(fields.get("type"));
        if (typeName.equalsIgnoreCase("robotMessage")) {
            YunzhijiaTypes.CallbackMessage inner = decodeBusinessMessage(fields.get("msg"));
            if (inner != null) {
                return new ParsedFrame(inner, null, null);
            }
            throw new IllegalArgumentException("robotMessage envelope has invalid msg");
        }

        String cmd = rawString(fields.get("cmd")).trim().toLowerCase(java.util.Locale.ROOT);
        String typeLower = typeName.trim().toLowerCase(java.util.Locale.ROOT);
        String event = rawString(fields.get("event")).trim().toLowerCase(java.util.Locale.ROOT);
        String control = !cmd.isEmpty() ? cmd : (!typeLower.isEmpty() ? typeLower : event);

        if (cmd.equals("directpush") || typeLower.equals("msgchg")) {
            boolean needAck = fields.path("needAck").asBoolean(false);
            long seq = fields.path("seq").asLong(0);
            byte[] ack = null;
            if (needAck) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("cmd", "ack");
                node.put("seq", seq);
                ack = node.toString().getBytes(StandardCharsets.UTF_8);
            }
            return new ParsedFrame(null, ack, control);
        }
        if (!control.isEmpty()) {
            return new ParsedFrame(null, null, control);
        }
        throw new IllegalArgumentException("frame has no business message or control type");
    }

    /** 业务消息判据：六个字符串字段 + type 整数 + time 整数。 */
    static YunzhijiaTypes.CallbackMessage decodeBusinessMessage(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return null;
        }
        List<String> required = new ArrayList<>(List.of("robotId", "robotName", "operatorOpenid",
                "operatorName", "msgId", "content"));
        for (String key : required) {
            if (!data.has(key) || !data.get(key).isTextual()) {
                return null;
            }
        }
        if (!data.path("type").isNumber() || !data.path("time").isNumber()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(data, YunzhijiaTypes.CallbackMessage.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 非字符串或缺失 → 空串。 */
    static String rawString(JsonNode node) {
        return node != null && node.isTextual() ? node.asText("") : "";
    }

    /** 供测试投递一帧（替代 WS 收包，照 wecom/dingtalk 长连接的测试粒度）。 */
    void offerFrame(String raw) {
        inbox.offer(raw);
    }

    /** 供测试触发读循环退出。 */
    void offerClosed() {
        inbox.offer(CLOSED_SENTINEL);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                inbox.offer(buffer.toString());
                buffer.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            inbox.offer(CLOSED_SENTINEL);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("[Yunzhijia] WebSocket error: {}", error.toString());
            inbox.offer(CLOSED_SENTINEL);
        }
    }
}
