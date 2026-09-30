package com.ragagent.mcp.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.ragagent.common.approval.ApprovalException;
import com.ragagent.common.approval.Decision;
import com.ragagent.common.approval.Gate;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GlobalExceptionHandler;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.ragagent.mcp.oauth.OAuthManager;
import com.ragagent.mcp.oauth.OAuthStateStore;
import com.ragagent.mcp.oauth.FakeOAuthRepository;
import com.ragagent.mcp.oauth.OAuthServerStub;
import com.ragagent.mcp.protocol.McpClientManager;
import com.ragagent.mcp.protocol.McpServiceUrls;
import com.ragagent.mcp.service.McpServiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MCP OAuth HTTP 层契约（对照 Go internal/handler/mcp_oauth.go）。
 *
 * <p>用 standalone MockMvc（不装 Filter 链）：鉴权/RBAC 由 AuthFilter 与 RbacInterceptor
 * 承担，路由注册是主会话的工作，本用例只钉住 <b>controller 自身的端点契约</b>：
 * 响应信封、状态码、重定向 fragment 的字节形态。</p>
 *
 * <p>{@code Gate} 与 {@code McpClientManager} 都还没有 Spring bean，故用
 * {@link DefaultListableBeanFactory} 造 {@code ObjectProvider}：不注册 bean 即
 * 等价 Go 的 {@code h.gate == nil} 分支。</p>
 */
class McpOAuthControllerTest {

    private static final long TENANT_ID = 7L;
    private static final String SERVICE_ID = "svc-1";
    private static final TenantContext.Principal PRINCIPAL =
            new TenantContext.Principal(McpPrincipal.WEB_USER, "user-1");

    private OAuthServerStub server;
    private FakeOAuthRepository repo;
    private McpServiceService svc;
    private McpService service;
    private OAuthManager manager;

    private MockMvc mockMvc;

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
        service = oauthService(server.url(), server.url("/metadata"));
        McpServiceMapper serviceMapper = mock(McpServiceMapper.class);
        when(serviceMapper.getByIdForTenant(TENANT_ID, SERVICE_ID)).thenReturn(service);
        manager = new OAuthManager(repo, serviceMapper, new OAuthStateStore(null));

        svc = mock(McpServiceService.class);
        // 用 doXxx().when(...) 而不是 when(...)：when(...) 会真的调用 mock 方法，
        // 若该方法已被"抛异常"的桩命中，桩注册本身就会抛。
        // 另：宽匹配的桩先注册，具体桩后注册才能覆盖它（Mockito 后注册者优先）。
        doThrow(BizException.notFound("MCP service not found"))
                .when(svc).getMCPServiceByID(anyLong(), anyString());
        doReturn(service).when(svc).getMCPServiceByID(TENANT_ID, SERVICE_ID);

        TenantContext.set(TENANT_ID, PRINCIPAL, "admin", false, "user-1", false);
        TenantContext.setEmbedVisitorId(null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (server != null) {
            server.close();
        }
        McpServiceUrls.setSsrfGuard(new SsrfGuard());
    }

    private MockMvc mvc(Gate gate, McpClientManager clientManager) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (gate != null) {
            beans.registerSingleton("gate", gate);
        }
        if (clientManager != null) {
            beans.registerSingleton("mcpClientManager", clientManager);
        }
        McpOAuthController controller = new McpOAuthController(manager, svc,
                beans.getBeanProvider(McpClientManager.class),
                beans.getBeanProvider(Gate.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── 1. authorize-url ──────────────────────────────────────────────

    @Test
    void authorizeUrlReturnsUrlAndAttempt() throws Exception {
        mvc(null, null).perform(post("/api/v1/mcp-services/" + SERVICE_ID + "/oauth/authorize-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uri\":\"https://app.example.com/api/v1/mcp-oauth/callback\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.authorization_url").exists())
                .andExpect(jsonPath("$.data.authorization_attempt").exists());
    }

    @Test
    void authorizeUrlRequiresRedirectUri() throws Exception {
        mvc(null, null).perform(post("/api/v1/mcp-services/" + SERVICE_ID + "/oauth/authorize-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uri\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("redirect_uri is required"));
    }

    @Test
    void authorizeUrlRejectsNonOAuthService() throws Exception {
        McpAuthConfig apiKey = new McpAuthConfig();
        apiKey.setAuthType(McpAuthType.API_KEY);
        service.setAuthConfig(apiKey);

        mvc(null, null).perform(post("/api/v1/mcp-services/" + SERVICE_ID + "/oauth/authorize-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uri\":\"https://app/callback\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value("MCP service is not configured to use OAuth"));
    }

    @Test
    void authorizeUrlReturns404ForUnknownService() throws Exception {
        mvc(null, null).perform(post("/api/v1/mcp-services/nope/oauth/authorize-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uri\":\"https://app/callback\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("MCP service not found"));
    }

    @Test
    void authorizeUrlRequiresAuthentication() throws Exception {
        TenantContext.clear();
        mvc(null, null).perform(post("/api/v1/mcp-services/" + SERVICE_ID + "/oauth/authorize-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uri\":\"https://app/callback\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("authentication required"));
    }

    // ── 2. 公开回调 ────────────────────────────────────────────────────

    @Test
    void callbackReportsProviderError() throws Exception {
        mvc(null, null).perform(get("/api/v1/mcp-oauth/callback").param("error", "access denied"))
                .andExpect(status().isFound())
                // urlQueryEscape：空格 → %20
                .andExpect(header().string("Location", "/#mcp_oauth_error=access%20denied"));
    }

    @Test
    void callbackRequiresCodeAndState() throws Exception {
        mvc(null, null).perform(get("/api/v1/mcp-oauth/callback").param("state", "s1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "/#mcp_oauth_error=missing_code_or_state"));
    }

    @Test
    void callbackWithUnknownStateFails() throws Exception {
        mvc(null, null).perform(get("/api/v1/mcp-oauth/callback")
                        .param("state", "unknown").param("code", "c1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "/#mcp_oauth_error=authorization_failed"));
    }

    /** 成功路径：回调带 fragment 重定向回前端登记的地址。 */
    @Test
    void callbackRedirectsToFrontendWithSuccessFragment() throws Exception {
        OAuthManager.StartResult start = manager.startAuthorization(service, TENANT_ID, PRINCIPAL,
                "https://app.example.com/api/v1/mcp-oauth/callback", "/mcp-settings");

        mvc(null, null).perform(get("/api/v1/mcp-oauth/callback")
                        .param("state", start.attemptId()).param("code", "the-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/mcp-settings#mcp_oauth_result=success"));
    }

    // ── 3. status ─────────────────────────────────────────────────────

    @Test
    void statusWithoutAttemptReturnsLifecycleState() throws Exception {
        mvc(null, null).perform(get("/api/v1/mcp-services/" + SERVICE_ID + "/oauth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.authorized").value(false))
                .andExpect(jsonPath("$.data.state").value("reauth_required"))
                .andExpect(jsonPath("$.data.refresh_available").value(false));
    }

    @Test
    void statusWithAttemptStaysPendingUntilCallback() throws Exception {
        OAuthManager.StartResult start = manager.startAuthorization(service, TENANT_ID, PRINCIPAL,
                "https://app.example.com/api/v1/mcp-oauth/callback", "/");

        mvc(null, null).perform(get("/api/v1/mcp-services/" + SERVICE_ID + "/oauth/status")
                        .param("authorization_attempt", start.attemptId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorized").value(false))
                .andExpect(jsonPath("$.data.state").value("pending"));
    }

    /** 回调完成后再查：同一 attempt 变为 authorized（历史 token 不参与）。 */
    @Test
    void statusWithAttemptFlipsToAuthorizedAfterCallback() throws Exception {
        OAuthManager.StartResult start = manager.startAuthorization(service, TENANT_ID, PRINCIPAL,
                "https://app.example.com/api/v1/mcp-oauth/callback", "/");
        manager.completeAuthorization(start.attemptId(), "the-code");

        mvc(null, null).perform(get("/api/v1/mcp-services/" + SERVICE_ID + "/oauth/status")
                        .param("authorization_attempt", start.attemptId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorized").value(true))
                .andExpect(jsonPath("$.data.state").value("authorized"));
    }

    // ── 4. revoke ─────────────────────────────────────────────────────

    @Test
    void revokeReturns204() throws Exception {
        OAuthManager.StartResult start = manager.startAuthorization(service, TENANT_ID, PRINCIPAL,
                "https://app.example.com/api/v1/mcp-oauth/callback", "/");
        manager.completeAuthorization(start.attemptId(), "the-code");

        mvc(null, null).perform(delete("/api/v1/mcp-services/" + SERVICE_ID + "/oauth/token"))
                .andExpect(status().isNoContent());
        assertEquals(false, manager.isAuthorized(TENANT_ID, PRINCIPAL, SERVICE_ID));
    }

    // ── 5. 会话内挂起 / 取消 ────────────────────────────────────────────

    @Test
    void resolveWithoutGateIsServerError() throws Exception {
        mvc(null, null).perform(post("/api/v1/agent/mcp-oauth-resolutions/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"" + SERVICE_ID + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.message").value("OAuth gate is not configured"));
    }

    @Test
    void cancelWithoutGateIsServerError() throws Exception {
        mvc(null, null).perform(
                        post("/api/v1/agent/mcp-oauth-resolutions/p1/cancel"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.message").value("OAuth gate is not configured"));
    }

    @Test
    void resolveRequiresServiceId() throws Exception {
        mvc(mock(Gate.class), null).perform(post("/api/v1/agent/mcp-oauth-resolutions/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("service_id is required"));
    }

    @Test
    void resolveRejectsUnknownDecision() throws Exception {
        mvc(mock(Gate.class), null).perform(post("/api/v1/agent/mcp-oauth-resolutions/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"" + SERVICE_ID + "\",\"decision\":\"maybe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("decision must be authorize or cancel"));
    }

    /** 授权还没完成就恢复 → 409（免得工具调用再撞一次 authorization-required）。 */
    @Test
    void resolveRequiresCompletedAuthorization() throws Exception {
        mvc(mock(Gate.class), null).perform(post("/api/v1/agent/mcp-oauth-resolutions/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"" + SERVICE_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message")
                        .value("authorization not completed yet for this MCP service"));
    }

    /** 用户已持有 token → 放行，并把 pending 标记为批准。 */
    @Test
    void resolveSucceedsOnceAuthorizationCompleted() throws Exception {
        OAuthManager.StartResult start = manager.startAuthorization(service, TENANT_ID, PRINCIPAL,
                "https://app.example.com/api/v1/mcp-oauth/callback", "/");
        manager.completeAuthorization(start.attemptId(), "the-code");

        Gate gate = mock(Gate.class);
        mvc(gate, null).perform(post("/api/v1/agent/mcp-oauth-resolutions/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_id\":\"" + SERVICE_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        org.mockito.Mockito.verify(gate).resolve(anyLong(), anyString(), anyString(), any(Decision.class));
    }

    /** cancel 分支：不校验 token，直接把 pending 置为拒绝。 */
    @Test
    void cancelDeniesPending() throws Exception {
        Gate gate = mock(Gate.class);
        mvc(gate, null).perform(post("/api/v1/agent/mcp-oauth-resolutions/p1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        org.mockito.Mockito.verify(gate).resolve(anyLong(), anyString(), anyString(), any(Decision.class));
    }

    /** 对照 Go {@code mcp_oauth.go:311-360} 的四条错误映射。 */
    @Test
    void resolveMapsGateErrors() throws Exception {
        Gate gate = mock(Gate.class);
        doThrow(ApprovalException.pendingNotFound())
                .when(gate).resolve(anyLong(), anyString(), anyString(), any(Decision.class));

        mvc(gate, null).perform(post("/api/v1/agent/mcp-oauth-resolutions/p1/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message")
                        .value("pending authorization not found or already completed"));

        doThrow(ApprovalException.userMismatch())
                .when(gate).resolve(anyLong(), anyString(), anyString(), any(Decision.class));
        mvc(gate, null).perform(post("/api/v1/agent/mcp-oauth-resolutions/p1/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value("user mismatch: only the session owner may resolve this prompt"));
    }

    // ── 6. urlQueryEscape（对照 Go handler/auth.go:494-505） ─────────────

    @Test
    void urlQueryEscapeReplacesOnlyTheSevenCharacters() {
        assertEquals("a%25b%20c%23d%26e%2Bf%3Dg%3Fh",
                McpOAuthController.urlQueryEscape("a%b c#d&e+f=g?h"));
        assertEquals("plain", McpOAuthController.urlQueryEscape("plain"));
        assertEquals("", McpOAuthController.urlQueryEscape(null));
    }

    // ── fixture ────────────────────────────────────────────────────────

    private static McpService oauthService(String url, String metadataUrl) {
        McpService s = new McpService();
        s.setId(SERVICE_ID);
        s.setTenantId(TENANT_ID);
        s.setName("test-service");
        s.setUrl(url);
        s.setTransportType("http-streamable");
        McpAuthConfig authConfig = new McpAuthConfig();
        authConfig.setAuthType(McpAuthType.OAUTH);
        authConfig.setScopes(List.of("read", "write"));
        authConfig.setAuthServerMetadataUrl(metadataUrl);
        s.setAuthConfig(authConfig);
        return s;
    }
}
