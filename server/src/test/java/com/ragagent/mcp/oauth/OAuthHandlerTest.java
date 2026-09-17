package com.ragagent.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.protocol.McpContext;
import com.ragagent.mcp.protocol.McpServiceUrls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 对照 mcp-go {@code client/transport/oauth_test.go} 中本项目用到的协议行为，
 * 以及 {@code oauth_utils_test.go}。
 *
 * <p>重点钉住四件事：PKCE/state 的生成口径、发现链的候选顺序与路径插入语义、
 * 授权 URL 的字节级形态（Go {@code url.Values.Encode()} 的字典序）、
 * 以及 <b>CSRF expected-state 的跨请求重建语义</b>（{@code SetExpectedState}）。</p>
 */
class OAuthHandlerTest {

    private OAuthServerStub server;

    @BeforeEach
    void setUp() throws Exception {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1");
        McpServiceUrls.setSsrfGuard(guard);
        server = new OAuthServerStub();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        McpServiceUrls.setSsrfGuard(new SsrfGuard());
    }

    // ── PKCE / state ───────────────────────────────────────────────────

    /** 对照 {@code oauth_utils_test.go}：verifier 64 字符、state 32 字符、challenge=S256。 */
    @Test
    void pkceGenerationMatchesRfc7636() throws Exception {
        String verifier = Pkce.generateCodeVerifier();
        assertEquals(64, verifier.length());

        String expectedChallenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedChallenge, Pkce.generateCodeChallenge(verifier),
                "code_challenge 必须是 base64url(SHA-256(verifier))");
        assertFalse(Pkce.generateCodeChallenge(verifier).contains("="), "无填充");

        assertEquals(32, Pkce.generateState().length());
        assertNotEquals(Pkce.generateState(), Pkce.generateState(), "state 必须每次都不同");
    }

    // ── 发现链的静态形状 ────────────────────────────────────────────────

    /** 对照 Go {@code buildWellKnownURL} 的<b>路径插入</b>语义（不是简单拼接）。 */
    @Test
    void wellKnownUrlInsertsSegmentBetweenAuthorityAndPath() {
        assertEquals("https://host/.well-known/oauth-protected-resource",
                OAuthHandler.buildWellKnownUrl("https://host", "oauth-protected-resource"));
        assertEquals("https://host/.well-known/oauth-protected-resource",
                OAuthHandler.buildWellKnownUrl("https://host/", "oauth-protected-resource"));
        assertEquals("https://host/.well-known/oauth-protected-resource/mcp",
                OAuthHandler.buildWellKnownUrl("https://host/mcp", "oauth-protected-resource"));
    }

    /** 对照 Go {@code authorizationServerMetadataURLs} 的候选顺序。 */
    @Test
    void authorizationServerMetadataUrlsFollowMcpSpecOrder() {
        assertEquals(List.of(
                        "https://host/.well-known/oauth-authorization-server",
                        "https://host/.well-known/openid-configuration"),
                OAuthHandler.authorizationServerMetadataUrls("https://host"));

        assertEquals(List.of(
                        "https://host/.well-known/oauth-authorization-server/tenant/a",
                        "https://host/.well-known/openid-configuration/tenant/a",
                        "https://host/tenant/a/.well-known/openid-configuration"),
                OAuthHandler.authorizationServerMetadataUrls("https://host/tenant/a"));
    }

    /** 对照 Go {@code resourceIdentifiersEqual}：尾部单斜杠忽略，其余分量显著。 */
    @Test
    void resourceIdentifierComparison() {
        assertTrue(OAuthHandler.resourceIdentifiersEqual("https://Host/mcp", "https://host/mcp/"));
        assertFalse(OAuthHandler.resourceIdentifiersEqual("https://host/mcp", "https://host/other"));
        assertFalse(OAuthHandler.resourceIdentifiersEqual("https://host/mcp?a=1", "https://host/mcp"));
        // 不可解析 → 退回字符串精确比较
        assertTrue(OAuthHandler.resourceIdentifiersEqual("not a url", "not a url"));
        assertFalse(OAuthHandler.resourceIdentifiersEqual("not a url", "not a url2"));
    }

    /** 对照 Go {@code url.Values.Encode()}：键按字典序，空格编成 {@code +}，{@code ~} 不编码。 */
    @Test
    void formEncodingMatchesGoUrlValues() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("state", "s1");
        params.put("client_id", "c1");
        params.put("redirect_uri", "https://app/x?a=b&c=d");
        params.put("scope", "read write");
        params.put("response_type", "code");

        assertEquals("client_id=c1&redirect_uri=https%3A%2F%2Fapp%2Fx%3Fa%3Db%26c%3Dd"
                        + "&response_type=code&scope=read+write&state=s1",
                OAuthHandler.encodeForm(params));
        assertEquals("~a.b-c_d", OAuthHandler.queryEscape("~a.b-c_d"));
    }

    // ── 元数据 URL 校验 ────────────────────────────────────────────────

    /** 对照 Go {@code validateAuthServerMetadataURLs}：拒绝非 http(s) 与缺 host。 */
    @Test
    void rejectsDisallowedMetadataSchemes() {
        AuthServerMetadata metadata = new AuthServerMetadata("https://host",
                "javascript:alert(1)", "https://host/token", "");
        OAuthProtocolException e = assertThrows(OAuthProtocolException.class,
                () -> OAuthHandler.validateAuthServerMetadataUrls(metadata));
        assertTrue(e.getMessage().contains("disallowed scheme"), e.getMessage());

        AuthServerMetadata missingHost = new AuthServerMetadata("https://host",
                "https://host/a", "file:///etc/passwd", "");
        assertThrows(OAuthProtocolException.class,
                () -> OAuthHandler.validateAuthServerMetadataUrls(missingHost));
    }

    // ── 授权 URL ───────────────────────────────────────────────────────

    /** 授权 URL 必须带 response_type/state/PKCE S256，并记录 expected state。 */
    @Test
    void authorizationUrlCarriesPkceAndRecordsExpectedState() {
        OAuthHandler handler = newHandler(Map.of("client_id", "c1"));

        String url = handler.getAuthorizationUrl(McpContext.none(), "state-1", "challenge-1");

        assertTrue(url.startsWith(server.url("/authorize") + "?"), url);
        assertTrue(url.contains("response_type=code"), url);
        assertTrue(url.contains("client_id=c1"), url);
        assertTrue(url.contains("state=state-1"), url);
        assertTrue(url.contains("code_challenge=challenge-1"), url);
        assertTrue(url.contains("code_challenge_method=S256"), url);
        assertTrue(url.contains("scope=read+write"), url);
        assertEquals("state-1", handler.getExpectedState());
    }

    // ── code 交换 ──────────────────────────────────────────────────────

    /**
     * 对照 {@code ProcessAuthorizationResponse} 的 CSRF 校验：
     * 期望值不匹配 / 期望值为空都必须被拒。
     */
    @Test
    void processesCodeExchangeAndEnforcesCsrf() {
        OAuthHandler handler = newHandler(Map.of("client_id", "c1"));
        server.tokenBody = Map.of("access_token", "at", "refresh_token", "rt",
                "token_type", "Bearer", "expires_in", 3600);

        // 期望值为空（回调请求重建了 handler）→ 明确报"流程未正确发起"
        OAuthProtocolException noState = assertThrows(OAuthProtocolException.class,
                () -> handler.processAuthorizationResponse(McpContext.none(), "code", "s1", "v1"));
        assertTrue(noState.getMessage().contains("no expected state found"), noState.getMessage());

        // 期望值不匹配 → CSRF 哨兵文案
        handler.setExpectedState("expected");
        OAuthProtocolException mismatch = assertThrows(OAuthProtocolException.class,
                () -> handler.processAuthorizationResponse(McpContext.none(), "code", "other", "v1"));
        assertEquals(OAuthHandler.INVALID_STATE_MESSAGE, mismatch.getMessage());

        // 匹配 → 校验通过，且交换后期望值被清空（同一 handler 不能复用）
        handler.setExpectedState("s1");
        handler.processAuthorizationResponse(McpContext.none(), "code", "s1", "v1");
        assertEquals("", handler.getExpectedState());
        assertEquals("authorization_code", server.lastTokenForm().get("grant_type"));
        assertEquals("v1", server.lastTokenForm().get("code_verifier"), "PKCE verifier 必须发出去");
        assertEquals("c1", server.lastTokenForm().get("client_id"));

        assertThrows(OAuthProtocolException.class,
                () -> handler.processAuthorizationResponse(McpContext.none(), "code", "s1", "v1"));
    }

    /**
     * <b>关键 hack 的对等测试</b>：跨请求重建 handler 后，
     * 只有显式 {@code setExpectedState(state)} 才能让交换通过——这正是
     * {@code OAuthManager.CompleteAuthorization} 必须保留那一行的原因。
     */
    @Test
    void setExpectedStateMakesRebuiltHandlerAcceptTheCallback() {
        OAuthHandler rebuilt = newHandler(Map.of("client_id", "c1"));
        server.tokenBody = Map.of("access_token", "at", "token_type", "Bearer", "expires_in", 60);

        assertThrows(OAuthProtocolException.class,
                () -> rebuilt.processAuthorizationResponse(McpContext.none(), "code", "s1", "v1"));

        rebuilt.setExpectedState("s1");
        rebuilt.processAuthorizationResponse(McpContext.none(), "code", "s1", "v1");
        assertEquals(1, server.tokenRequests());
    }

    /** GitHub 兼容：HTTP 200 里带 error 字段也必须当失败。 */
    @Test
    void httpTwoHundredWithErrorBodyIsAFailure() {
        OAuthHandler handler = newHandler(Map.of("client_id", "c1"));
        handler.setExpectedState("s1");
        server.tokenBody = Map.of("error", "invalid_grant",
                "error_description", "code expired");

        OAuthProtocolException e = assertThrows(OAuthProtocolException.class,
                () -> handler.processAuthorizationResponse(McpContext.none(), "code", "s1", "v1"));
        assertTrue(e.getMessage().contains("invalid_grant"), e.getMessage());
    }

    /** 非 2xx 且体不是 OAuth 错误 → 兜底文案（永久失败判定依赖里头的 "status 400"）。 */
    @Test
    void nonOAuthErrorBodyFallsBackToStatusMessage() {
        OAuthHandler handler = newHandler(Map.of("client_id", "c1"));
        handler.setExpectedState("s1");
        server.tokenStatus = 400;
        server.tokenBody = Map.of();

        OAuthProtocolException e = assertThrows(OAuthProtocolException.class,
                () -> handler.processAuthorizationResponse(McpContext.none(), "code", "s1", "v1"));
        assertTrue(e.getMessage().contains("status 400"), e.getMessage());
    }

    // ── 动态客户端注册 ─────────────────────────────────────────────────

    /** 对照 Go {@code RegisterClient}：公共客户端用 token_endpoint_auth_method=none。 */
    @Test
    void registersPublicClientAndAdoptsClientId() {
        OAuthHandler handler = newHandler(Map.of("client_id", ""));
        server.registerBody = Map.of("client_id", "dyn-9", "client_secret", "sec-9");
        server.registerStatus = 201;

        handler.registerClient(McpContext.none(), "WeKnora");

        assertEquals(1, server.registerRequests());
        assertEquals("WeKnora", server.lastRegisterBody().get("client_name"));
        assertEquals("none", server.lastRegisterBody().get("token_endpoint_auth_method"));
        assertEquals("dyn-9", handler.getClientId(), "client_id 必须就地回填");
        assertEquals("sec-9", handler.getClientSecret());
    }

    /**
     * 显式配置的 metadata URL 拿不到元数据时必须明确失败。
     *
     * <p>Go 在这个分支会返回 {@code (nil, nil)}，调用方随后解引用空指针 panic
     * （gin recovery → 500）。Java 侧改为同名语义的显式异常（仍是对外 500），
     * 见 {@code OAuthHandler.discover} 的注释。</p>
     */
    @Test
    void explicitMetadataUrlThatFailsDiscoveryIsAnError() {
        OAuthHandler handler = newHandler(Map.of("client_id", ""));
        server.metadataAvailable = false;

        OAuthProtocolException e = assertThrows(OAuthProtocolException.class,
                () -> handler.registerClient(McpContext.none(), "WeKnora"));
        assertTrue(e.getMessage().contains("failed to load authorization server metadata"),
                e.getMessage());
        assertEquals(0, server.registerRequests(), "元数据都没有，不该去打注册端点");
    }

    /** 元数据里没有 registration_endpoint 时报"不支持动态注册"（对照 Go 文案）。 */
    @Test
    void registrationWithoutEndpointFails() {
        server.includeRegistrationEndpoint = false;
        OAuthHandler handler = newHandler(Map.of("client_id", ""));

        OAuthProtocolException e = assertThrows(OAuthProtocolException.class,
                () -> handler.registerClient(McpContext.none(), "WeKnora"));
        assertEquals("server does not support dynamic client registration", e.getMessage());
    }

    // ── 刷新 ───────────────────────────────────────────────────────────

    /** 服务器不回新 refresh token 时必须沿用旧的（轮换型服务器的常见形态）。 */
    @Test
    void refreshKeepsOldRefreshTokenWhenServerOmitsIt() {
        OAuthHandler handler = newHandler(Map.of("client_id", "c1"));
        server.tokenBody = Map.of("access_token", "new", "token_type", "Bearer", "expires_in", 60);

        OAuthToken token = handler.refreshToken(McpContext.none(), "keep-me");

        assertEquals("new", token.accessToken());
        assertEquals("keep-me", token.refreshToken());
    }

    /** 对照 Go {@code getValidToken}：没有 token 时抛"需要授权"并携带 handler。 */
    @Test
    void authorizationHeaderRequiresAToken() {
        OAuthHandler handler = newHandler(Map.of("client_id", "c1"));
        OAuthAuthorizationRequiredException e = assertThrows(
                OAuthAuthorizationRequiredException.class,
                () -> handler.getAuthorizationHeader(McpContext.none()));
        assertEquals(handler, e.handler(), "401 异常必须携带 handler，供运行期做强制刷新");
    }

    /** 有 token 时 Authorization 头形如 {@code Bearer <access>}。 */
    @Test
    void authorizationHeaderUsesStoredToken() {
        FakeOAuthRepository repo = new FakeOAuthRepository();
        var principal = new com.ragagent.common.context.TenantContext.Principal(
                com.ragagent.mcp.domain.McpPrincipal.WEB_USER, "u1");
        DbTokenStore store = new DbTokenStore(repo, 1, principal, "svc");
        store.saveToken(McpContext.none(), new OAuthToken("tok", "", "bearer", null));

        OAuthHandler handler = newHandler(Map.of("client_id", "c1", "tokenStore", store));
        assertEquals("Bearer tok", handler.getAuthorizationHeader(McpContext.none()),
                "RFC 6749 §5.1：token_type 大小写不敏感，归一为 Bearer");
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** 造一个以 stub 为授权服务器的 handler；{@code overrides} 可覆写 clientId/tokenStore。 */
    private OAuthHandler newHandler(Map<String, Object> overrides) {
        OAuthConfig cfg = new OAuthConfig()
                .clientId((String) overrides.getOrDefault("client_id", "c1"))
                .scopes(List.of("read", "write"))
                .authServerMetadataUrl(server.url("/metadata"))
                .tokenStore((OAuthTokenStore) overrides.getOrDefault("tokenStore",
                        new DbTokenStore(new FakeOAuthRepository(), 1,
                                new com.ragagent.common.context.TenantContext.Principal(
                                        com.ragagent.mcp.domain.McpPrincipal.WEB_USER, "u1"),
                                "svc")));
        OAuthHandler handler = new OAuthHandler(cfg);
        handler.setBaseUrl(server.url());
        return handler;
    }
}
