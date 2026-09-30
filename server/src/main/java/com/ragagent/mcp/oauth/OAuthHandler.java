package com.ragagent.mcp.oauth;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ragagent.mcp.protocol.McpContext;

/**
 * OAuth2 授权码流程客户端：发现 → 动态客户端注册 → 授权跳转 → code 交换 → 刷新
 * （对照 mcp-go {@code client/transport.OAuthHandler}，oauth.go:170-1101）。
 *
 * <p>这是本项目<b>自研</b>的实现，替代 mcp-go 依赖；协议语义逐条对照，其中：
 * <ul>
 *   <li><b>发现链</b>（{@code getServerMetadata}）：显式 {@code AuthServerMetadataURL} 优先；
 *       否则先拉 RFC 9728 protected-resource well-known，再按 RFC 8414 §3 的
 *       <b>路径插入</b>语义试 {@code /.well-known/oauth-authorization-server[/<path>]} 与
 *       OIDC 的两个变体，全失败才退到默认端点（{@code <authBase>/authorize|token|register}）；</li>
 *   <li><b>元数据 URL 校验</b>：授权服务器广告的每个 URL 字段都必须是 http/https 且带 host，
 *       防止 {@code javascript:}/{@code file:} 被反射进浏览器；</li>
 *   <li><b>RFC 7591 注册</b>：公共客户端用 {@code token_endpoint_auth_method=none}，
 *       已有 secret 时改用 {@code client_secret_post}；成功即就地改写 client_id/secret；</li>
 *   <li><b>CSRF</b>：{@link #setExpectedState} 是跨请求重建 handler 后仍能校验 state 的唯一手段
 *       ——服务端已在回调路径上做过一次性校验，这里是<b>刻意</b>保留的等价语义；</li>
 *   <li><b>GitHub 兼容</b>：HTTP 200 也可能带 {@code error} 字段，故先探 OAuthError 再解析 Token。</li>
 * </ul>
 *
 * <p><b>与 Go 的差异（仅出站实现）</b>：Go 用 {@code *http.Client}（SSRF-safe dialer）+
 * 完整 {@code http.Request}；Java 侧统一走 {@link OAuthHttp}（基于 {@code McpHttp} 的
 * "发送前校验 + 逐跳重定向校验 + 跨域剥凭据头"）。协议行为一致。</p>
 */
public class OAuthHandler {

    /** 对照 mcp-go {@code ErrInvalidState}。 */
    public static final String INVALID_STATE_MESSAGE = "invalid state parameter, possible CSRF attack";

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    final OAuthConfig config;

    /** 元数据发现协作者（对照 Go metadataOnce 段）。 */
    final OAuthDiscovery discovery;

    /** 协议交换协作者（对照 Go 刷新/注册/授权/code 交换段）。 */
    final OAuthTokenOps tokenOps;
    final Duration timeout;

    final ReentrantLock stateLock = new ReentrantLock();
    String expectedState = "";

    public OAuthHandler(OAuthConfig config) {
        this.config = config;
        this.timeout = config.httpTimeout();
        this.discovery = new OAuthDiscovery(this);
        this.tokenOps = new OAuthTokenOps(this);
    }

    /** 薄委托：发现状态见 {@link OAuthDiscovery#setBaseUrl}。 */
    public void setBaseUrl(String value) {
        discovery.setBaseUrl(value);
    }

    /** 薄委托：见 {@link OAuthDiscovery#setProtectedResourceMetadataUrl}。 */
    public void setProtectedResourceMetadataUrl(String url) {
        discovery.setProtectedResourceMetadataUrl(url);
    }

    /** 薄委托：见 {@link OAuthDiscovery#getResourceUrl}。 */
    public String getResourceUrl() {
        return discovery.getResourceUrl();
    }

    /** 薄委托：见 {@link OAuthDiscovery#getServerMetadata}。 */
    public AuthServerMetadata getServerMetadata(McpContext ctx) {
        return discovery.getServerMetadata(ctx);
    }

    /** 薄委托：见 {@link OAuthDiscovery#buildWellKnownUrl}。 */
    static String buildWellKnownUrl(String baseUrl, String suffix) {
        return OAuthDiscovery.buildWellKnownUrl(baseUrl, suffix);
    }

    /** 薄委托：见 {@link OAuthDiscovery#authorizationServerMetadataUrls}。 */
    static List<String> authorizationServerMetadataUrls(String issuerUrl) {
        return OAuthDiscovery.authorizationServerMetadataUrls(issuerUrl);
    }

    /** 薄委托：见 {@link OAuthDiscovery#validateAuthServerMetadataUrls}。 */
    static void validateAuthServerMetadataUrls(AuthServerMetadata m) {
        OAuthDiscovery.validateAuthServerMetadataUrls(m);
    }

    /** 薄委托：见 {@link OAuthDiscovery#resourceIdentifiersEqual}。 */
    static boolean resourceIdentifiersEqual(String a, String b) {
        return OAuthDiscovery.resourceIdentifiersEqual(a, b);
    }


    /** 薄委托：刷新语义见 {@link OAuthTokenOps#refreshToken}。 */
    public OAuthToken refreshToken(McpContext ctx, String refreshToken) {
        return tokenOps.refreshToken(ctx, refreshToken);
    }

    /** 薄委托：见 {@link OAuthTokenOps#registerClient}。 */
    public void registerClient(McpContext ctx, String clientName) {
        tokenOps.registerClient(ctx, clientName);
    }

    /** 薄委托：见 {@link OAuthTokenOps#getAuthorizationUrl}。 */
    public String getAuthorizationUrl(McpContext ctx, String state, String codeChallenge) {
        return tokenOps.getAuthorizationUrl(ctx, state, codeChallenge);
    }

    /** 薄委托：见 {@link OAuthTokenOps#processAuthorizationResponse}。 */
    public void processAuthorizationResponse(McpContext ctx, String code, String state,
                                             String codeVerifier) {
        tokenOps.processAuthorizationResponse(ctx, code, state, codeVerifier);
    }

    /** 薄委托：见 {@link OAuthTokenOps#encodeForm}。 */
    static String encodeForm(Map<String, String> params) {
        return OAuthTokenOps.encodeForm(params);
    }

    /** 薄委托：见 {@link OAuthTokenOps#queryEscape}。 */
    static String queryEscape(String s) {
        return OAuthTokenOps.queryEscape(s);
    }


    // ── 配置读写 ───────────────────────────────────────────────────────

    /** 对照 Go {@code GetClientID}。 */
    public String getClientId() {
        return config.clientId();
    }

    /** 对照 Go {@code GetClientSecret}。 */
    public String getClientSecret() {
        return config.clientSecret();
    }

    // ── CSRF：expected state ───────────────────────────────────────────

    /** 对照 Go {@code SetExpectedState}。 */
    public void setExpectedState(String value) {
        stateLock.lock();
        try {
            this.expectedState = value == null ? "" : value;
        } finally {
            stateLock.unlock();
        }
    }

    /** 对照 Go {@code GetExpectedState}。 */
    public String getExpectedState() {
        stateLock.lock();
        try {
            return expectedState;
        } finally {
            stateLock.unlock();
        }
    }

    // ── 授权头 ─────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code GetAuthorizationHeader}：取当前 token 拼 {@code "<type> <access>"}。
     *
     * <p>RFC 6749 §5.1 规定 token_type 大小写不敏感，Go 把 {@code bearer} 归一成
     * {@code Bearer} 以适配严格实现；此处一致。token_type 为空时 Go 会拼出
     * {@code " <token>"}（前导空格），Java 同样保留，不做"修正"。</p>
     */
    public String getAuthorizationHeader(McpContext ctx) {
        OAuthToken token = getValidToken(ctx);
        String tokenType = token.tokenType();
        if ("bearer".equalsIgnoreCase(tokenType)) {
            tokenType = "Bearer";
        }
        return tokenType + " " + token.accessToken();
    }

    /** 对照 Go {@code getValidToken}：能直接用就返回；有 refresh token 就试一次刷新；否则要授权。 */
    private OAuthToken getValidToken(McpContext ctx) {
        OAuthToken token = null;
        try {
            token = config.tokenStore().getToken(ctx);
        } catch (RuntimeException e) {
            // 对照 Go `if err != nil && !errors.Is(err, ErrNoToken) { return nil, err }`
            if (!OAuthNoTokenException.isNoToken(e)) {
                throw e;
            }
        }
        if (token != null && !token.isExpired() && !token.accessToken().isEmpty()) {
            return token;
        }
        if (token != null && !token.refreshToken().isEmpty()) {
            try {
                return refreshToken(ctx, token.refreshToken());
            } catch (RuntimeException ignored) {
                // 对照 Go：刷新失败就继续走授权流程
            }
        }
        throw new OAuthAuthorizationRequiredException(this);
    }







}
