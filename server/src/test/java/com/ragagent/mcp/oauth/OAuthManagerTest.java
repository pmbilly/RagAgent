package com.ragagent.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpOAuthClient;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.ragagent.mcp.protocol.McpServiceUrls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 端到端走一遍授权码流程（对照 Go {@code OAuthManager} 的行为，及
 * {@code oauth_manager} 相关用例的语义）。
 *
 * <p>覆盖：动态客户端注册 → 授权 URL → 回调 code 交换（PKCE）→ token 落库 →
 * attempt 完成 → 状态查询 → 撤销；以及"第二次授权复用已注册客户端"
 * 与"同一 state 只能回调一次"。</p>
 */
class OAuthManagerTest {

    private static final long TENANT_ID = 7L;
    private static final String SERVICE_ID = "svc-1";
    private static final String REDIRECT_URI =
            "https://app.example.com/api/v1/mcp-oauth/callback";
    private static final String FRONTEND_REDIRECT = "/mcp-settings";
    private static final TenantContext.Principal PRINCIPAL =
            new TenantContext.Principal(McpPrincipal.WEB_USER, "user-1");

    private OAuthServerStub server;
    private FakeOAuthRepository repo;
    private McpServiceMapper serviceMapper;
    private OAuthManager manager;
    private McpService service;

    @BeforeEach
    void setUp() throws Exception {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1");
        McpServiceUrls.setSsrfGuard(guard);

        server = new OAuthServerStub();
        server.registerBody = Map.of("client_id", "dyn-client-1");
        server.tokenBody = Map.of("access_token", "access-1", "refresh_token", "refresh-1",
                "token_type", "Bearer", "expires_in", 3600);

        repo = new FakeOAuthRepository();
        serviceMapper = mock(McpServiceMapper.class);
        service = oauthService(server.url(), server.url("/metadata"));
        when(serviceMapper.getByIdForTenant(TENANT_ID, SERVICE_ID)).thenReturn(service);

        manager = new OAuthManager(repo, serviceMapper, new OAuthStateStore(null));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        McpServiceUrls.setSsrfGuard(new SsrfGuard());
    }

    // ── 完整往返 ───────────────────────────────────────────────────────

    @Test
    void authorizationCodeRoundTrip() {
        OAuthManager.StartResult start = manager.startAuthorization(
                service, TENANT_ID, PRINCIPAL, REDIRECT_URI, FRONTEND_REDIRECT);

        // 1. 动态客户端注册恰好一次，且 client_id 被持久化复用
        assertEquals(1, server.registerRequests());
        assertEquals("WeKnora", server.lastRegisterBody().get("client_name"));
        McpOAuthClient persisted = repo.getClient(TENANT_ID, SERVICE_ID);
        assertNotNull(persisted);
        assertEquals("dyn-client-1", persisted.getClientId());
        assertEquals(REDIRECT_URI, persisted.getRedirectUri());

        // 2. 授权 URL：PKCE + 注册来的 client_id + 一次性 state
        String authUrl = start.authorizationUrl();
        assertTrue(authUrl.startsWith(server.url("/authorize") + "?"), authUrl);
        assertTrue(authUrl.contains("client_id=dyn-client-1"), authUrl);
        assertTrue(authUrl.contains("code_challenge_method=S256"), authUrl);
        assertTrue(authUrl.contains("scope=read+write"), authUrl);
        assertEquals(start.attemptId(), stateOf(authUrl));

        // 3. 尚未完成：attempt 存在但未完成，且不算已授权（历史 token 不算数）
        assertFalse(manager.states().attempt(start.attemptId()).completed());
        assertFalse(manager.isAuthorized(TENANT_ID, PRINCIPAL, SERVICE_ID));

        // 4. 回调 → code 交换（PKCE verifier 必须发出去）
        OAuthManager.CompleteResult done =
                manager.completeAuthorization(start.attemptId(), "the-code");
        assertEquals(FRONTEND_REDIRECT, done.frontendRedirect());
        assertEquals(SERVICE_ID, done.serviceId());
        assertEquals(1, server.tokenRequests());
        assertEquals("authorization_code", server.lastTokenForm().get("grant_type"));
        assertEquals("the-code", server.lastTokenForm().get("code"));
        assertEquals(REDIRECT_URI, server.lastTokenForm().get("redirect_uri"));
        assertEquals("dyn-client-1", server.lastTokenForm().get("client_id"));
        assertNotNull(server.lastTokenForm().get("code_verifier"), "PKCE verifier 必须带上");

        // 5. token 落库到 (tenant, principal, service)
        McpOAuthToken row = repo.getTokenForPrincipal(TENANT_ID, PRINCIPAL, SERVICE_ID);
        assertNotNull(row);
        assertEquals("access-1", row.getAccessToken());
        assertEquals("refresh-1", row.getRefreshToken());
        assertEquals(McpPrincipal.WEB_USER, row.getPrincipalType());

        // 6. attempt 此刻才置完成，状态查询转为已授权
        assertTrue(manager.states().attempt(start.attemptId()).completed());
        assertTrue(manager.isAuthorized(TENANT_ID, PRINCIPAL, SERVICE_ID));
        assertEquals(OAuthAuthorizationStatus.STATE_AUTHORIZED,
                manager.authorizationStatus(TENANT_ID, PRINCIPAL, SERVICE_ID).state());
        assertTrue(manager.isAuthorizationAttemptComplete(
                TENANT_ID, PRINCIPAL, SERVICE_ID, start.attemptId()));

        // 7. 第二次授权复用已注册的客户端（不再打注册端点）
        OAuthManager.StartResult second = manager.startAuthorization(
                service, TENANT_ID, PRINCIPAL, REDIRECT_URI, FRONTEND_REDIRECT);
        assertEquals(1, server.registerRequests(), "客户端注册必须复用");
        assertTrue(second.authorizationUrl().contains("client_id=dyn-client-1"));

        // 8. 撤销后不再已授权
        manager.revoke(TENANT_ID, PRINCIPAL, SERVICE_ID);
        assertNull(repo.getTokenForPrincipal(TENANT_ID, PRINCIPAL, SERVICE_ID));
        assertFalse(manager.isAuthorized(TENANT_ID, PRINCIPAL, SERVICE_ID));
    }

    /** 同一个 state 只能回调一次（单次使用）：第二次必须带上前端地址报失败。 */
    @Test
    void callbackIsSingleUse() {
        OAuthManager.StartResult start = manager.startAuthorization(
                service, TENANT_ID, PRINCIPAL, REDIRECT_URI, FRONTEND_REDIRECT);
        manager.completeAuthorization(start.attemptId(), "the-code");

        OAuthCallbackException e = assertThrows(OAuthCallbackException.class,
                () -> manager.completeAuthorization(start.attemptId(), "the-code"));
        // state 已被消费，前端地址无从得知 → 空串（控制器回落 "/"）
        assertEquals("", e.frontendRedirect());
        assertEquals("", e.serviceId());
    }

    /** code 交换失败时仍要把前端地址与 serviceID 交回给控制器。 */
    @Test
    void failedExchangeStillReportsFrontendRedirect() {
        OAuthManager.StartResult start = manager.startAuthorization(
                service, TENANT_ID, PRINCIPAL, REDIRECT_URI, FRONTEND_REDIRECT);
        server.tokenStatus = 400;
        server.tokenBody = Map.of("error", "invalid_grant");

        OAuthCallbackException e = assertThrows(OAuthCallbackException.class,
                () -> manager.completeAuthorization(start.attemptId(), "bad-code"));
        assertEquals(FRONTEND_REDIRECT, e.frontendRedirect());
        assertEquals(SERVICE_ID, e.serviceId());
        assertTrue(e.getMessage().contains("token exchange failed"), e.getMessage());
        // 交换失败 → attempt 不得置完成（否则前端会误以为成功）
        assertFalse(manager.states().attempt(start.attemptId()).completed());
        assertFalse(manager.isAuthorized(TENANT_ID, PRINCIPAL, SERVICE_ID));
    }

    /** 非 OAuth 服务直接拒绝（对照 Go 文案）。 */
    @Test
    void nonOAuthServiceIsRejected() {
        McpService apiKeyService = oauthService(server.url(), server.url("/metadata"));
        McpAuthConfig apiKey = new McpAuthConfig();
        apiKey.setAuthType(McpAuthType.API_KEY);
        apiKeyService.setAuthConfig(apiKey);

        OAuthProtocolException e = assertThrows(OAuthProtocolException.class,
                () -> manager.startAuthorization(apiKeyService, TENANT_ID, PRINCIPAL,
                        REDIRECT_URI, FRONTEND_REDIRECT));
        assertTrue(e.getMessage().contains("does not use OAuth"), e.getMessage());
    }

    /** principal 缺失时拒绝（OAuth token 必须按身份隔离）。 */
    @Test
    void missingPrincipalIsRejected() {
        OAuthProtocolException e = assertThrows(OAuthProtocolException.class,
                () -> manager.startAuthorization(service, TENANT_ID, null,
                        REDIRECT_URI, FRONTEND_REDIRECT));
        assertTrue(e.getMessage().contains("principal context is required"), e.getMessage());
    }

    /** 服务 URL 缺失时拒绝（对照 Go {@code "MCP service URL is required for OAuth"}）。 */
    @Test
    void missingServiceUrlIsRejected() {
        McpService noUrl = oauthService(null, null);
        OAuthProtocolException e = assertThrows(OAuthProtocolException.class,
                () -> manager.startAuthorization(noUrl, TENANT_ID, PRINCIPAL,
                        REDIRECT_URI, FRONTEND_REDIRECT));
        assertEquals("MCP service URL is required for OAuth", e.getMessage());
    }

    /** 已过期但仍带 refresh token 的行使状态为 refreshable 而非 authorized。 */
    @Test
    void expiredRowIsRefreshableNotAuthorized() {
        McpOAuthToken row = new McpOAuthToken();
        row.setTenantId(TENANT_ID);
        row.setPrincipalType(PRINCIPAL.type());
        row.setPrincipalId(PRINCIPAL.id());
        row.setServiceId(SERVICE_ID);
        row.setAccessToken("stale");
        row.setRefreshToken("refresh");
        row.setExpiresAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        repo.seed(PRINCIPAL, row);

        OAuthAuthorizationStatus status =
                manager.authorizationStatus(TENANT_ID, PRINCIPAL, SERVICE_ID);
        assertFalse(status.authorized(), "过期行不算已授权");
        assertEquals(OAuthAuthorizationStatus.STATE_REFRESHABLE, status.state());
        assertTrue(status.refreshAvailable());
    }

    // ── fixture ────────────────────────────────────────────────────────

    private static McpService oauthService(String url, String metadataUrl) {
        McpService service = new McpService();
        service.setId(SERVICE_ID);
        service.setTenantId(TENANT_ID);
        service.setName("test-service");
        service.setUrl(url);
        service.setTransportType("http-streamable");
        McpAuthConfig authConfig = new McpAuthConfig();
        authConfig.setAuthType(McpAuthType.OAUTH);
        authConfig.setScopes(List.of("read", "write"));
        authConfig.setAuthServerMetadataUrl(metadataUrl);
        service.setAuthConfig(authConfig);
        return service;
    }

    /** 从授权 URL 里取 state 参数（对照 Go 测试里手工解析 {@code url.Parse}）。 */
    private static String stateOf(String url) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            if (pair.startsWith("state=")) {
                return pair.substring("state=".length());
            }
        }
        return "";
    }
}
