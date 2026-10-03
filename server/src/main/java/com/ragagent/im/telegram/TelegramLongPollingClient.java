package com.ragagent.im.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * Telegram 长轮询入站客户端。
 *
 * <p>getUpdates（offset/timeout=30/allowed_updates=["message"]）→ 逐条交 handler；
 * 出错退避 3 秒后重试；{@link #stop()} 后循环退出。{@code mode=websocket} 的
 * Telegram 渠道走本客户端（Telegram 的缺省接入形态）。</p>
 */
public class TelegramLongPollingClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramLongPollingClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BACKOFF_MS = 3000;
    private static final int POLL_TIMEOUT_SECONDS = 30;

    private final String botToken;
    private final String channelId;
    private final BiConsumer<IncomingMessage, String> handler;
    private final String apiBase;
    private final HttpClient http;
    private volatile int offset;
    private volatile boolean running = true;

    public TelegramLongPollingClient(String botToken, String channelId,
                                     BiConsumer<IncomingMessage, String> handler) {
        this(botToken, channelId, handler, TelegramAdapter.DEFAULT_API_BASE);
    }

    /** 测试用：注入 API 基址。 */
    TelegramLongPollingClient(String botToken, String channelId,
                              BiConsumer<IncomingMessage, String> handler, String apiBase) {
        this.botToken = botToken == null ? "" : botToken;
        this.channelId = channelId;
        this.handler = handler;
        this.apiBase = apiBase == null || apiBase.isEmpty()
                ? TelegramAdapter.DEFAULT_API_BASE : apiBase;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 停止循环。 */
    public void stop() {
        running = false;
    }

    /** 阻塞轮询循环；由工厂放进守护线程跑。 */
    public void start() {
        log.info("[IM] Telegram long polling connecting...");
        while (running) {
            List<JsonNode> updates;
            try {
                updates = getUpdates();
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.error("[Telegram] getUpdates error: {}", e.toString());
                if (!sleep(BACKOFF_MS)) {
                    return;
                }
                continue;
            }
            for (JsonNode update : updates) {
                int updateId = update.path("update_id").asInt(0);
                if (updateId >= offset) {
                    offset = updateId + 1;
                }
                IncomingMessage msg = TelegramAdapter.parseUpdate(update);
                if (msg == null) {
                    continue;
                }
                try {
                    handler.accept(msg, channelId);
                } catch (Exception e) {
                    log.error("[Telegram] Handle message error: {}", e.toString());
                }
            }
        }
    }

    private List<JsonNode> getUpdates() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", offset);
        body.put("timeout", POLL_TIMEOUT_SECONDS);
        body.put("allowed_updates", List.of("message"));

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(apiBase + "/bot" + botToken + "/getUpdates"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 5L))
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        JsonNode apiResp = raw == null || raw.length == 0
                ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
        if (!apiResp.path("ok").asBoolean(false)) {
            throw new IllegalStateException("getUpdates failed: " + apiResp.path("description").asText(""));
        }
        JsonNode result = apiResp.path("result");
        if (!result.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new java.util.ArrayList<>(result.size());
        result.forEach(out::add);
        return out;
    }

    /** 返回 false 表示循环应退出（stop 被调用）。 */
    private boolean sleep(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (running && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.min(100, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return running;
    }
}
