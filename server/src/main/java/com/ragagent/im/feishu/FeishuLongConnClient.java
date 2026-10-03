package com.ragagent.im.feishu;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 飞书 / Lark 长连接（websocket）客户端（协议对齐官方 oapi-sdk-go/v3 的 ws 模块）。
 *
 * <h2>协议（官方 SDK）</h2>
 * <ol>
 *   <li><b>取接入点</b>：{@code POST {domain}/callback/ws/endpoint}，体 {@code {AppID, AppSecret}}，
 *       头 {@code locale: zh}；响应 {@code {code, msg, data:{URL, ClientConfig}}}——
 *       {@code code} 0 成功、1 系统忙、1000040343 内部错误、其余客户端错误；{@code data.URL} 必填；
 *       {@code ClientConfig} 覆盖 {@code ReconnectCount/ReconnectInterval/ReconnectNonce/PingInterval}
 *       （秒）；</li>
 *   <li><b>连 WS</b>：直连该 URL（{@code device_id} / {@code service_id} 从查询串取，
 *       {@code service_id} 进 ping 帧）；非 101 时按 {@code Handshake-Status}/
 *       {@code Handshake-Autherrcode} 头判错；</li>
 *   <li><b>帧</b>：二进制消息 = {@link LarkFrame}（pbbp2）；
 *       {@code method=0} 控制帧（{@code type=pong}，payload 可带新 {@code ClientConfig}）、
 *       {@code method=1} 数据帧（{@code type=event/card} + {@code sum/seq/message_id/trace_id}）；</li>
 *   <li><b>心跳</b>：每 {@code PingInterval} 秒发控制帧 {@code {type:ping, service:<sid>}}；</li>
 *   <li><b>分片</b>：{@code sum>1} 时按 {@code message_id} 攒片（TTL 5 秒）；</li>
 *   <li><b>回执</b>：事件处理完把<b>同一个帧</b>回写，payload = {@code {"code":200|500}}，
 *       并追加 {@code biz_rt}（处理毫秒数）；</li>
 *   <li><b>重连</b>：默认 {@code ReconnectCount=-1}（无限）、{@code ReconnectInterval=120s}、
 *       {@code ReconnectNonce=30}（首次重连前随机抖动 ≤30s）、{@code PingInterval=120s}
 *       （均为缺省值，服务端可在接入点响应里覆盖）。</li>
 * </ol>
 *
 * <h2>实现差异（备案）</h2>
 * <ul>
 *   <li>接入点返回的 WS 地址做 <b>wss + SSRF 校验</b>（SDK 只做 scheme 解析；本仓长连接一贯校验）；</li>
 *   <li>事件 → 统一消息走 {@link LarkEventConverter}（与 webhook 的解析同一语义）；
 *       事件 JSON 与 webhook 回调同形，但长连接分支**不设 threadId**、post **取首图**。</li>
 * </ul>
 */
public class FeishuLongConnClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String GEN_ENDPOINT_URI = "/callback/ws/endpoint";
    static final String DEVICE_ID = "device_id";
    static final String SERVICE_ID = "service_id";
    static final String HEADER_TYPE = "type";
    static final String HEADER_MESSAGE_ID = "message_id";
    static final String HEADER_SUM = "sum";
    static final String HEADER_SEQ = "seq";
    static final String HEADER_BIZ_RT = "biz_rt";
    static final String TYPE_EVENT = "event";
    static final String TYPE_CARD = "card";
    static final String TYPE_PONG = "pong";
    static final long COMBINE_TTL_MS = 5_000L;

    /** ack 出口（生产走 WS，测试注入记录器）。 */
    interface AckSink {
        void send(byte[] frame);
    }

    private final FeishuRegion region;
    private final String appId;
    private final String appSecret;
    private final String domain;
    private final SsrfGuard ssrfGuard;
    private final String channelId;
    private final BiConsumer<IncomingMessage, String> msgHandler;
    private final HttpClient http;

    private volatile int reconnectCount = -1;
    private volatile int reconnectIntervalSeconds = 120;
    private volatile int reconnectNonce = 30;
    private volatile int pingIntervalSeconds = 120;

    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile WebSocket ws;
    private volatile int serviceId;
    private volatile String connId = "";
    private volatile AckSink ackSink = this::sendOverWs;
    private final Map<String, ChunkBuffer> chunks = new ConcurrentHashMap<>();
    private volatile Thread pingThread;
    private volatile Thread loopThread;

    public FeishuLongConnClient(FeishuRegion region, String appId, String appSecret,
                                String domain, SsrfGuard ssrfGuard, String channelId,
                                BiConsumer<IncomingMessage, String> msgHandler) {
        this.region = region == null ? FeishuRegion.FEISHU : region;
        this.appId = appId == null ? "" : appId;
        this.appSecret = appSecret == null ? "" : appSecret;
        String base = domain == null || domain.isBlank()
                ? this.region.openBaseUrl() : domain.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.domain = base;
        this.ssrfGuard = ssrfGuard;
        this.channelId = channelId == null ? "" : channelId;
        this.msgHandler = msgHandler;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** 分片缓冲（5 秒 TTL）。 */
    private static final class ChunkBuffer {
        final byte[][] parts;
        long updatedAt = System.currentTimeMillis();

        ChunkBuffer(int sum) {
            parts = new byte[sum][];
        }
    }

    // ── 生命周期（阻塞循环 + 重连，装配方起守护线程） ────────────────────────

    public void start() throws InterruptedException {
        this.loopThread = Thread.currentThread();
        while (!stopped.get()) {
            boolean connected = false;
            try {
                connect();
                connected = true;
            } catch (Exception e) {
                log.warn("[{}] long connection failed: {}", region.label(), e.toString());
            }
            if (stopped.get()) {
                return;
            }
            // 首次重连随机抖动（对应 ReconnectNonce，单位秒；取不到就跳过）
            if (reconnectNonce > 0) {
                Thread.sleep((long) (Math.random() * reconnectNonce * 1000L));
            }
            int attempt = 0;
            while (!stopped.get()) {
                if (reconnectCount >= 0 && attempt >= reconnectCount) {
                    log.error("[{}] unable to connect to server after {} retries",
                            region.label(), reconnectCount);
                    return;
                }
                attempt++;
                try {
                    connect();
                    connected = true;
                    break;
                } catch (Exception e) {
                    log.warn("[{}] reconnect attempt {} failed: {}", region.label(), attempt,
                            e.toString());
                }
                Thread.sleep(Math.max(reconnectIntervalSeconds, 1) * 1000L);
            }
            if (!connected && stopped.get()) {
                return;
            }
        }
    }

    /** 一次建连：取接入点 → WS 握手 → 起心跳；连接断开后本方法返回。 */
    void connect() throws Exception {
        String url = fetchConnUrl();
        URI uri = URI.create(url);
        if (!"wss".equals(uri.getScheme())) {
            throw new IllegalStateException(region.label() + " long connection URL must be wss://, got "
                    + uri.getScheme() + "://");
        }
        if (ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(url);
        }
        this.connId = queryParam(uri, DEVICE_ID);
        this.serviceId = parseIntSafe(queryParam(uri, SERVICE_ID));

        CountDownLatch closed = new CountDownLatch(1);
        WebSocket socket;
        try {
            socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(uri, new Listener(closed))
                    .get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException(hint(e.getCause()), e.getCause());
        }
        this.ws = socket;
        log.info("[{}] long connection established: channel={} conn={} service={}",
                region.label(), channelId, connId, serviceId);

        startPingLoop();
        closed.await();   // 断开（或被 stop）后返回，交给 start() 的重连逻辑
        closeSocket();
    }

    /** 心跳循环（连接建立后启动）。 */
    private void startPingLoop() {
        Thread previous = pingThread;
        if (previous != null) {
            previous.interrupt();
        }
        Thread thread = new Thread(() -> {
            try {
                while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(Math.max(pingIntervalSeconds, 1) * 1000L);
                    WebSocket socket = ws;
                    if (socket == null) {
                        return;
                    }
                    LarkFrame ping = LarkFrame.ping(serviceId);
                    try {
                        socket.sendBinary(ByteBuffer.wrap(ping.encode()), true)
                                .get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("[{}] ping failed: {}", region.label(), e.toString());
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "im-feishu-ping-" + channelId);
        thread.setDaemon(true);
        thread.start();
        this.pingThread = thread;
    }

    public void stop() {
        stopped.set(true);
        Thread pinger = pingThread;
        if (pinger != null) {
            pinger.interrupt();
        }
        Thread loop = loopThread;
        if (loop != null) {
            loop.interrupt();   // 打断重连退避的睡眠，让装配方的 stop 立即生效
        }
        closeSocket();
    }

    private void closeSocket() {
        WebSocket socket = ws;
        if (socket != null) {
            try {
                socket.abort();
            } catch (RuntimeException e) {
                log.debug("[{}] abort failed: {}", region.label(), e.toString());
            }
            ws = null;
        }
    }

    private static String hint(Throwable cause) {
        return cause == null ? "websocket handshake failed" : cause.toString();
    }

    // ── 接入点 ──────────────────────────────────────────────────────────────

    /** POST {domain}/callback/ws/endpoint（locale: zh）。 */
    String fetchConnUrl() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("AppID", appId);
        body.put("AppSecret", appSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(domain + GEN_ENDPOINT_URI))
                .header("locale", "zh")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("ws endpoint returned " + response.statusCode() + ": "
                    + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
        return parseEndpointResponse(response.body());
    }

    /** 解析接入点响应（code 分支 + 参数覆盖）。 */
    String parseEndpointResponse(byte[] raw) throws Exception {
        JsonNode node = MAPPER.readTree(raw == null ? new byte[0] : raw);
        int code = node.path("code").asInt(0);
        String msg = node.path("msg").asText("");
        if (code != 0) {
            throw new IllegalStateException("ws endpoint error: code=" + code
                    + (msg.isEmpty() ? "" : " msg=" + msg));
        }
        String url = node.path("data").path("URL").asText("");
        if (url.isEmpty()) {
            throw new IllegalStateException("ws endpoint is null");
        }
        JsonNode config = node.path("data").path("ClientConfig");
        if (!config.isMissingNode() && !config.isNull()) {
            configure(config);
        }
        return url;
    }

    /** 服务端可覆盖重连与心跳参数（秒）。 */
    void configure(JsonNode config) {
        if (config.hasNonNull("ReconnectCount")) {
            reconnectCount = config.path("ReconnectCount").asInt();
        }
        if (config.hasNonNull("ReconnectInterval")) {
            reconnectIntervalSeconds = config.path("ReconnectInterval").asInt();
        }
        if (config.hasNonNull("ReconnectNonce")) {
            reconnectNonce = config.path("ReconnectNonce").asInt();
        }
        if (config.hasNonNull("PingInterval")) {
            pingIntervalSeconds = config.path("PingInterval").asInt();
        }
    }

    static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            if (name.equals(key)) {
                return idx < 0 ? "" : pair.substring(idx + 1);
            }
        }
        return "";
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── 帧处理 ──────────────────────────────────────────────────────────────

    /** 处理一帧二进制消息（纯逻辑，可单测）。 */
    void handleFrame(byte[] raw) {
        LarkFrame frame;
        try {
            frame = LarkFrame.decode(raw);
        } catch (RuntimeException e) {
            log.warn("[{}] decode frame failed: {}", region.label(), e.toString());
            return;
        }
        switch (frame.method) {
            case LarkFrame.METHOD_CONTROL -> handleControlFrame(frame);
            case LarkFrame.METHOD_DATA -> handleDataFrame(frame);
            default -> {
                // 未知 method：忽略
            }
        }
    }

    /** pong 的 payload 可带新的 ClientConfig。 */
    void handleControlFrame(LarkFrame frame) {
        if (!TYPE_PONG.equals(frame.header(HEADER_TYPE))) {
            return;
        }
        if (frame.payload == null || frame.payload.length == 0) {
            return;
        }
        try {
            configure(MAPPER.readTree(frame.payload));
        } catch (Exception e) {
            log.warn("[{}] unmarshal client config failed: {}", region.label(), e.toString());
        }
    }

    /** 分片合包 → 事件转换 → 同帧回执（带 biz_rt）。 */
    void handleDataFrame(LarkFrame frame) {
        int sum = frame.intHeader(HEADER_SUM);
        int seq = frame.intHeader(HEADER_SEQ);
        String messageId = frame.header(HEADER_MESSAGE_ID);
        String type = frame.header(HEADER_TYPE);

        byte[] payload = frame.payload == null ? new byte[0] : frame.payload;
        if (sum > 1) {
            payload = combine(messageId, sum, seq, payload);
            if (payload == null) {
                return;
            }
        }

        if (!TYPE_EVENT.equals(type)) {
            // card 帧直接忽略（不走事件分发）
            return;
        }

        long start = System.currentTimeMillis();
        int code = 200;
        try {
            IncomingMessage message = LarkEventConverter.convert(region, payload);
            if (message != null && msgHandler != null) {
                msgHandler.accept(message, channelId);
            }
        } catch (Exception e) {
            log.error("[{}] handle event failed: {}", region.label(), e.toString());
            code = 500;
        }
        long elapsed = System.currentTimeMillis() - start;

        LarkFrame ack = LarkFrame.decode(frame.encode());
        ack.addHeader(HEADER_BIZ_RT, String.valueOf(elapsed));
        ack.payload = ("{\"code\":" + code + "}").getBytes(StandardCharsets.UTF_8);
        ackSink.send(ack.encode());
    }

    /** 按 message_id 攒片，齐了拼起来并清缓存。 */
    byte[] combine(String messageId, int sum, int seq, byte[] data) {
        purgeChunks();
        ChunkBuffer buffer = chunks.get(messageId);
        if (buffer == null) {
            buffer = new ChunkBuffer(sum);
            if (seq >= 0 && seq < sum) {
                buffer.parts[seq] = data;
            }
            chunks.put(messageId, buffer);
            return null;
        }
        if (seq >= 0 && seq < buffer.parts.length) {
            buffer.parts[seq] = data;
        }
        buffer.updatedAt = System.currentTimeMillis();
        int capacity = 0;
        for (byte[] part : buffer.parts) {
            if (part == null || part.length == 0) {
                return null;
            }
            capacity += part.length;
        }
        byte[] joined = new byte[capacity];
        int offset = 0;
        for (byte[] part : buffer.parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        chunks.remove(messageId);
        return joined;
    }

    private void purgeChunks() {
        long cutoff = System.currentTimeMillis() - COMBINE_TTL_MS;
        chunks.entrySet().removeIf(e -> e.getValue().updatedAt < cutoff);
    }

    private void sendOverWs(byte[] frame) {
        WebSocket socket = ws;
        if (socket == null) {
            return;
        }
        try {
            socket.sendBinary(ByteBuffer.wrap(frame), true).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[{}] ack send failed: {}", region.label(), e.toString());
        }
    }

    /** 供测试注入 ack 记录器。 */
    void setAckSink(AckSink sink) {
        this.ackSink = sink;
    }

    /** 供测试观察当前参数。 */
    Map<String, Integer> connectionParams() {
        Map<String, Integer> params = new HashMap<>();
        params.put("reconnectCount", reconnectCount);
        params.put("reconnectIntervalSeconds", reconnectIntervalSeconds);
        params.put("reconnectNonce", reconnectNonce);
        params.put("pingIntervalSeconds", pingIntervalSeconds);
        return params;
    }

    private final class Listener implements WebSocket.Listener {
        private final CountDownLatch closed;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        Listener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data,
                                                               boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            buffer.writeBytes(chunk);
            if (last) {
                byte[] frame = buffer.toByteArray();
                buffer.reset();
                handleFrame(frame);
            }
            socket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int statusCode,
                                                              String reason) {
            log.info("[{}] long connection closed: status={} reason={}", region.label(),
                    statusCode, reason);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("[{}] long connection error: {}", region.label(), error.toString());
            closed.countDown();
        }
    }
}
