package com.ragagent.im.qqbot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * QQ 机器人网关长连接（对照 Go {@code internal/im/qqbot/longconn.go} L18-199）。
 *
 * <p>握手照 Go：连上收 {@code op=10 hello}（{@code heartbeat_interval} 缺省 45000ms）→
 * 发 {@code op=2 identify}（{@code token: "QQBot <token>"}、
 * {@code intents: 1<<25}、{@code shard: [0,1]}）→ 心跳线程按间隔发
 * {@code op=1}（{@code d} 为最近一次 {@code s}，无则 null）；{@code op=0 dispatch}
 * 的事件交 {@link QqBotAdapter#parseGatewayPayload}；{@code op=7/9} 视为"重连"；
 * 断线按 {@code attempt} 秒退避（上限 30 秒）重连。</p>
 */
public class QqBotGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(QqBotGatewayClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final int OP_DISPATCH = 0;
    static final int OP_HEARTBEAT = 1;
    static final int OP_IDENTIFY = 2;
    static final int OP_RECONNECT = 7;
    static final int OP_INVALID_SESSION = 9;
    static final int OP_HELLO = 10;
    static final int OP_HEARTBEAT_ACK = 11;
    static final int INTENT_GROUP_AND_C2C = 1 << 25;
    static final int DEFAULT_HEARTBEAT_INTERVAL_MS = 45000;
    static final long MAX_RECONNECT_DELAY_MS = 30_000;

    private final QqBotClient client;
    private final String channelId;
    private final BiConsumer<IncomingMessage, String> handler;
    private final HttpClient http;

    private volatile boolean closed;
    private volatile WebSocket socket;
    private volatile Long seq;
    private volatile Thread heartbeatThread;

    public QqBotGatewayClient(QqBotClient client, String channelId,
                              BiConsumer<IncomingMessage, String> handler) {
        this.client = client;
        this.channelId = channelId;
        this.handler = handler;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 对照 {@code reconnectDelay}：attempt 秒、上限 30 秒。 */
    static long reconnectDelayMs(int attempt) {
        if (attempt <= 0) {
            return 1000;
        }
        return Math.min(attempt * 1000L, MAX_RECONNECT_DELAY_MS);
    }

    /** 对照 {@code Stop}：置关闭标记并断开。 */
    public void stop() {
        closed = true;
        Thread hb = heartbeatThread;
        if (hb != null) {
            hb.interrupt();
        }
        WebSocket ws = socket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "stopped").join();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
        }
    }

    /** 阻塞重连循环（对照 {@code Start}）；由工厂放进守护线程跑。 */
    public void start() {
        log.info("[IM] QQBot WebSocket connecting...");
        int attempt = 0;
        while (!closed) {
            try {
                connectAndRun();
                attempt = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                attempt++;
                long delay = reconnectDelayMs(attempt);
                log.warn("[QQBot] connection lost: {}, reconnecting in {}ms", e.toString(), delay);
                if (!sleep(delay)) {
                    return;
                }
            }
        }
    }

    private void connectAndRun() throws Exception {
        String gatewayUrl = client.gatewayUrl();
        CountDownLatch closedLatch = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        socket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(gatewayUrl), new Listener(closedLatch, failure))
                .join();
        closedLatch.await();
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0].toString(), failure[0]);
        }
        if (!closed) {
            throw new IllegalStateException("qqbot gateway closed");
        }
    }

    /** 处理一帧（对照 {@code connectAndRun} 的读循环）。 */
    private void handleFrame(String raw, WebSocket ws) throws Exception {
        JsonNode payload = MAPPER.readTree(raw);
        if (payload.hasNonNull("s")) {
            seq = payload.get("s").asLong();
        }
        int op = payload.path("op").asInt(0);
        switch (op) {
            case OP_HELLO:
                handleHello(payload.path("d"), ws);
                return;
            case OP_DISPATCH:
                try {
                    IncomingMessage msg = QqBotAdapter.parseGatewayPayload(payload);
                    if (msg != null) {
                        handler.accept(msg, channelId);
                    }
                } catch (Exception e) {
                    log.warn("[QQBot] parse event failed: {}", e.toString());
                }
                return;
            case OP_RECONNECT:
            case OP_INVALID_SESSION:
                throw new IllegalStateException("gateway requested reconnect op=" + op);
            case OP_HEARTBEAT_ACK:
            default:
                // 心跳回执与未知 op：忽略（照 Go 的空分支）
        }
    }

    /** 对照 {@code handleHello}：identify + 起心跳线程。 */
    private void handleHello(JsonNode data, WebSocket ws) throws Exception {
        int interval = data.path("heartbeat_interval").asInt(0);
        if (interval <= 0) {
            interval = DEFAULT_HEARTBEAT_INTERVAL_MS;
        }
        String token = client.accessToken();

        Map<String, Object> identify = new LinkedHashMap<>();
        identify.put("token", "QQBot " + token);
        identify.put("intents", INTENT_GROUP_AND_C2C);
        identify.put("shard", List.of(0, 1));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("op", OP_IDENTIFY);
        frame.put("d", identify);
        ws.sendText(MAPPER.writeValueAsString(frame), true).join();

        int heartbeatInterval = interval;
        Thread thread = new Thread(() -> heartbeatLoop(heartbeatInterval, ws),
                "im-qqbot-heartbeat-" + channelId);
        thread.setDaemon(true);
        heartbeatThread = thread;
        thread.start();
    }

    /** 对照 {@code heartbeatLoop}：按间隔发 {@code op=1}，发送失败即退出。 */
    private void heartbeatLoop(int intervalMs, WebSocket ws) {
        while (!closed) {
            if (!sleep(intervalMs)) {
                return;
            }
            try {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("op", OP_HEARTBEAT);
                frame.put("d", seq); // 最近一次 s；无则 null（照 Go 的 *int64）
                ws.sendText(MAPPER.writeValueAsString(frame), true).join();
            } catch (Exception e) {
                // 序列化失败或连接已断：心跳线程退出（照 Go 的 err != nil → return）
                return;
            }
        }
    }

    /** 返回 false 表示循环应退出。 */
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
        private final CountDownLatch closedLatch;
        private final Throwable[] failure;
        private final StringBuilder buffer = new StringBuilder();

        Listener(CountDownLatch closedLatch, Throwable[] failure) {
            this.closedLatch = closedLatch;
            this.failure = failure;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                try {
                    handleFrame(raw, ws);
                } catch (Exception e) {
                    failure[0] = e;
                    try {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").join();
                    } catch (RuntimeException ignored) {
                        // 尽力而为
                    }
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closedLatch.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (failure[0] == null) {
                failure[0] = error;
            }
            closedLatch.countDown();
        }
    }

    /** 供测试断言用（UTF-8 常量）。 */
    static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
}
