package com.ragagent.im.feishu;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.FeishuWecomCrypt;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;

/**
 * 飞书 / Lark 适配器（对照 Go {@code internal/im/feishu/adapter.go} L44-1343）。
 *
 * <p>五面齐备：{@code Adapter}（验签/挑战/解析/发送）+ {@code StreamSender}
 * （CardKit v1 流式卡片）+ {@code FullOutputProgressSender}（StartStream 立即给出可替换的
 * "思考中"卡片）+ {@code FileDownloader}（GetMessageResource）。</p>
 *
 * <h2>照抄点</h2>
 * <ul>
 *   <li>验签：{@code header.token} 与 verification_token 比对（加密体先解密；未配 token 跳过）；</li>
 *   <li>URL 挑战：解出 {@code challenge} 即 200 回显（加密体同样先解密）；</li>
 *   <li>解析：只认 {@code im.message.receive_v1}；threadID = root_id 回落 message_id；
 *       群聊剥 {@code @_user_} 前缀；text/file/image/post 四型（post 取 title + text/a 元素）；</li>
 *   <li>发送：先 reply API（落在线程下），可回落错误码 {230019,230054,230071} 时改走
 *       send-message（带 receive_id_type）；message_id 含不安全字符直接拒（疑似篡改）；</li>
 *   <li>流式：CardKit 建卡 → 以 interactive 消息发出 → 逐次 PUT 元素内容（严格递增 seq）→
 *       EndStream 时 PATCH settings 关 streaming_mode 并回填摘要（≤120 字符预览，
 *       始终关 streaming_mode、仅在有内容时给 summary）；</li>
 *   <li>卡片 markdown 图片：外链图先下载（≤10MB）再上传换 image_key（按 app 缓存、URL 去 query），
 *       失败降级为纯链接（label 取 alt 或区域默认）；</li>
 *   <li>token：{@code tenant_access_token/internal}，缓存留 5 分钟余量；</li>
 *   <li>解密走 {@link FeishuWecomCrypt#feishuDecrypt}（AES-256-CBC，密钥 = SHA-256(encrypt_key)，
 *       IV 为密文前 16 字节）。</li>
 * </ul>
 *
 * <h2>与 Go 的实现差异（备案）</h2>
 * <ul>
 *   <li><b>孤儿流回收是惰性的</b>：Go 起 ticker（每分钟清 5 分钟前的孤儿流）；
 *       Java 在每次 {@link #startStream} 时顺带清理（无后台线程，效果等价）。</li>
 *   <li>API 基址可注入（照 Go 的 {@code api_base_url} 语义）——测试因此能用本地 stub。</li>
 * </ul>
 */
public class FeishuAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.FullOutputProgressSender, AdapterInterfaces.FileDownloader {

    private static final Logger log = LoggerFactory.getLogger(FeishuAdapter.class);
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 流式卡片里承载内容的元素 id。 */
    static final String STREAMING_ELEMENT_ID = "streaming_content";
    static final long STREAM_ORPHAN_TTL_MS = 5 * 60 * 1000L;
    /** 上传飞书前对图片的下载上限（飞书限制 10MB，留余量）。 */
    static final int MAX_IMAGE_BYTES = 10 << 20;
    /** 可回落错误码（对照 Go 的 fallbackEligibleErrorCodes）。 */
    static final Set<Integer> FALLBACK_ELIGIBLE = Set.of(230019, 230054, 230071);

    static final Pattern MD_IMAGE_RE =
            Pattern.compile("!\\[([^\\]]*)\\]\\((https?://[^)\\s]+)\\)");
    static final Pattern MD_LINK_RE =
            Pattern.compile("\\[([^\\]]*)\\]\\((https?://[^)\\s]+)\\)");

    /** 全局流表（照 Go 的包级 feishuStreams），key = card_id。 */
    static final Map<String, StreamState> STREAMS = new ConcurrentHashMap<>();
    /** image_key 缓存（照 Go：按 app 作用域 + URL 去 query）。 */
    static final Map<String, String> IMAGE_KEY_CACHE = new ConcurrentHashMap<>();

    /** 一条流的状态（对照 Go {@code feishuStreamState}）。 */
    static final class StreamState {
        final long createdAt = System.currentTimeMillis();
        final Object lock = new Object();
        final StringBuilder content = new StringBuilder();
        long contentSeq;
        long seq;

        /** 对照 {@code nextSeq}：严格递增（CardKit 要求）。 */
        int nextSeq() {
            synchronized (lock) {
                return (int) ++seq;
            }
        }
    }

    final FeishuRegion region;
    final String appId;
    final String appSecret;
    final String verificationToken;
    final String encryptKey;
    final String apiBaseUrl;
    final HttpClient http;
    final SsrfGuard ssrfGuard;

    /** 回调验签/解析协作者（对照 Go 回调段）。 */
    final FeishuCallbackOps callbackOps;

    private final Object tokenLock = new Object();
    private String tokenCache = "";
    private Instant tokenExpiresAt = Instant.EPOCH;

    public FeishuAdapter(FeishuRegion region, String appId, String appSecret,
                         String verificationToken, String encryptKey, String apiBaseUrl,
                         SsrfGuard ssrfGuard) {
        this.region = region == null ? FeishuRegion.FEISHU : region;
        this.appId = appId == null ? "" : appId;
        this.appSecret = appSecret == null ? "" : appSecret;
        this.verificationToken = verificationToken == null ? "" : verificationToken;
        this.encryptKey = encryptKey == null ? "" : encryptKey;
        String base = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        validateApiBaseUrl(base, this.region.openBaseUrl(), ssrfGuard);
        this.apiBaseUrl = base.isEmpty() ? this.region.openBaseUrl() : base;
        this.ssrfGuard = ssrfGuard;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.callbackOps = new FeishuCallbackOps(this);
    }

    /**
     * 对照 {@code validateAPIBaseURL}：空或区域默认放行；自定义必须 http(s)（允许明文 http——
     * 内网反代在 nginx 终止 TLS 的部署）且过 SSRF 校验。
     */
    static void validateApiBaseUrl(String endpoint, String defaultEndpoint, SsrfGuard ssrfGuard) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.equals(defaultEndpoint)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid api_base_url: " + e.getMessage());
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException("api_base_url must use http(s):// scheme, got "
                    + uri.getScheme() + "://");
        }
        if (ssrfGuard != null) {
            try {
                ssrfGuard.validateURLForSSRF(endpoint);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(e.getMessage()
                        + " (for private deployments on internal networks, add the hostname to"
                        + " SSRF_WHITELIST)");
            }
        }
    }

    /** 对照 {@code api}：区域基址 + 路径。 */
    private String api(String path) {
        return apiBaseUrl + path;
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    @Override
    public String platform() {
        return region.platform();
    }

    /** 对照 {@code SupportsFullOutputProgress}：StartStream 立刻给出可替换的思考卡片。 */
    @Override
    public boolean supportsFullOutputProgress() {
        return true;
    }


    /** 薄委托：验签/解析见 {@link FeishuCallbackOps}。 */
    public Exception verifyCallback(CallbackExchange exchange) {
        return callbackOps.verifyCallback(exchange);
    }

    /** 薄委托：见 {@link FeishuCallbackOps#handleURLVerification}。 */
    public boolean handleURLVerification(CallbackExchange exchange) {
        return callbackOps.handleURLVerification(exchange);
    }

    /** 薄委托：见 {@link FeishuCallbackOps#parseCallback}。 */
    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        return callbackOps.parseCallback(exchange);
    }

    /** 薄委托：见 {@link FeishuCallbackOps#stripBotMention}（LarkEventConverter 消费）。 */
    static String stripBotMention(String content) {
        return FeishuCallbackOps.stripBotMention(content);
    }


    // ── 发送（reply 优先，可回落 send-message） ───────────────────────────────

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String accessToken = getTenantAccessToken();
        String content = MAPPER.writeValueAsString(Map.of("text",
                reply.content == null ? "" : reply.content));

        Map<String, Object> replyPayload = new LinkedHashMap<>();
        replyPayload.put("msg_type", "text");
        replyPayload.put("content", content);

        String[] receive = resolveReceiveId(incoming);
        Map<String, Object> fallbackPayload = new LinkedHashMap<>();
        fallbackPayload.put("receive_id", receive[1]);
        fallbackPayload.put("msg_type", "text");
        fallbackPayload.put("content", content);

        sendWithFallback(accessToken, incoming, replyPayload, fallbackPayload, receive[0]);
    }

    /** 对照 {@code resolveReceiveID}：群聊 chat_id，私聊 open_id。 */
    static String[] resolveReceiveId(IncomingMessage incoming) {
        String receiveIdType = "open_id";
        String receiveId = incoming.userId == null ? "" : incoming.userId;
        if (ImTypes.CHAT_TYPE_GROUP.equals(incoming.chatType)
                && incoming.chatId != null && !incoming.chatId.isEmpty()) {
            receiveIdType = "chat_id";
            receiveId = incoming.chatId;
        }
        return new String[] {receiveIdType, receiveId};
    }

    /** 对照 {@code sendWithFallback}：reply API 优先，可回落码 → send-message。 */
    private void sendWithFallback(String accessToken, IncomingMessage incoming,
                                  Map<String, Object> replyPayload,
                                  Map<String, Object> fallbackPayload, String receiveIdType)
            throws Exception {
        String messageId = incoming.messageId == null ? "" : incoming.messageId;
        if (!messageId.isEmpty() && safePathParam(messageId)) {
            String replyUrl = api("/open-apis/im/v1/messages/" + messageId + "/reply");
            ApiResult result = postFeishuMessage(accessToken, replyUrl, replyPayload);
            if (result.transportError() != null) {
                log.warn("[{}] reply API transport error (will try fallback): {}",
                        region.label(), result.transportError());
            } else if (result.code() == 0) {
                return;
            } else if (!FALLBACK_ELIGIBLE.contains(result.code())) {
                throw new IllegalStateException(region.label() + " reply api error: code="
                        + result.code() + " msg=" + result.msg());
            } else {
                log.warn("[{}] reply API returned code={} msg={}, falling back to send-message API",
                        region.label(), result.code(), result.msg());
            }
        } else if (!messageId.isEmpty()) {
            // message_id 含不安全字符 → 拒绝而不是放进 URL 路径（疑似篡改）
            throw new IllegalArgumentException("invalid message_id for reply API: " + messageId);
        } else {
            log.warn("[{}] incoming message has no message_id; replying via send-message API"
                    + " (will not attach to thread)", region.label());
        }

        String fallbackUrl = api("/open-apis/im/v1/messages?receive_id_type=" + receiveIdType);
        ApiResult result = postFeishuMessage(accessToken, fallbackUrl, fallbackPayload);
        if (result.transportError() != null) {
            throw new IllegalStateException("send message (fallback): " + result.transportError(),
                    result.transportError());
        }
        if (result.code() != 0) {
            throw new IllegalStateException(region.label() + " send api error: code="
                    + result.code() + " msg=" + result.msg());
        }
    }

    /** 对照 {@code postFeishuMessage}：POST JSON，解 (code, msg)（传输错误单列）。 */
    private ApiResult postFeishuMessage(String accessToken, String url, Map<String, Object> payload)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            JsonNode node = readTree(response.body());
            return new ApiResult(node.path("code").asInt(0), node.path("msg").asText(""), null);
        } catch (java.io.IOException e) {
            return new ApiResult(0, "", e);
        }
    }

    private record ApiResult(int code, String msg, Throwable transportError) {
    }

    /** 对照 {@code feishuSafePathParam}：只允许字母数字与 {@code -_}，且非空。 */
    static boolean safePathParam(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ── 流式（CardKit v1） ──────────────────────────────────────────────────

    @Override
    public String startStream(IncomingMessage incoming) throws Exception {
        purgeOrphans();
        String accessToken = getTenantAccessToken();

        String cardId = cardkitCreate(accessToken, buildStreamingCardJson(region));
        sendCardByCardId(accessToken, incoming, cardId);

        STREAMS.put(cardId, new StreamState());
        log.info("[{}] Streaming started: card_id={}", region.label(), cardId);
        return cardId;
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
        int seq = state.nextSeq();
        String accessToken = getTenantAccessToken();

        // 卡片 markdown 只接受上传后的 image_key（外链会 200570）→ 先换 image_key
        String content = resolveMarkdownImages(accessToken, fullContent);

        cardkitUpdateElement(accessToken, streamId, STREAMING_ELEMENT_ID, content, seq);

        synchronized (state.lock) {
            if (seq > state.contentSeq) {
                state.content.setLength(0);
                state.content.append(fullContent);
                state.contentSeq = seq;
            }
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
        String accessToken = getTenantAccessToken();

        int seq = 0;
        String summary = null;
        if (state != null) {
            seq = state.nextSeq();
            String preview;
            synchronized (state.lock) {
                preview = cardSummaryPreview(state.content.toString());
            }
            if (!preview.isEmpty()) {
                summary = preview;
            }
        }
        // 始终关 streaming_mode（CardKit 拒绝顶层 streaming_mode 字段）；
        // 只有真的产出过文本才回填摘要，空摘要会把"思考中"预览留在聊天列表里
        try {
            cardkitSetStreaming(accessToken, streamId, false, summary, seq);
        } catch (Exception e) {
            log.warn("[{}] Failed to disable streaming_mode: {}", region.label(), e.toString());
        }
        log.info("[{}] Streaming ended: card_id={}", region.label(), streamId);
    }

    /** 对照 Go 的 ticker 回收：惰性清掉超过 TTL 的孤儿流。 */
    static void purgeOrphans() {
        long cutoff = System.currentTimeMillis() - STREAM_ORPHAN_TTL_MS;
        STREAMS.entrySet().removeIf(e -> e.getValue().createdAt < cutoff);
    }

    /** 对照 {@code cardSummaryPreview}：去图片/链接语法 → 折叠空白 → 截 120 字符。 */
    static String cardSummaryPreview(String content) {
        String value = content == null ? "" : content;
        value = MD_IMAGE_RE.matcher(value).replaceAll("$1");
        value = MD_LINK_RE.matcher(value).replaceAll("$1");
        String collapsed = String.join(" ", value.trim().split("\\s+"));
        return collapsed.length() > 120 ? collapsed.substring(0, 120) : collapsed;
    }

    /** 对照 {@code buildStreamingCardJSON}：schema 2.0 + streaming_mode + 区域占位文案。 */
    static String buildStreamingCardJson(FeishuRegion region) throws Exception {
        ObjectNode card = MAPPER.createObjectNode();
        card.put("schema", "2.0");

        ObjectNode config = MAPPER.createObjectNode();
        config.put("streaming_mode", true);
        config.set("summary", MAPPER.createObjectNode().put("content", region.thinkingText()));
        card.set("config", config);

        ObjectNode header = MAPPER.createObjectNode();
        header.put("template", "blue");
        ObjectNode title = MAPPER.createObjectNode();
        title.put("tag", "plain_text");
        title.put("content", "WeKnora");
        header.set("title", title);
        card.set("header", header);

        ObjectNode element = MAPPER.createObjectNode();
        element.put("tag", "markdown");
        element.put("content", "💭 " + region.thinkingText());
        element.put("text_size", "normal");
        element.put("element_id", STREAMING_ELEMENT_ID);
        ArrayNode elements = MAPPER.createArrayNode();
        elements.add(element);
        card.set("body", MAPPER.createObjectNode().set("elements", elements));

        return MAPPER.writeValueAsString(card);
    }

    /** 对照 {@code cardkitCreate}：POST cardkit/v1/cards（type=card_json）→ card_id。 */
    private String cardkitCreate(String accessToken, String cardJson) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "card_json");
        payload.put("data", cardJson);

        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/open-apis/cardkit/v1/cards")))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("code=" + code + " msg=" + result.path("msg").asText(""));
        }
        String cardId = result.path("data").path("card_id").asText("");
        if (cardId.isEmpty()) {
            throw new IllegalStateException("parse card_id: empty (raw: " + response.body().length
                    + " bytes)");
        }
        return cardId;
    }

    /** 对照 {@code sendCardByCardID}：interactive 消息（content.type=card）+ 回落。 */
    private void sendCardByCardId(String accessToken, IncomingMessage incoming, String cardId)
            throws Exception {
        String[] receive = resolveReceiveId(incoming);
        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "card");
        content.set("data", MAPPER.createObjectNode().put("card_id", cardId));
        String contentJson = MAPPER.writeValueAsString(content);

        Map<String, Object> replyPayload = new LinkedHashMap<>();
        replyPayload.put("msg_type", "interactive");
        replyPayload.put("content", contentJson);

        Map<String, Object> fallbackPayload = new LinkedHashMap<>();
        fallbackPayload.put("receive_id", receive[1]);
        fallbackPayload.put("msg_type", "interactive");
        fallbackPayload.put("content", contentJson);

        sendWithFallback(accessToken, incoming, replyPayload, fallbackPayload, receive[0]);
    }

    /** 对照 {@code cardkitUpdateElement}：PUT 元素内容（带 sequence）。 */
    private void cardkitUpdateElement(String accessToken, String cardId, String elementId,
                                      String content, int sequence) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("sequence", sequence);

        String url = api("/open-apis/cardkit/v1/cards/" + cardId + "/elements/" + elementId
                + "/content");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("update element error: code=" + code + " msg="
                    + result.path("msg").asText(""));
        }
    }

    /** 对照 {@code cardkitSetStreaming}：PATCH settings（config.streaming_mode + 可选 summary）。 */
    private void cardkitSetStreaming(String accessToken, String cardId, boolean streaming,
                                     String finalSummary, int sequence) throws Exception {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("streaming_mode", streaming);
        if (finalSummary != null && !finalSummary.trim().isEmpty()) {
            config.set("summary", MAPPER.createObjectNode().put("content", finalSummary));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settings", MAPPER.writeValueAsString(Map.of("config", config)));
        payload.put("sequence", sequence);

        String url = api("/open-apis/cardkit/v1/cards/" + cardId + "/settings");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("set streaming error: code=" + code + " msg="
                    + result.path("msg").asText(""));
        }
    }

    // ── 卡片 markdown 图片 → image_key ──────────────────────────────────────

    /** 对照 {@code resolveMarkdownImages}：失败降级为纯链接（不让整次更新失败）。 */
    String resolveMarkdownImages(String accessToken, String content) {
        if (content == null || !content.contains("![")) {
            return content;
        }
        Matcher matcher = MD_IMAGE_RE.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String rawUrl = matcher.group(2);
            String imageKey = null;
            try {
                imageKey = imageKeyForUrl(accessToken, rawUrl);
            } catch (Exception e) {
                log.warn("[{}] image upload failed, degrading to link: url={} err={}",
                        region.label(), rawUrl, e.toString());
            }
            String replacement;
            if (imageKey == null || imageKey.isEmpty()) {
                String label = alt == null || alt.isEmpty() ? region.imageFallbackLabel() : alt;
                replacement = "[" + label + "](" + rawUrl + ")";
            } else {
                replacement = "![" + alt + "](" + imageKey + ")";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 对照 {@code imageKeyForURL}：缓存按 app 作用域 + URL 去 query。 */
    String imageKeyForUrl(String accessToken, String rawUrl) throws Exception {
        String key = appId + "\u0000" + imageCacheKey(rawUrl);
        String cached = IMAGE_KEY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String imageKey = uploadImageFromUrl(accessToken, rawUrl);
        IMAGE_KEY_CACHE.put(key, imageKey);
        return imageKey;
    }

    /** 对照 {@code imageCacheKey}：去掉 query（签名 URL 的签名每次都变）。 */
    static String imageCacheKey(String rawUrl) {
        int idx = rawUrl.indexOf('?');
        return idx >= 0 ? rawUrl.substring(0, idx) : rawUrl;
    }

    /** 对照 {@code uploadImageFromURL}：下载（限 10MB）→ multipart 上传 → image_key。 */
    String uploadImageFromUrl(String accessToken, String rawUrl) throws Exception {
        if (ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(rawUrl);
        }
        HttpRequest downloadRequest = HttpRequest.newBuilder(URI.create(rawUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> downloadResponse = http.send(downloadRequest,
                HttpResponse.BodyHandlers.ofByteArray());
        if (downloadResponse.statusCode() != 200) {
            throw new IllegalStateException("download image: status="
                    + downloadResponse.statusCode());
        }
        byte[] data = downloadResponse.body() == null ? new byte[0] : downloadResponse.body();
        if (data.length == 0) {
            throw new IllegalStateException("empty image body");
        }
        if (data.length > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("image exceeds " + MAX_IMAGE_BYTES + " bytes");
        }

        String boundary = "----WeKnoraBoundary" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image_type\"\r\n\r\n"
                + "message\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"image\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        body.write(prefix.getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/open-apis/im/v1/images")))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("upload image error: code=" + code + " msg="
                    + result.path("msg").asText(""));
        }
        String imageKey = result.path("data").path("image_key").asText("");
        if (imageKey.isEmpty()) {
            throw new IllegalStateException("upload image: empty image_key");
        }
        return imageKey;
    }

    // ── FileDownloader ──────────────────────────────────────────────────────

    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()
                || msg.messageId == null || msg.messageId.isEmpty()) {
            throw new IllegalArgumentException("file_key and message_id are required");
        }
        if (!safePathParam(msg.messageId) || !safePathParam(msg.fileKey)) {
            throw new IllegalArgumentException("invalid message_id or file_key format");
        }
        String accessToken = getTenantAccessToken();
        String resourceType = ImTypes.MESSAGE_TYPE_IMAGE.equals(msg.messageType)
                ? "image" : "file";

        String url = api("/open-apis/im/v1/messages/" + msg.messageId + "/resources/"
                + msg.fileKey + "?type=" + resourceType);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("download file failed: status="
                    + response.statusCode());
        }

        String fileName = msg.fileName == null ? "" : msg.fileName;
        if (fileName.isEmpty()) {
            String disposition = response.headers().firstValue("Content-Disposition").orElse("");
            int idx = disposition.indexOf("filename=");
            if (idx >= 0) {
                fileName = disposition.substring(idx + "filename=".length()).trim()
                        .replaceAll("^\"|\"$", "").trim();
            }
        }
        if (fileName.isEmpty()) {
            fileName = msg.fileKey;
        }
        return new DownloadedFile(response.body(), fileName);
    }

    // ── token 与解密 ────────────────────────────────────────────────────────

    /** 对照 {@code getTenantAccessToken}：缓存留 5 分钟余量。 */
    String getTenantAccessToken() throws Exception {
        synchronized (tokenLock) {
            if (!tokenCache.isEmpty() && Instant.now().isBefore(tokenExpiresAt)) {
                return tokenCache;
            }
        }
        Map<String, String> payload = Map.of("app_id", appId, "app_secret", appSecret);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(api("/open-apis/auth/v3/tenant_access_token/internal")))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("get token error: code=" + code + " msg="
                    + result.path("msg").asText(""));
        }
        String token = result.path("tenant_access_token").asText("");
        long expireSeconds = result.path("expire").asLong(0);
        synchronized (tokenLock) {
            tokenCache = token;
            long ttl = expireSeconds;
            if (ttl > 300) {
                ttl -= 300;
            }
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(ttl, 0));
        }
        return token;
    }

    static JsonNode readTree(byte[] body) throws Exception {
        if (body == null || body.length == 0) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(body);
    }

    /** 供测试观察（当前 API 基址）。 */
    String apiBaseUrl() {
        return apiBaseUrl;
    }
}
