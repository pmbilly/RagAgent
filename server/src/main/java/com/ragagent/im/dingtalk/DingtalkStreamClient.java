package com.ragagent.im.dingtalk;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 钉钉 Stream 模式长连接（协议对齐官方 dingtalk-stream-sdk-go）。
 *
 * <h2>协议（钉钉 Stream SDK）</h2>
 * <ol>
 *   <li><b>取接入点</b>：{@code POST /v1.0/gateway/connections/open}
 *       {@code {clientId, clientSecret, subscriptions:[{type,topic}], ua, localIp, extras}}
 *       → {@code {endpoint, ticket}}（两者皆空即失败）；</li>
 *   <li><b>连 WS</b>：{@code {endpoint}?ticket={ticket}}（无额外头）；</li>
 *   <li><b>数据帧</b>（JSON）：{@code {specVersion, type, time, headers:{topic, contentType,
 *       messageId, time}, data}}；订阅三类：{@code SYSTEM/ping}、{@code SYSTEM/disconnect}、
 *       {@code CALLBACK//v1.0/im/bot/messages/get}（机器人消息，{@code data} 直接是回调 JSON）；</li>
 *   <li><b>回执</b>：{@code {code, headers:{contentType:"application/json", messageId},
 *       message, data}}——普通成功 200；ping 回 {@code 200 + message "ok" + data 原样回显}；
 *       无处理器/未知 topic 404；处理器异常 500；<b>disconnect 先回执再关连接</b>；</li>
 *   <li><b>心跳</b>：每 120s 发 <b>WS 控制帧 ping</b>，5s 内无 pong 即关连接；</li>
 *   <li><b>分流</b>：SYSTEM 帧在消费线程上同步处理（控制帧不被慢处理器饿死），
 *       CALLBACK 帧交工作池。</li>
 * </ol>
 *
 * <h2>实现差异（备案）</h2>
 * <ul>
 *   <li>回调并发用固定大小工作池限制（语义等价：回调并发受限、
 *       控制帧优先）；</li>
 *   <li>接入点返回的 WS 地址做 wss + SSRF 校验（SDK 本身不校验；本仓长连接一贯校验，
 *       与 qqbot 网关/wecom 长连接同规）；</li>
 *   <li>重连退避由本类承担（SDK 内部循环不含）。</li>
 * </ul>
 */
public class DingtalkStreamClient {

    private static final Logger log = LoggerFactory.getLogger(DingtalkStreamClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 机器人消息统一回调 topic。 */
    public static final String BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get";
    public static final String DEFAULT_OPEN_API_HOST = "https://api.dingtalk.com";
    public static final String ENDPOINT_PATH = "/v1.0/gateway/connections/open";
    static final String SYSTEM_TYPE = "SYSTEM";
    static final String CALLBACK_TYPE = "CALLBACK";
    static final String TOPIC_PING = "ping";
    static final String TOPIC_DISCONNECT = "disconnect";
    public static final long KEEP_ALIVE_IDLE_MS = 120_000L;
    public static final long PING_WAIT_MS = 5_000L;
    static final long MAX_BACKOFF_SECONDS = 30;
    private static final String CLOSED_SENTINEL = "\u0000closed";

    /** ack 出口（生产走 WS，测试注入记录器）。 */
    interface AckSink {
        void send(String json);
    }

    /** 接入点响应（endpoint + ticket）。 */
    record Endpoint(String endpoint, String ticket) {
    }

    private final String clientId;
    private final String clientSecret;
    private final String openApiHost;
    private final SsrfGuard ssrfGuard;
    private final String channelId;
    private final BiConsumer<IncomingMessage, String> msgHandler;
    private final HttpClient http;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "im-dingtalk-worker");
        t.setDaemon(true);
        return t;
    });

    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile WebSocket ws;
    private volatile AckSink ackSink = this::sendOverWs;
    /** 回调是否交工作池（测试可关，保证确定性）。 */
    volatile boolean asyncCallbacks = true;

    public DingtalkStreamClient(String clientId, String clientSecret, String openApiHost,
                                SsrfGuard ssrfGuard, String channelId,
                                BiConsumer<IncomingMessage, String> msgHandler) {
        this.clientId = clientId == null ? "" : clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        String host = openApiHost == null || openApiHost.isBlank()
                ? DEFAULT_OPEN_API_HOST : openApiHost.trim();
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        this.openApiHost = host;
        this.ssrfGuard = ssrfGuard;
        this.channelId = channelId == null ? "" : channelId;
        this.msgHandler = msgHandler;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── 生命周期（阻塞循环 + 退避重连；装配方起守护线程） ────────────────────

    public void start() {
        int attempt = 0;
        while (!stopped.get()) {
            try {
                connectOnce();
                attempt = 0;
                consumeLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[DingTalk] Stream connect failed: {}", e.toString());
            }
            if (stopped.get()) {
                return;
            }
            long backoff = Math.min(++attempt, MAX_BACKOFF_SECONDS);
            log.info("[DingTalk] Stream reconnecting in {}s", backoff);
            try {
                Thread.sleep(backoff * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 建连一次（取接入点 + WS 握手）。 */
    void connectOnce() throws Exception {
        Endpoint endpoint = openConnectionEndpoint();
        URI uri = URI.create(endpoint.endpoint() + "?ticket="
                + URLEncoder.encode(endpoint.ticket(), StandardCharsets.UTF_8));
        WebSocket socket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(uri, new Listener())
                .get(30, TimeUnit.SECONDS);
        this.ws = socket;
        log.info("[DingTalk] Stream connected: channel={} session={}", channelId,
                endpoint.ticket());
    }

    /** 消费帧：SYSTEM 同步、CALLBACK 交工作池；心跳线程每 120s 发 WS ping。 */
    void consumeLoop() throws InterruptedException {
        CountDownLatch heartbeatDone = new CountDownLatch(1);
        Thread heartbeat = new Thread(() -> {
            try {
                while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(KEEP_ALIVE_IDLE_MS);
                    WebSocket socket = ws;
                    if (socket == null || socket.isOutputClosed()) {
                        return;
                    }
                    try {
                        socket.sendPing(ByteBuffer.wrap(new byte[0]))
                                .get(PING_WAIT_MS, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        log.warn("[DingTalk] ping timeout, closing connection: {}", e.toString());
                        socket.abort();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                heartbeatDone.countDown();
            }
        }, "im-dingtalk-heartbeat-" + channelId);
        heartbeat.setDaemon(true);
        heartbeat.start();

        try {
            while (true) {
                String frame = inbox.poll(150, TimeUnit.SECONDS);
                if (frame == null) {
                    continue;
                }
                if (CLOSED_SENTINEL.equals(frame)) {
                    return;
                }
                try {
                    dispatch(frame);
                } catch (Exception e) {
                    log.warn("[DingTalk] Stream frame handling error: {}", e.toString());
                }
            }
        } finally {
            heartbeat.interrupt();
            try {
                heartbeatDone.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void stop() {
        stopped.set(true);
        WebSocket socket = ws;
        if (socket != null) {
            try {
                socket.abort();
            } catch (RuntimeException e) {
                log.debug("[DingTalk] abort failed: {}", e.toString());
            }
        }
        workers.shutdownNow();
    }

    // ── 帧分流 ──────────────────────────────────────────────────────────────

    /** 处理一帧（纯逻辑，可单测）：SYSTEM 同步；CALLBACK 交池；其余回 404。 */
    void dispatch(String raw) throws Exception {
        JsonNode frame = MAPPER.readTree(raw);
        String type = frame.path("type").asText("");
        String topic = frame.path("headers").path("topic").asText("");

        if (SYSTEM_TYPE.equals(type)) {
            if (TOPIC_PING.equals(topic)) {
                // pong：200 + message "ok" + data 原样回显
                ackSink.send(buildAck(frame, 200, "ok", frame.path("data").asText("")));
                return;
            }
            if (TOPIC_DISCONNECT.equals(topic)) {
                // 先回执再关连接（在断连回调里关会让 ACK 写失败）
                ackSink.send(buildAck(frame, 200, "", ""));
                closeOwnedConnection();
                return;
            }
            ackSink.send(buildAck(frame, 404, "", ""));
            return;
        }

        if (CALLBACK_TYPE.equals(type) && BOT_MESSAGE_TOPIC.equals(topic)) {
            if (asyncCallbacks) {
                workers.submit(() -> handleBotFrameSafely(frame));
            } else {
                handleBotFrameSafely(frame);
            }
            return;
        }
        ackSink.send(buildAck(frame, 404, "", ""));
    }

    private void handleBotFrameSafely(JsonNode frame) {
        try {
            handleBotFrame(frame);
        } catch (Exception e) {
            log.error("[DingTalk] bot frame failed: {}", e.toString());
        }
    }

    /** 机器人回调：{@code data} 是回调 JSON；成功后回 200、失败回 500。 */
    void handleBotFrame(JsonNode frame) {
        try {
            IncomingMessage msg = streamToIncoming(frame, clientId);
            if (msgHandler != null) {
                msgHandler.accept(msg, channelId);
            }
            ackSink.send(buildAck(frame, 200, "", ""));
        } catch (Exception e) {
            ackSink.send(buildAck(frame, 500, e.getMessage() == null ? "" : e.getMessage(), ""));
        }
    }

    /** 回执帧：messageId 回填请求的。 */
    static String buildAck(JsonNode frame, int code, String message, String data) {
        ObjectNode ack = MAPPER.createObjectNode();
        ack.put("code", code);
        ObjectNode headers = MAPPER.createObjectNode();
        headers.put("contentType", "application/json");
        headers.put("messageId", frame.path("headers").path("messageId").asText(""));
        ack.set("headers", headers);
        ack.put("message", message == null ? "" : message);
        ack.put("data", data == null ? "" : data);
        return ack.toString();
    }

    /**
     * Stream 帧 → 统一消息：与 webhook 同一解析，
     * 但机器人码回落 clientId（Stream 载荷不带 robotCode；企业内部机器人的 clientId 即 robotCode）。
     */
    static IncomingMessage streamToIncoming(JsonNode frame, String fallbackRobotCode)
            throws Exception {
        String data = frame.path("data").asText("");
        JsonNode payload = data.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(data);
        IncomingMessage msg = DingtalkAdapter.parseCallbackMessage(payload);
        if (!msg.fileKey.isEmpty()) {
            msg.extra.put("robot_code", fallbackRobotCode);
        }
        return msg;
    }

    // ── 接入点 ──────────────────────────────────────────────────────────────

    /** 订阅三类 + ua/localIp/extras。 */
    String buildEndpointRequest() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("clientId", clientId);
        body.put("clientSecret", clientSecret);
        ArrayNode subscriptions = MAPPER.createArrayNode();
        subscriptions.add(subscription(SYSTEM_TYPE, TOPIC_PING));
        subscriptions.add(subscription(SYSTEM_TYPE, TOPIC_DISCONNECT));
        subscriptions.add(subscription(CALLBACK_TYPE, BOT_MESSAGE_TOPIC));
        body.set("subscriptions", subscriptions);
        body.put("ua", "weknora-java");
        body.put("localIp", firstLanIp());
        body.set("extras", MAPPER.createObjectNode());
        return MAPPER.writeValueAsString(body);
    }

    private static ObjectNode subscription(String type, String topic) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("topic", topic);
        return node;
    }

    /** 取本机局域网 IP：取不到就回落空串。 */
    static String firstLanIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "";
        }
    }

    Endpoint openConnectionEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(openApiHost + ENDPOINT_PATH))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(buildEndpointRequest(),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("gateway connections/open returned "
                    + response.statusCode() + ": "
                    + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
        return parseEndpointResponse(response.body());
    }

    /** 解析接入点响应 + 校验（非空 + wss/SSRF 规则）。 */
    Endpoint parseEndpointResponse(byte[] body) throws Exception {
        JsonNode node = MAPPER.readTree(body == null ? new byte[0] : body);
        String endpoint = node.path("endpoint").asText("");
        String ticket = node.path("ticket").asText("");
        if (endpoint.isEmpty() || ticket.isEmpty()) {
            throw new IllegalStateException("gateway response missing endpoint or ticket");
        }
        URI uri = URI.create(endpoint);
        if (!"wss".equals(uri.getScheme())) {
            throw new IllegalStateException("dingtalk stream endpoint must use wss://, got "
                    + uri.getScheme() + "://");
        }
        if (ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(endpoint);
        }
        return new Endpoint(endpoint, ticket);
    }

    // ── WS 细节 ─────────────────────────────────────────────────────────────

    private void sendOverWs(String json) {
        WebSocket socket = ws;
        if (socket == null) {
            return;
        }
        try {
            socket.sendText(json, true).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[DingTalk] ack send failed: {}", e.toString());
        }
    }

    private void closeOwnedConnection() {
        WebSocket socket = ws;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect")
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(e -> {
                        socket.abort();
                        return null;
                    });
        }
        inbox.offer(CLOSED_SENTINEL);
    }

    /** 供测试注入 ack 记录器。 */
    void setAckSink(AckSink sink) {
        this.ackSink = sink;
    }

    /** 供测试投递帧（替代 WS 收包）。 */
    void offerFrame(String raw) {
        inbox.offer(raw);
    }

    String openApiHost() {
        return openApiHost;
    }

    /** 供测试触发一次消费循环退出。 */
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
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            // 显式回 pong（帧内容原样回显）
            socket.sendPong(message == null ? ByteBuffer.wrap(new byte[0]) : message);
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            log.info("[DingTalk] Stream closed: status={} reason={}", statusCode, reason);
            inbox.offer(CLOSED_SENTINEL);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("[DingTalk] Stream error: {}", error.toString());
            inbox.offer(CLOSED_SENTINEL);
        }
    }
}
