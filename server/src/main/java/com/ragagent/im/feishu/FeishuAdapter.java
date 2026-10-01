package com.ragagent.im.feishu;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.FeishuWecomCrypt;
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

    static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** 发送协作者（对照 Go 发送段）。 */
    final FeishuSendOps sendOps;

    /** CardKit 流式卡片协作者（对照 Go 流式段）。 */
    final FeishuCardStreamOps streamOps;

    /** 媒体协作者（对照 Go 图片/文件段）。 */
    final FeishuMediaOps mediaOps;

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
        this.sendOps = new FeishuSendOps(this);
        this.streamOps = new FeishuCardStreamOps(this);
        this.mediaOps = new FeishuMediaOps(this);
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
    String api(String path) {
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



    /** 薄委托：发送语义见 {@link FeishuSendOps#sendReply}。 */
    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        sendOps.sendReply(incoming, reply);
    }

    /** 薄委托：F3/F4 消费（reply 优先、可回落）。 */
    void sendWithFallback(String accessToken, IncomingMessage incoming,
                          Map<String, Object> replyPayload, Map<String, Object> fallbackPayload,
                          String receiveIdType) throws Exception {
        sendOps.sendWithFallback(accessToken, incoming, replyPayload, fallbackPayload, receiveIdType);
    }

    /** 薄委托：测试直调 + F3 消费。 */
    static String[] resolveReceiveId(IncomingMessage incoming) {
        return FeishuSendOps.resolveReceiveId(incoming);
    }

    /** 薄委托：测试直调 + F4 消费。 */
    static boolean safePathParam(String value) {
        return FeishuSendOps.safePathParam(value);
    }



    /** 薄委托：流式卡片见 {@link FeishuCardStreamOps}。 */
    @Override
    public String startStream(IncomingMessage incoming) throws Exception {
        return streamOps.startStream(incoming);
    }

    /** 薄委托：见 {@link FeishuCardStreamOps#updateStreamContent}。 */
    @Override
    public void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        streamOps.updateStreamContent(incoming, streamId, fullContent);
    }

    /** 薄委托：见 {@link FeishuCardStreamOps#finalizeStream}。 */
    @Override
    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        streamOps.finalizeStream(incoming, streamId, finalContent);
    }

    /** 薄委托：见 {@link FeishuCardStreamOps#endStream}。 */
    @Override
    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        streamOps.endStream(incoming, streamId);
    }

    /** 薄委托：见 {@link FeishuCardStreamOps#cardSummaryPreview}（测试直调）。 */
    static String cardSummaryPreview(String content) {
        return FeishuCardStreamOps.cardSummaryPreview(content);
    }




    /** 薄委托：图片降级语义见 {@link FeishuMediaOps#resolveMarkdownImages}（测试直调）。 */
    String resolveMarkdownImages(String accessToken, String content) {
        return mediaOps.resolveMarkdownImages(accessToken, content);
    }

    /** 薄委托：文件下载见 {@link FeishuMediaOps#downloadFile}。 */
    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        return mediaOps.downloadFile(msg);
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
