package com.ragagent.mcp.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final OAuthConfig config;
    private final Duration timeout;

    /**
     * 元数据发现的"只跑一次"状态（对照 Go 的 {@code metadataOnce} /
     * {@code serverMetadata} / {@code metadataFetchErr} / {@code baseURL} /
     * {@code resourceURL} 一整组，全由 {@code metadataMu} 保护）。
     */
    private final ReentrantLock metadataLock = new ReentrantLock();
    private boolean metadataFetched;
    private AuthServerMetadata serverMetadata;
    private OAuthProtocolException metadataFetchError;
    private String baseUrl = "";
    /** RFC 8707 resource indicator；由 protected-resource 元数据或 base URL 推出。 */
    private String resourceUrl = "";

    private final ReentrantLock stateLock = new ReentrantLock();
    private String expectedState = "";

    public OAuthHandler(OAuthConfig config) {
        this.config = config;
        this.timeout = config.httpTimeout();
    }

    // ── 配置读写 ───────────────────────────────────────────────────────

    /** 对照 Go {@code SetBaseURL}。 */
    public void setBaseUrl(String value) {
        metadataLock.lock();
        try {
            this.baseUrl = value == null ? "" : value;
        } finally {
            metadataLock.unlock();
        }
    }

    /** 对照 Go {@code GetClientID}。 */
    public String getClientId() {
        return config.clientId();
    }

    /** 对照 Go {@code GetClientSecret}。 */
    public String getClientSecret() {
        return config.clientSecret();
    }

    /**
     * 对照 Go {@code SetProtectedResourceMetadataURL}：换 URL 时把发现结果整体作废
     * （{@code serverMetadata}/{@code metadataFetchErr}/{@code metadataOnce}/{@code resourceURL}）。
     */
    public void setProtectedResourceMetadataUrl(String url) {
        metadataLock.lock();
        try {
            config.setProtectedResourceMetadataUrl(url);
            this.serverMetadata = null;
            this.metadataFetchError = null;
            this.metadataFetched = false;
            this.resourceUrl = "";
        } finally {
            metadataLock.unlock();
        }
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

    // ── 刷新 ───────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code refreshToken}（oauth.go:240-318）。
     *
     * <p>要点：接受<b>任意 2xx</b>（Supabase 会回 201）；若响应体里带 {@code error} 字段
     * （GitHub 的 HTTP 200 错误）则按错误处理；服务器没回新 refresh token 时<b>沿用旧的</b>
     * （轮换型 refresh token 的常见形态）。</p>
     */
    public OAuthToken refreshToken(McpContext ctx, String refreshToken) {
        AuthServerMetadata metadata = getServerMetadata(ctx);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", config.clientId());
        if (!config.clientSecret().isEmpty()) {
            form.put("client_secret", config.clientSecret());
        }
        // RFC 8707：刷新请求也要带 resource
        if (!getResourceUrl().isEmpty()) {
            form.put("resource", getResourceUrl());
        }

        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        OAuthHttp.Response resp = OAuthHttp.post(metadata.tokenEndpoint(),
                "application/x-www-form-urlencoded", "application/json", body, timeout);

        if (resp.status() < 200 || resp.status() >= 300) {
            throw extractOAuthError(resp.body(), resp.status(), "refresh token request failed");
        }

        // GitHub 会在 HTTP 200 里带 error 字段
        OAuthError bodyError = parseOAuthError(resp.body());
        if (bodyError != null) {
            throw OAuthProtocolException.ofOAuthError("refresh token request failed", bodyError);
        }

        OAuthToken token = parseToken(resp.body());
        if (token.expiresIn() > 0) {
            token.applyExpiresIn(token.expiresIn());
        }
        if (token.refreshToken().isEmpty()) {
            token.setRefreshToken(refreshToken);
        }
        config.tokenStore().saveToken(ctx, token);
        return token;
    }

    // ── RFC 7591 动态客户端注册 ─────────────────────────────────────────

    /**
     * 对照 Go {@code RegisterClient}（oauth.go:886-966）。
     *
     * <p>注册成功后<b>就地</b>更新 handler 的 client_id/secret，随后的
     * {@link #getClientId} 才拿得到新值（Go 正是靠这个把 client_id 回填进
     * {@code OAuthManager.StartAuthorization}）。</p>
     */
    public void registerClient(McpContext ctx, String clientName) {
        AuthServerMetadata metadata = getServerMetadata(ctx);
        if (metadata.registrationEndpoint().isEmpty()) {
            throw OAuthProtocolException.of("server does not support dynamic client registration");
        }

        Map<String, Object> regRequest = new LinkedHashMap<>();
        regRequest.put("client_name", clientName);
        regRequest.put("redirect_uris", List.of(config.redirectUri()));
        regRequest.put("token_endpoint_auth_method", "none"); // 公共客户端
        regRequest.put("grant_types", List.of("authorization_code", "refresh_token"));
        regRequest.put("response_types", List.of("code"));
        regRequest.put("scope", String.join(" ", config.scopes()));
        if (!config.clientUri().isEmpty()) {
            regRequest.put("client_uri", config.clientUri());
        }
        if (!config.clientSecret().isEmpty()) {
            regRequest.put("token_endpoint_auth_method", "client_secret_post");
        }
        if (!getResourceUrl().isEmpty()) {
            regRequest.put("resource", getResourceUrl());
        }

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(regRequest);
        } catch (Exception e) {
            throw OAuthProtocolException.of("failed to marshal registration request: " + e.getMessage(), e);
        }

        OAuthHttp.Response resp = OAuthHttp.post(metadata.registrationEndpoint(),
                "application/json", "application/json", body, timeout);

        if (resp.status() != 201 && resp.status() != 200) {
            throw extractOAuthError(resp.body(), resp.status(), "registration request failed");
        }

        Map<?, ?> regResponse;
        try {
            regResponse = MAPPER.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            throw OAuthProtocolException.of("failed to decode registration response: " + e.getMessage(), e);
        }
        String clientId = stringOf(regResponse.get("client_id"));
        String clientSecret = stringOf(regResponse.get("client_secret"));
        config.clientId(clientId);
        if (!clientSecret.isEmpty()) {
            config.clientSecret(clientSecret);
        }
    }

    // ── 授权 URL ───────────────────────────────────────────────────────

    /**
     * 对照 Go {@code GetAuthorizationURL}（oauth.go:1071-1101）。
     *
     * <p><b>注意副作用</b>：Go 在这里顺手调用 {@code SetExpectedState(state)}，
     * 即"发起授权"这一动作本身就把 state 记进了 handler 的 CSRF 期望值。
     * 回调请求是<b>另一个</b> handler 实例，因此必须显式再 set 一次
     * （见 {@code OAuthManager.CompleteAuthorization} 的说明）。</p>
     */
    public String getAuthorizationUrl(McpContext ctx, String state, String codeChallenge) {
        AuthServerMetadata metadata = getServerMetadata(ctx);
        setExpectedState(state);

        Map<String, String> params = new TreeMap<>();
        params.put("response_type", "code");
        params.put("client_id", config.clientId());
        params.put("redirect_uri", config.redirectUri());
        params.put("state", state);
        if (!config.scopes().isEmpty()) {
            params.put("scope", String.join(" ", config.scopes()));
        }
        if (config.pkceEnabled() && !codeChallenge.isEmpty()) {
            params.put("code_challenge", codeChallenge);
            params.put("code_challenge_method", "S256");
        }
        if (!getResourceUrl().isEmpty()) {
            params.put("resource", getResourceUrl());
        }
        return metadata.authorizationEndpoint() + "?" + encodeForm(params);
    }

    // ── code 交换 ──────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ProcessAuthorizationResponse}（oauth.go:971-1068）。
     *
     * <p>CSRF 校验先行：期望值为空报"流程未正确发起"，不匹配报
     * {@value #INVALID_STATE_MESSAGE}；<b>校验后立刻清空</b>期望值，
     * 使同一 handler 上的第二次回调必然失败。</p>
     */
    public void processAuthorizationResponse(McpContext ctx, String code, String state,
                                             String codeVerifier) {
        stateLock.lock();
        try {
            if (expectedState.isEmpty()) {
                throw OAuthProtocolException.of(
                        "no expected state found, authorization flow may not have been initiated properly");
            }
            if (!state.equals(expectedState)) {
                throw OAuthProtocolException.of(INVALID_STATE_MESSAGE);
            }
            expectedState = "";
        } finally {
            stateLock.unlock();
        }

        AuthServerMetadata metadata = getServerMetadata(ctx);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", config.clientId());
        form.put("redirect_uri", config.redirectUri());
        if (!config.clientSecret().isEmpty()) {
            form.put("client_secret", config.clientSecret());
        }
        if (config.pkceEnabled() && !codeVerifier.isEmpty()) {
            form.put("code_verifier", codeVerifier);
        }
        if (!getResourceUrl().isEmpty()) {
            form.put("resource", getResourceUrl());
        }

        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        OAuthHttp.Response resp = OAuthHttp.post(metadata.tokenEndpoint(),
                "application/x-www-form-urlencoded", "application/json", body, timeout);

        if (resp.status() < 200 || resp.status() >= 300) {
            throw extractOAuthError(resp.body(), resp.status(), "token request failed");
        }

        OAuthError bodyError = parseOAuthError(resp.body());
        if (bodyError != null) {
            throw OAuthProtocolException.ofOAuthError("token request failed", bodyError);
        }

        OAuthToken token = parseToken(resp.body());
        if (token.expiresIn() > 0) {
            token.applyExpiresIn(token.expiresIn());
        }
        config.tokenStore().saveToken(ctx, token);
    }

    // ── 发现 ───────────────────────────────────────────────────────────

    /** 对照 Go {@code GetServerMetadata}（公开包装）。 */
    public AuthServerMetadata getServerMetadata(McpContext ctx) {
        metadataLock.lock();
        try {
            if (!metadataFetched) {
                metadataFetched = true;
                discover(ctx);
            }
            if (metadataFetchError != null) {
                throw metadataFetchError;
            }
            return serverMetadata;
        } finally {
            metadataLock.unlock();
        }
    }

    /**
     * 对照 Go {@code getServerMetadata} 的 {@code metadataOnce.Do} 函数体（oauth.go:499-663）。
     * 调用方必须已持有 {@link #metadataLock}。
     */
    private void discover(McpContext ctx) {
        // 1. 显式 metadata URL 优先，直接用它，不做任何回退
        if (!config.authServerMetadataUrl().isEmpty()) {
            fetchMetadataFromUrl(config.authServerMetadataUrl());
            if (serverMetadata == null && metadataFetchError == null) {
                // Go 在这里会返回 (nil, nil)，随后调用方解引用空指针 panic（gin recovery → 500）。
                // Java 侧改为显式报错：对外仍是 500，但只有一条清晰文案，不是 NPE 堆栈。
                metadataFetchError = OAuthProtocolException.of(
                        "failed to load authorization server metadata from "
                                + config.authServerMetadataUrl());
            }
            return;
        }

        String base;
        try {
            base = extractBaseUrl();
        } catch (RuntimeException e) {
            metadataFetchError = OAuthProtocolException.of("failed to extract base URL: " + e.getMessage(), e);
            return;
        }

        boolean explicitMetadataUrl = !config.protectedResourceMetadataUrl().isEmpty();
        String protectedResourceUrl;
        if (explicitMetadataUrl) {
            protectedResourceUrl = config.protectedResourceMetadataUrl();
        } else {
            try {
                protectedResourceUrl = buildWellKnownUrl(base, "oauth-protected-resource");
            } catch (RuntimeException e) {
                metadataFetchError = OAuthProtocolException.of(
                        "failed to build protected resource URL: " + e.getMessage(), e);
                return;
            }
        }

        OAuthHttp.Response resp;
        try {
            resp = OAuthHttp.get(protectedResourceUrl, timeout);
        } catch (RuntimeException e) {
            metadataFetchError = OAuthProtocolException.of(
                    "failed to send protected resource request: " + e.getMessage(), e);
            return;
        }

        if (resp.status() != 200) {
            // 显式给的 URL 失败了就<b>不</b>再回退——服务端明确指了去哪找，回退只会掩盖问题
            if (explicitMetadataUrl) {
                metadataFetchError = OAuthProtocolException.of(
                        "protected resource metadata discovery failed for explicit URL \""
                                + protectedResourceUrl + "\": status " + resp.status());
                return;
            }
            for (String u : authorizationServerMetadataUrls(base)) {
                fetchMetadataFromUrl(u);
                if (serverMetadata != null) {
                    metadataFetchError = null;
                    return;
                }
            }
            AuthServerMetadata defaults = getDefaultEndpoints(base);
            serverMetadata = defaults;
            metadataFetchError = null;
            return;
        }

        OAuthProtectedResource protectedResource;
        try {
            protectedResource = MAPPER.readValue(resp.body(), OAuthProtectedResource.class);
        } catch (Exception e) {
            metadataFetchError = OAuthProtocolException.of(
                    "failed to decode protected resource response: " + e.getMessage(), e);
            return;
        }

        // RFC 9728 §3.3/§7.3：从 WWW-Authenticate 广告来的 PRM（不可信网络输入）必须自证——
        // 声明的 resource 必须与被寻址的受保护资源一致；缺 resource 字段同样拒绝。
        if (explicitMetadataUrl) {
            if (protectedResource.resource().isEmpty()) {
                metadataFetchError = OAuthProtocolException.of(
                        "advertised protected resource metadata from \"" + protectedResourceUrl
                                + "\" omits required resource field");
                return;
            }
            if (!resourceIdentifiersEqual(protectedResource.resource(), base)) {
                metadataFetchError = OAuthProtocolException.of(
                        "advertised protected resource metadata declares resource \""
                                + protectedResource.resource() + "\" which does not match base URL \"" + base + "\"");
                return;
            }
        }

        // RFC 8707：记下 resource 指示符；元数据没给就退回 base URL
        resourceUrl = protectedResource.resource().isEmpty() ? base : protectedResource.resource();

        if (!protectedResource.hasAuthorizationServers()) {
            serverMetadata = getDefaultEndpoints(base);
            metadataFetchError = null;
            return;
        }

        String authServerUrl = protectedResource.authorizationServers().get(0);
        for (String u : authorizationServerMetadataUrls(authServerUrl)) {
            fetchMetadataFromUrl(u);
            if (serverMetadata != null) {
                metadataFetchError = null;
                return;
            }
        }
        serverMetadata = getDefaultEndpoints(authServerUrl);
        metadataFetchError = null;
    }

    /**
     * 对照 Go {@code fetchMetadataFromURL}：非 200 <b>静默跳过</b>（让调用方试下一个候选）；
     * 解码成功但 URL 字段非法时记录错误。
     */
    private void fetchMetadataFromUrl(String metadataUrl) {
        OAuthHttp.Response resp;
        try {
            resp = OAuthHttp.get(metadataUrl, timeout);
        } catch (RuntimeException e) {
            metadataFetchError = OAuthProtocolException.of(
                    "failed to send metadata request: " + e.getMessage(), e);
            return;
        }
        if (resp.status() != 200) {
            return;
        }
        AuthServerMetadata metadata;
        try {
            metadata = MAPPER.readValue(resp.body(), AuthServerMetadata.class);
        } catch (Exception e) {
            metadataFetchError = OAuthProtocolException.of(
                    "failed to decode metadata response: " + e.getMessage(), e);
            return;
        }
        try {
            validateAuthServerMetadataUrls(metadata);
        } catch (RuntimeException e) {
            metadataFetchError = OAuthProtocolException.of(
                    "invalid authorization server metadata from " + metadataUrl + ": " + e.getMessage(), e);
            return;
        }
        serverMetadata = metadata;
    }

    /**
     * 对照 Go {@code extractBaseURL}：有 base URL 就用；否则从 redirect_uri 推 scheme://host。
     */
    private String extractBaseUrl() {
        if (!baseUrl.isEmpty()) {
            return baseUrl;
        }
        if (config.redirectUri().isEmpty()) {
            throw OAuthProtocolException.of("no base URL available and no redirect URI provided");
        }
        URI parsed;
        try {
            parsed = new URI(config.redirectUri());
        } catch (URISyntaxException e) {
            throw OAuthProtocolException.of("failed to parse redirect URI: " + e.getMessage(), e);
        }
        return parsed.getScheme() + "://" + parsed.getRawAuthority();
    }

    /**
     * 对照 Go {@code getDefaultEndpoints}：丢掉 path，用 {@code <scheme>://<host>} 拼默认端点。
     */
    private static AuthServerMetadata getDefaultEndpoints(String url) {
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw OAuthProtocolException.of("failed to parse base URL: " + e.getMessage(), e);
        }
        if (isBlank(parsed.getScheme()) || isBlank(parsed.getRawAuthority())) {
            throw OAuthProtocolException.of("invalid base URL: missing scheme or host in \"" + url + "\"");
        }
        String authBaseUrl = parsed.getScheme() + "://" + parsed.getRawAuthority();
        return new AuthServerMetadata(authBaseUrl, authBaseUrl + "/authorize",
                authBaseUrl + "/token", authBaseUrl + "/register");
    }

    /** 对照 Go {@code getResourceURL}。 */
    public String getResourceUrl() {
        metadataLock.lock();
        try {
            return resourceUrl;
        } finally {
            metadataLock.unlock();
        }
    }

    // ── 静态工具（对照 Go 的包级函数） ───────────────────────────────────

    /**
     * 对照 Go {@code buildWellKnownURL}：well-known 段<b>插在 authority 与 path 之间</b>
     * （RFC 8414 §3 / RFC 9728 的路径插入语义），不是简单拼接。
     */
    static String buildWellKnownUrl(String baseUrl, String suffix) {
        URI parsed;
        try {
            parsed = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw OAuthProtocolException.of("failed to parse base URL: " + e.getMessage(), e);
        }
        if (isBlank(parsed.getScheme()) || isBlank(parsed.getRawAuthority())) {
            throw OAuthProtocolException.of(
                    "invalid base URL: missing scheme or host in \"" + baseUrl + "\"");
        }
        String path = trimTrailingSlash(parsed.getRawPath() == null ? "" : parsed.getRawPath());
        String root = parsed.getScheme() + "://" + parsed.getRawAuthority();
        if (path.isEmpty() || "/".equals(path)) {
            return root + "/.well-known/" + suffix;
        }
        return root + "/.well-known/" + suffix + path;
    }

    /**
     * 对照 Go {@code authorizationServerMetadataURLs}：给定 issuer 的候选发现地址有序列表。
     * issuer 无路径时两个候选（RFC 8414 + OIDC）；有路径时三个（含 OIDC 的
     * {@code <path>/.well-known/openid-configuration} 变体）。
     */
    static List<String> authorizationServerMetadataUrls(String issuerUrl) {
        List<String> urls = new ArrayList<>();
        URI parsed;
        try {
            parsed = new URI(issuerUrl);
        } catch (URISyntaxException e) {
            return urls;
        }
        if (isBlank(parsed.getScheme()) || isBlank(parsed.getRawAuthority())) {
            return urls;
        }
        String root = parsed.getScheme() + "://" + parsed.getRawAuthority();
        String originalPath = trimSlashes(parsed.getPath() == null ? "" : parsed.getPath());
        if (originalPath.isEmpty()) {
            urls.add(root + "/.well-known/oauth-authorization-server");
            urls.add(root + "/.well-known/openid-configuration");
            return urls;
        }
        urls.add(root + "/.well-known/oauth-authorization-server/" + originalPath);
        urls.add(root + "/.well-known/openid-configuration/" + originalPath);
        urls.add(root + "/" + originalPath + "/.well-known/openid-configuration");
        return urls;
    }

    /**
     * 对照 Go {@code validateAuthServerMetadataURLs}：每个 URL 型字段必须 http/https 且带 host。
     * 空的可选字段放行。
     */
    static void validateAuthServerMetadataUrls(AuthServerMetadata m) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("issuer", m.issuer());
        fields.put("authorization_endpoint", m.authorizationEndpoint());
        fields.put("token_endpoint", m.tokenEndpoint());
        fields.put("jwks_uri", m.jwksUri());
        fields.put("registration_endpoint", m.registrationEndpoint());
        fields.put("service_documentation", m.serviceDocumentation());
        fields.put("op_policy_uri", m.opPolicyUri());
        fields.put("op_tos_uri", m.opTosUri());
        fields.put("revocation_endpoint", m.revocationEndpoint());
        fields.put("introspection_endpoint", m.introspectionEndpoint());
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            URI u;
            try {
                u = new URI(e.getValue());
            } catch (URISyntaxException ex) {
                throw OAuthProtocolException.of(e.getKey() + ": " + ex.getMessage());
            }
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw OAuthProtocolException.of(
                        e.getKey() + " has disallowed scheme \"" + u.getScheme() + "\"");
            }
            if (isBlank(u.getRawAuthority())) {
                throw OAuthProtocolException.of(e.getKey() + " is missing host");
            }
        }
    }

    /**
     * 对照 Go {@code resourceIdentifiersEqual}：scheme/host 大小写不敏感；
     * 路径比 {@code EscapedPath}（保留百分号编码语义）；<b>两侧各去掉一个尾部斜杠</b>
     * （真实部署常带或不带，判不等会误杀合法服务器）；query/fragment/userinfo 参与比较。
     * 不可解析时退回字符串精确比较。
     */
    static boolean resourceIdentifiersEqual(String a, String b) {
        URI ua;
        URI ub;
        try {
            ua = new URI(a);
            ub = new URI(b);
        } catch (URISyntaxException e) {
            return a.equals(b);
        }
        if (!equalsIgnoreCase(ua.getScheme(), ub.getScheme())) {
            return false;
        }
        if (!equalsIgnoreCase(ua.getRawAuthority(), ub.getRawAuthority())) {
            return false;
        }
        String pathA = ua.getRawPath() == null ? "" : ua.getRawPath();
        String pathB = ub.getRawPath() == null ? "" : ub.getRawPath();
        if (!trimTrailingSlash(pathA).equals(trimTrailingSlash(pathB))) {
            return false;
        }
        if (!equalsNn(ua.getRawQuery(), ub.getRawQuery())) {
            return false;
        }
        if (!equalsNn(ua.getRawFragment(), ub.getRawFragment())) {
            return false;
        }
        return equalsNn(ua.getRawUserInfo(), ub.getRawUserInfo());
    }

    /** 对照 Go {@code extractOAuthError}：结构化错误优先，否则回落到 "with status N: <body>"。 */
    static OAuthProtocolException extractOAuthError(String body, int statusCode, String context) {
        OAuthError parsed = parseOAuthError(body);
        if (parsed != null) {
            return OAuthProtocolException.ofOAuthError(context, parsed);
        }
        return OAuthProtocolException.ofRawStatus(context, statusCode, body);
    }

    private static OAuthError parseOAuthError(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            OAuthError error = MAPPER.readValue(body, OAuthError.class);
            return error.isPresent() ? error : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static OAuthToken parseToken(String body) {
        try {
            return MAPPER.readValue(body, OAuthToken.class);
        } catch (Exception e) {
            throw OAuthProtocolException.of("failed to decode token response: " + e.getMessage(), e);
        }
    }

    /**
     * 对照 Go {@code url.Values.Encode()}：键<b>按字典序</b>输出，空格编成 {@code +}，
     * 非保留字符含 {@code ~} 不编码。JDK 的 {@code URLEncoder} 会把 {@code ~} 编成
     * {@code %7E} 且对 {@code *} 的处理不同，故这里自实现以保证字节级一致。
     */
    static String encodeForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(queryEscape(e.getKey())).append('=').append(queryEscape(e.getValue()));
        }
        return sb.toString();
    }

    /** 对照 Go {@code url.QueryEscape}。 */
    static String queryEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte raw : (s == null ? "" : s).getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    /** 对照 Go {@code strings.TrimSuffix(p, "/")}：<b>最多</b>去掉一个尾部斜杠。 */
    private static String trimTrailingSlash(String s) {
        if (s.length() > 0 && s.charAt(s.length() - 1) == '/') {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String trimSlashes(String s) {
        String out = s;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.equalsIgnoreCase(y);
    }

    private static boolean equalsNn(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static String stringOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
