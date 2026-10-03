package com.ragagent.im.qqbot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;

/**
 * QQ 机器人开放平台 HTTP 客户端。
 *
 * <p>行为要点：access_token 缓存（余量 60 秒、缺省 7200 秒，{@code expires_in} 数字/字符串两形态）、
 * 除取 token 外一律带 {@code Authorization: QQBot <token>}、发送体
 * {@code {msg_type:2, markdown:{content}, msg_id, msg_seq:1}}、C2C/群两条路径、
 * 基址与 gateway 的校验（http(s) + SSRF 白名单；gateway 必须 {@code wss}）。</p>
 */
public class QqBotClient {

    public static final String DEFAULT_API_BASE_URL = "https://api.sgroup.qq.com";
    public static final String APP_TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    public static final String DEFAULT_GATEWAY_URL = "https://api.sgroup.qq.com/gateway";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long EXPIRY_MARGIN_SECONDS = 60;
    private static final int DEFAULT_EXPIRES_IN = 7200;

    private final String appId;
    private final String clientSecret;
    private final String apiBaseUrl;
    private final String gatewayUrl;
    /** token 端点（默认官方地址；测试用包内构造注入 stub 基址）。 */
    private final String tokenUrl;
    /** 网关发现端点（同上；包内可见，测试可改指 stub）。 */
    String gatewayDiscoveryUrl = DEFAULT_GATEWAY_URL;
    private final HttpClient http;
    private final SsrfGuard ssrfGuard;

    private final Object tokenLock = new Object();
    private String accessToken = "";
    private Instant expiresAt = Instant.EPOCH;

    /**
     * 参数校验失败抛 {@link IllegalArgumentException}（文案与平台行为对齐）。
     */
    public QqBotClient(String appId, String clientSecret, String apiBaseUrl, String gatewayUrl,
            SsrfGuard ssrfGuard) {
        this(appId, clientSecret, apiBaseUrl, gatewayUrl, APP_TOKEN_URL, ssrfGuard);
    }

    /** 测试用：注入 token 端点（生产走 {@link #APP_TOKEN_URL}）。 */
    QqBotClient(String appId, String clientSecret, String apiBaseUrl, String gatewayUrl,
            String tokenUrl, SsrfGuard ssrfGuard) {
        this.tokenUrl = tokenUrl == null || tokenUrl.isEmpty() ? APP_TOKEN_URL : tokenUrl;
        this.appId = appId == null ? "" : appId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        if (this.appId.isEmpty()) {
            throw new IllegalArgumentException("qqbot app_id is required");
        }
        if (this.clientSecret.isEmpty()) {
            throw new IllegalArgumentException("qqbot client_secret is required");
        }
        String base = apiBaseUrl == null || apiBaseUrl.isEmpty() ? DEFAULT_API_BASE_URL : apiBaseUrl;
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        validateHttpApiBaseUrl(base, ssrfGuard);
        this.apiBaseUrl = base;
        String gateway = gatewayUrl == null ? "" : gatewayUrl.trim();
        validateGatewayUrl(gateway, ssrfGuard);
        this.gatewayUrl = gateway;
        this.ssrfGuard = ssrfGuard;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** api_base_url 必须 http(s) 且过 SSRF 校验。 */
    static void validateHttpApiBaseUrl(String raw, SsrfGuard ssrfGuard) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "invalid qqbot api_base_url: must be a valid http(s) URL");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid qqbot api_base_url: must be a valid http(s) URL");
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException(
                    "invalid qqbot api_base_url: must use http or https");
        }
        if (ssrfGuard != null) {
            try {
                ssrfGuard.validateURLForSSRF(raw);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid qqbot api_base_url: " + e.getMessage()
                        + " (for private deployments, add the hostname to SSRF_WHITELIST)");
            }
        }
    }

    /** gateway_url 空放行；必须 wss；按 https 做 SSRF 校验。 */
    static void validateGatewayUrl(String raw, SsrfGuard ssrfGuard) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("gateway_url must be a valid wss URL");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("gateway_url must be a valid wss URL");
        }
        if (!"wss".equals(uri.getScheme())) {
            throw new IllegalArgumentException("gateway_url must use wss");
        }
        if (ssrfGuard != null) {
            String check = "https://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + (uri.getPath() == null ? "" : uri.getPath());
            try {
                ssrfGuard.validateURLForSSRF(check);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("gateway_url failed SSRF validation: "
                        + e.getMessage()
                        + " (for private deployments, add the hostname to SSRF_WHITELIST)");
            }
        }
    }

    /** 注入优先，否则 GET 默认网关端点取 {@code url} 并校验。 */
    public String gatewayUrl() throws Exception {
        if (!gatewayUrl.isEmpty()) {
            return gatewayUrl;
        }
        JsonNode result = doJson("GET", gatewayDiscoveryUrl, null);
        String url = result.path("url").asText("");
        if (url.isEmpty()) {
            throw new IllegalStateException("empty qqbot gateway url");
        }
        try {
            validateGatewayUrl(url, ssrfGuard);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("invalid qqbot gateway url: " + e.getMessage(), e);
        }
        return url;
    }

    /** POST {@code /v2/users/{openId}/messages}。 */
    public void sendC2CMessage(String openId, String content, String msgId) throws Exception {
        sendText("/v2/users/" + openId + "/messages", content, msgId);
    }

    /** POST {@code /v2/groups/{groupOpenId}/messages}。 */
    public void sendGroupMessage(String groupOpenId, String content, String msgId) throws Exception {
        sendText("/v2/groups/" + groupOpenId + "/messages", content, msgId);
    }

    private void sendText(String path, String content, String msgId) throws Exception {
        Map<String, Object> markdown = new LinkedHashMap<>();
        if (content != null && !content.isEmpty()) {
            markdown.put("content", content);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", 2);
        body.put("markdown", markdown);
        if (msgId != null && !msgId.isEmpty()) {
            body.put("msg_id", msgId);
        }
        body.put("msg_seq", 1);
        doJson("POST", apiBaseUrl + path, body);
    }

    /** 非 2xx 抛错；取 token 的请求不带 Authorization。 */
    private JsonNode doJson(String method, String url, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(
                    MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        if (!url.contains("getAppAccessToken")) {
            builder.header("Authorization", "QQBot " + accessToken());
        }
        HttpResponse<byte[]> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("qqbot api " + method + " " + url + " failed: "
                    + response.statusCode());
        }
        byte[] raw = response.body();
        if (raw == null || raw.length == 0) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(raw);
    }

    /** 缓存命中（余量 &gt; 60s）直接用，否则取新。 */
    public String accessToken() throws Exception {
        synchronized (tokenLock) {
            if (!accessToken.isEmpty()
                    && Duration.between(Instant.now(), expiresAt).getSeconds() > EXPIRY_MARGIN_SECONDS) {
                return accessToken;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appId", appId);
        body.put("clientSecret", clientSecret);
        JsonNode result = doJson("POST", tokenUrl, body);
        String token = result.path("access_token").asText("");
        if (token.isEmpty()) {
            throw new IllegalStateException("empty qqbot access token: code="
                    + result.path("code").asInt(0) + " message="
                    + result.path("message").asText(""));
        }
        int expiresIn = parseExpiresIn(result.path("expires_in"));
        synchronized (tokenLock) {
            this.accessToken = token;
            this.expiresAt = Instant.now().plusSeconds(expiresIn);
        }
        return token;
    }

    /** 数字、或可解析的字符串；否则 7200。 */
    static int parseExpiresIn(JsonNode raw) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return DEFAULT_EXPIRES_IN;
        }
        if (raw.isNumber()) {
            int number = raw.asInt(0);
            return number > 0 ? number : DEFAULT_EXPIRES_IN;
        }
        if (raw.isTextual()) {
            try {
                int parsed = Integer.parseInt(raw.asText().trim());
                return parsed > 0 ? parsed : DEFAULT_EXPIRES_IN;
            } catch (NumberFormatException ignored) {
                return DEFAULT_EXPIRES_IN;
            }
        }
        return DEFAULT_EXPIRES_IN;
    }
}
