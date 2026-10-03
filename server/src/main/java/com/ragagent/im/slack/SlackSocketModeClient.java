package com.ragagent.im.slack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.SlackAdapterCore;

/**
 * Slack Socket Mode 长连接。
 *
 * <p>流程（Socket Mode 协议）：{@code POST apps.connections.open}（app 级 token）拿
 * WSS URL → 连上后收 envelope：<b>先 ack</b>（回 {@code {"envelope_id": …}}）再处理
 * {@code events_api} 的 payload（交给 {@link SlackAdapterCore#parseCallback} 解析）→
 * {@code disconnect} 收帧即关连接、外层循环重连（退避 3 秒）。</p>
 */
public class SlackSocketModeClient {

    private static final Logger log = LoggerFactory.getLogger(SlackSocketModeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long RECONNECT_BACKOFF_MS = 3000;

    private final String appToken;
    private final String channelId;
    private final String apiBase;
    private final BiConsumer<IncomingMessage, String> handler;
    private final HttpClient http;
    private volatile boolean running = true;
    private volatile WebSocket socket;

    public SlackSocketModeClient(String appToken, String channelId,
                                 BiConsumer<IncomingMessage, String> handler) {
        this(appToken, channelId, handler, SlackAdapter.DEFAULT_API_BASE);
    }

    /** 测试用：注入 API 基址。 */
    SlackSocketModeClient(String appToken, String channelId,
                          BiConsumer<IncomingMessage, String> handler, String apiBase) {
        this.appToken = appToken == null ? "" : appToken;
        this.channelId = channelId;
        this.handler = handler;
        this.apiBase = apiBase == null || apiBase.isEmpty()
                ? SlackAdapter.DEFAULT_API_BASE : apiBase;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 停止循环并关连接。 */
    public void stop() {
        running = false;
        WebSocket ws = socket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "stopped").join();
            } catch (RuntimeException ignored) {
                // 尽力而为：连接可能已断
            }
        }
    }

    /** 阻塞的重连循环；由工厂放进守护线程跑。 */
    public void start() {
        log.info("[IM] Slack WebSocket connecting...");
        while (running) {
            try {
                String url = openConnection();
                CountDownLatch closed = new CountDownLatch(1);
                socket = http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .buildAsync(URI.create(url), new Listener(closed))
                        .join();
                log.info("[IM] Slack WebSocket connected successfully");
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.error("[Slack] Connection failed. Retrying later... ({})", e.toString());
            }
            if (!running) {
                return;
            }
            sleep(RECONNECT_BACKOFF_MS);
        }
    }

    /** 握手：POST {@code apps.connections.open} → 取 {@code url}。 */
    private String openConnection() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/apps.connections.open"))
                .header("Authorization", "Bearer " + appToken)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        JsonNode node = raw == null || raw.length == 0
                ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
        if (!node.path("ok").asBoolean(false)) {
            throw new IllegalStateException("apps.connections.open failed: "
                    + node.path("error").asText("unknown_error"));
        }
        return node.path("url").asText("");
    }

    /** 处理一帧（Events 循环 + Ack + handleEvent）。 */
    private void handleFrame(String payload, WebSocket ws) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            String envelopeId = node.path("envelope_id").asText("");
            if (!envelopeId.isEmpty()) {
                // 先 Ack 再处理
                ws.sendText("{\"envelope_id\":\"" + envelopeId + "\"}", true).join();
            }
            String type = node.path("type").asText("");
            if ("disconnect".equals(type)) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect").join();
                return;
            }
            if (!"events_api".equals(type)) {
                return;
            }
            byte[] eventBytes = MAPPER.writeValueAsBytes(node.path("payload"));
            IncomingMessage msg = SlackAdapterCore.parseCallback(eventBytes);
            if (msg != null) {
                handler.accept(msg, channelId);
            }
        } catch (Exception e) {
            log.error("[Slack] Handle message error: {}", e.toString());
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final CountDownLatch closed;
        private final StringBuilder buffer = new StringBuilder();

        Listener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                handleFrame(payload, ws);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("[Slack] WebSocket error: {}", error.toString());
            closed.countDown();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供测试断言（连接地址）。 */
    static String connectionRequestPath() {
        return "/apps.connections.open";
    }

    /** UTF-8 常量占位，避免未用导入。 */
    static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
}
