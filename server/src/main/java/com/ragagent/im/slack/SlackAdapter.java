package com.ragagent.im.slack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImAdapterVerify;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.runtime.SlackAdapterCore;

/**
 * Slack 适配器（对照 Go {@code internal/im/slack/adapter.go} L28-343）。
 *
 * <p><b>入站</b>委托已翻译的 {@link SlackAdapterCore}（URL verification 挑战 + 事件解析）
 * 与 {@link ImAdapterVerify#slackExpectedSignature}（v0 签名）；<b>出站</b>按 Slack Web API
 * 直连（Go 用 slack-go SDK）：{@code chat.postMessage} / {@code chat.update} /
 * {@code files.info} + 带 Bearer 的私有文件下载。</p>
 *
 * <h2>照抄的语义</h2>
 * <ul>
 *   <li>{@code sendReply} 的文本是 <b>reply.content 原样</b>（Slack 这一支不做
 *       {@code FormatIMDisplayContent}——与 telegram 不同，照 Go）；{@code thread_ts}
 *       取 {@code incoming.messageId}；channel 回落 user_id（DM）；</li>
 *   <li>流：{@code startStream} 发"正在思考..." → {@code {channel}:{ts}}；
 *       {@code update} = {@code chat.update}（<b>无节流</b>，与 telegram 不同）；
 *       {@code finalize} 就是 update（照 Go）；{@code endStream} 用累积内容再 update 一次；</li>
 *   <li>验签：{@code X-Slack-Signature} = {@code v0=HMAC(secret, "v0:ts:body")}，
 *       且时间戳须在 5 分钟窗内（照 slack-go 的 {@code Ensure()}）；secret 空则免验。</li>
 * </ul>
 *
 * <h2>与 Go 的一处措辞差异（备案）</h2>
 * <p>失败信息的包装层照 Go（"slack post message: …"），内层用的是 Slack 的
 * {@code error} 字段（Go 用 SDK 自己的措辞）。</p>
 */
public class SlackAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.StreamSender, AdapterInterfaces.FileDownloader {

    private static final Logger log = LoggerFactory.getLogger(SlackAdapter.class);

    public static final String DEFAULT_API_BASE = "https://slack.com/api";
    /** 签名时间戳容忍窗（照 slack-go Ensure 的 5 分钟）。 */
    static final long SIGNATURE_MAX_AGE_SECONDS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 全局流表（对照 Go 包级 {@code slackStreams}），key = {@code {channel}:{ts}}。 */
    static final Map<String, StreamState> STREAMS = new ConcurrentHashMap<>();

    /** 一条流的状态（对照 Go {@code slackStreamState}）。 */
    static final class StreamState {
        final String channel;
        final String ts;
        volatile String content = "";

        StreamState(String channel, String ts) {
            this.channel = channel;
            this.ts = ts;
        }
    }

    private final String botToken;
    private final String signingSecret;
    private final String apiBase;
    private final HttpClient http;

    public SlackAdapter(String botToken, String signingSecret) {
        this(botToken, signingSecret, DEFAULT_API_BASE);
    }

    /** 测试用：注入 API 基址。 */
    SlackAdapter(String botToken, String signingSecret, String apiBase) {
        this.botToken = botToken == null ? "" : botToken;
        this.signingSecret = signingSecret == null ? "" : signingSecret;
        this.apiBase = apiBase == null || apiBase.isEmpty() ? DEFAULT_API_BASE : apiBase;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── Adapter（入站委托核心） ─────────────────────────────────────────────

    @Override
    public String platform() {
        return ImTypes.PLATFORM_SLACK;
    }

    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        if (signingSecret.isEmpty()) {
            return null;
        }
        String timestamp = exchange.header("X-Slack-Request-Timestamp");
        String signature = exchange.header("X-Slack-Signature");
        long epoch;
        try {
            epoch = Long.parseLong(timestamp == null ? "" : timestamp.trim());
        } catch (NumberFormatException e) {
            return new AdapterInterfaces.VerifyException("verify signature: invalid timestamp");
        }
        if (Math.abs(Instant.now().getEpochSecond() - epoch) > SIGNATURE_MAX_AGE_SECONDS) {
            return new AdapterInterfaces.VerifyException("verify signature: timestamp too old");
        }
        String expected = ImAdapterVerify.slackExpectedSignature(signingSecret, timestamp,
                exchange.body());
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = (signature == null ? "" : signature).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes)
                ? null : new AdapterInterfaces.VerifyException("verify signature: invalid signature");
    }

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        return SlackAdapterCore.parseCallback(exchange.body());
    }

    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return SlackAdapterCore.handleURLVerification(exchange);
    }

    // ── 出站 ────────────────────────────────────────────────────────────────

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channelOf(incoming));
        body.put("text", reply.content); // 照 Go：Slack 这支不做展示格式化
        if (incoming.messageId != null && !incoming.messageId.isEmpty()) {
            body.put("thread_ts", incoming.messageId);
        }
        callApi("chat.postMessage", body, "slack post message");
    }

    @Override
    public String startStream(IncomingMessage incoming) throws Exception {
        String channel = channelOf(incoming);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("text", "正在思考...");
        if (incoming.messageId != null && !incoming.messageId.isEmpty()) {
            body.put("thread_ts", incoming.messageId);
        }
        JsonNode result = callApi("chat.postMessage", body, "slack start stream");
        String ts = result.path("ts").asText("");
        String streamId = channel + ":" + ts;
        STREAMS.put(streamId, new StreamState(channel, ts));
        log.info("[Slack] Streaming started: stream_id={}", streamId);
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
        state.content = fullContent;
        try {
            updateMessage(state.channel, state.ts, fullContent);
        } catch (Exception e) {
            // 照 Go：更新失败只告警（流式期间的一次编辑失败不该中断回答）
            log.warn("[Slack] Failed to update stream content: {}", e.toString());
        }
    }

    @Override
    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        updateStreamContent(incoming, streamId, finalContent);
    }

    @Override
    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        StreamState state = STREAMS.remove(streamId);
        if (state == null) {
            return;
        }
        try {
            updateMessage(state.channel, state.ts, state.content);
        } catch (Exception e) {
            log.warn("[Slack] Failed to end stream: {}", e.toString());
        }
        log.info("[Slack] Streaming ended: stream_id={}", streamId);
    }

    private void updateMessage(String channel, String ts, String text) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("ts", ts);
        body.put("text", text);
        callApi("chat.update", body, "slack update message");
    }

    private static String channelOf(IncomingMessage incoming) {
        if (incoming.chatId != null && !incoming.chatId.isEmpty()) {
            return incoming.chatId;
        }
        return incoming.userId == null ? "" : incoming.userId;
    }

    // ── FileDownloader ──────────────────────────────────────────────────────

    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()) {
            throw new IllegalArgumentException("file_key is required");
        }
        String downloadUrl = msg.extra == null ? "" : msg.extra.getOrDefault("url_private_download", "");
        if (downloadUrl.isEmpty()) {
            JsonNode file = callApi("files.info", Map.of("file", msg.fileKey), "get file info");
            downloadUrl = file.path("file").path("url_private_download").asText("");
        }
        if (downloadUrl.isEmpty()) {
            throw new IllegalStateException("no download URL available for file " + msg.fileKey);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                .header("Authorization", "Bearer " + botToken)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("download failed with status " + response.statusCode());
        }
        return new DownloadedFile(response.body(), msg.fileName == null ? "" : msg.fileName);
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    /** 对照 slack-go 的调用：POST JSON + Bearer；{@code ok=false} → 错误（包装文案照 Go）。 */
    private JsonNode callApi(String method, Object body, String wrap) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/" + method))
                .header("Authorization", "Bearer " + botToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        JsonNode apiResp = raw == null || raw.length == 0
                ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
        if (!apiResp.path("ok").asBoolean(false)) {
            throw new IllegalStateException(wrap + ": slack API error: "
                    + apiResp.path("error").asText("unknown_error"));
        }
        return apiResp;
    }
}
