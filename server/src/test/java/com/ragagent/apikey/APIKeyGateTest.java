package com.ragagent.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.ragagent.apikey.domain.APIKeyCapability;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.apikey.filter.APIKeyGateInterceptor;
import com.ragagent.apikey.filter.APIKeyRouteAuthorizer;
import com.ragagent.apikey.filter.APIKeyRoutePolicy;
import com.ragagent.apikey.filter.AllowFileServeAPIKeyInterceptor;
import com.ragagent.apikey.filter.DenyAPIKeyPrincipalInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * API-Key 门禁的行为测试——逐条对照 Go internal/middleware/api_key_gate_test.go
 * （L1-327）。
 *
 * <p>把 gin 的 {@code engine.Use(a.Middleware()) + engine.Handle(method, path)} 换成
 * "直接驱动 {@link HandlerInterceptor}"：{@code preHandle} 的时序与
 * gin 中间件一致（路由已确定、handler 未执行），
 * {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} 在这里手工注入，
 * 充当 {@code c.FullPath()}。</p>
 */
class APIKeyGateTest {

    @AfterEach
    void clearScope() {
        com.ragagent.apikey.domain.APIKeyScopeContext.clear();
    }

    /** 对照 Go 的 {@code newTestAuthorizer()}（api_key_gate_test.go L12-22）。 */
    private static APIKeyRouteAuthorizer newTestAuthorizer() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        a.register("GET", "/api/v1/auth/me", APIKeyRoutePolicy.any());
        a.register("POST", "/api/v1/knowledge-bases/:id/knowledge/file",
                APIKeyRoutePolicy.fullAccess().withCapability(APIKeyCapability.INGEST));
        a.register("GET", "/api/v1/models",
                APIKeyRoutePolicy.fullAccess().withCapability(APIKeyCapability.MANAGE_MODELS));
        a.register("PUT", "/api/v1/tenants/kv/:key",
                APIKeyRoutePolicy.fullAccess().withCapability(APIKeyCapability.MANAGE_TENANT_SETTINGS));
        return a;
    }

    /**
     * 对照 Go 的 {@code runGate}：scope 为 null 表示 JWT 主体。
     *
     * @return 是否被放行（未进拦截器视为放行）
     */
    private static boolean runGate(APIKeyRouteAuthorizer authorizer, TenantAPIKeyScope scope,
                                   String method, String pattern) throws Exception {
        if (scope != null) {
            com.ragagent.apikey.domain.APIKeyScopeContext.set(scope);
        }
        MockHttpServletRequest request = new MockHttpServletRequest(method, concretePath(pattern));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = new APIKeyGateInterceptor(authorizer).preHandle(request, response, new Object());
        return allowed && response.getStatus() == 200;
    }

    /** 对照 Go 的 {@code concretePath}：把模板参数换成字面量。 */
    private static String concretePath(String template) {
        return switch (template) {
            case "/api/v1/knowledge-bases/:id/knowledge/file" -> "/api/v1/knowledge-bases/kb-1/knowledge/file";
            case "/api/v1/tenants/kv/:key" -> "/api/v1/tenants/kv/some-key";
            default -> template;
        };
    }

    @Test
    void gateJwtPassesThrough() throws Exception {
        // 无 scope = JWT 主体 → 永远放行，哪怕路由要求 full access
        assertThat(runGate(newTestAuthorizer(), null, "GET", "/api/v1/models")).isTrue();
    }

    @Test
    void gateDefaultDeny() throws Exception {
        // 未声明的路由对 full-access Key 也 default-deny
        APIKeyRouteAuthorizer a = newTestAuthorizer();
        com.ragagent.apikey.domain.APIKeyScopeContext.set(
                new TenantAPIKeyScope(0L, "tenant", true, null, null));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = new APIKeyGateInterceptor(a).preHandle(request, response, new Object());
        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"error\":\"Forbidden: API key scope does not allow this operation\"}");
    }

    @Test
    void gateAnyPolicyAllowsScopedKey() throws Exception {
        assertThat(runGate(newTestAuthorizer(), TenantAPIKeyScope.empty(), "GET", "/api/v1/auth/me"))
                .isTrue();
    }

    @Test
    void gateFullAccessAndCapabilityPolicies() throws Exception {
        APIKeyRouteAuthorizer a = newTestAuthorizer();
        TenantAPIKeyScope scoped = TenantAPIKeyScope.empty();
        TenantAPIKeyScope full = new TenantAPIKeyScope(0L, "tenant", true, null, null);
        TenantAPIKeyScope ingest = new TenantAPIKeyScope(0L, "tenant", false, null, List.of("ingest"));
        TenantAPIKeyScope models = new TenantAPIKeyScope(0L, "tenant", false, null, List.of("manage_models"));

        // 普通 scoped Key 不能写内容
        assertThat(runGate(a, scoped, "POST", "/api/v1/knowledge-bases/:id/knowledge/file")).isFalse();
        // full-access Key 能写
        assertThat(runGate(a, full, "POST", "/api/v1/knowledge-bases/:id/knowledge/file")).isTrue();
        // ingest 能力能写
        assertThat(runGate(a, ingest, "POST", "/api/v1/knowledge-bases/:id/knowledge/file")).isTrue();
        // 但 ingest 读不了模型管理路由
        assertThat(runGate(a, ingest, "GET", "/api/v1/models")).isFalse();
        // manage_models 能读
        assertThat(runGate(a, models, "GET", "/api/v1/models")).isTrue();
    }

    @Test
    void gateAnyOfCapabilities() throws Exception {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        a.register("POST", "/api/v1/sessions",
                APIKeyRoutePolicy.fullAccess().withCapability(APIKeyCapability.CHAT));
        a.register("GET", "/api/v1/agents", APIKeyRoutePolicy.fullAccess()
                .withCapability(APIKeyCapability.CHAT)
                .withCapability(APIKeyCapability.MANAGE_AGENTS));
        a.register("POST", "/api/v1/agents",
                APIKeyRoutePolicy.fullAccess().withCapability(APIKeyCapability.MANAGE_AGENTS));
        a.register("PUT", "/api/v1/knowledge-bases/:id",
                APIKeyRoutePolicy.fullAccess().withCapability(APIKeyCapability.MANAGE_KBS));

        TenantAPIKeyScope chat = new TenantAPIKeyScope(0L, "tenant", false, null, List.of("chat"));
        TenantAPIKeyScope manage = new TenantAPIKeyScope(0L, "tenant", false, null, List.of("manage_agents"));
        TenantAPIKeyScope manageKbs = new TenantAPIKeyScope(0L, "tenant", false, null, List.of("manage_kbs"));

        // any-of：任一能力满足即可
        assertThat(runGate(a, chat, "GET", "/api/v1/agents")).isTrue();
        assertThat(runGate(a, manage, "GET", "/api/v1/agents")).isTrue();
        // 只有 manage_agents 能编排 agent
        assertThat(runGate(a, chat, "POST", "/api/v1/agents")).isFalse();
        assertThat(runGate(a, manage, "POST", "/api/v1/agents")).isTrue();
        // 只有 manage_kbs 能管知识库元数据/配置
        assertThat(runGate(a, manage, "PUT", "/api/v1/knowledge-bases/:id")).isFalse();
        assertThat(runGate(a, manageKbs, "PUT", "/api/v1/knowledge-bases/:id")).isTrue();
    }

    @Test
    void gateKbScopeDoesNotBlockDataPlane() throws Exception {
        // KB 受限的 Key **不**被门禁拦住（白名单由下游 KBAccess/handler 判定）
        TenantAPIKeyScope restricted = new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1"), List.of("ingest"));
        assertThat(runGate(newTestAuthorizer(), restricted, "POST",
                "/api/v1/knowledge-bases/:id/knowledge/file")).isTrue();
    }

    @Test
    void gatePlatformOnlyPolicyRejectsTenantKeyBeforeFullAccess() throws Exception {
        // 平台专用策略：租户 full-access Key 也进不去（这条判定在 fullAccess 之前）
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        a.register("GET", "/api/v1/system/admin/settings",
                APIKeyRoutePolicy.platform(APIKeyCapability.SYSTEM_SETTINGS_READ));
        TenantAPIKeyScope tenantFull = new TenantAPIKeyScope(0L, "tenant", true, null, null);
        TenantAPIKeyScope platform = new TenantAPIKeyScope(
                0L, "platform", false, null, List.of(APIKeyCapability.SYSTEM_SETTINGS_READ));
        assertThat(runGate(a, tenantFull, "GET", "/api/v1/system/admin/settings")).isFalse();
        assertThat(runGate(a, platform, "GET", "/api/v1/system/admin/settings")).isTrue();

        // 平台专用但**没写明能力** → fail closed（哪怕 Key 自称 full access）
        APIKeyRouteAuthorizer withoutCapability = new APIKeyRouteAuthorizer();
        withoutCapability.register("GET", "/api/v1/system/admin/unsafe",
                new APIKeyRoutePolicy(true, false, List.of()));
        TenantAPIKeyScope corruptPlatformFull = new TenantAPIKeyScope(0L, "platform", true, null, null);
        assertThat(runGate(withoutCapability, corruptPlatformFull, "GET", "/api/v1/system/admin/unsafe"))
                .isFalse();
    }

    // ── DenyAPIKeyPrincipal（对照 api_key_gate_test.go L203-245） ──

    private static boolean runDenyAPIKey(TenantAPIKeyScope scope) throws Exception {
        if (scope != null) {
            com.ragagent.apikey.domain.APIKeyScopeContext.set(scope);
        }
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/files/presigned-preview");
        MockHttpServletResponse response = new MockHttpServletResponse();
        return new DenyAPIKeyPrincipalInterceptor().preHandle(request, response, new Object())
                && response.getStatus() == 200;
    }

    @Test
    void denyApiKeyPrincipalBlocksApiKeys() throws Exception {
        // 连 full-access Key 都被直接拒绝
        assertThat(runDenyAPIKey(new TenantAPIKeyScope(0L, "tenant", true, null, null))).isFalse();
    }

    @Test
    void denyApiKeyPrincipalAllowsJwt() throws Exception {
        assertThat(runDenyAPIKey(null)).isTrue();
    }

    // ── AllowFileServeAPIKey（对照 api_key_gate_test.go L247-313） ──

    private static boolean runAllowFileServe(TenantAPIKeyScope scope) throws Exception {
        if (scope != null) {
            com.ragagent.apikey.domain.APIKeyScopeContext.set(scope);
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/files");
        MockHttpServletResponse response = new MockHttpServletResponse();
        return new AllowFileServeAPIKeyInterceptor().preHandle(request, response, new Object())
                && response.getStatus() == 200;
    }

    @Test
    void allowFileServeApiKey() throws Exception {
        // JWT 直通
        assertThat(runAllowFileServe(null)).isTrue();
        // full access 直通
        assertThat(runAllowFileServe(new TenantAPIKeyScope(0L, "tenant", true, null, null))).isTrue();
        // 租户级 retrieve（不受 KB 限制）直通
        assertThat(runAllowFileServe(new TenantAPIKeyScope(
                0L, "tenant", false, null, List.of(APIKeyCapability.RETRIEVE)))).isTrue();
        // KB 受限的 retrieve **拒绝**（路径里没有 KB id 可校验白名单）
        assertThat(runAllowFileServe(new TenantAPIKeyScope(
                0L, "tenant", false, List.of("kb-1"), List.of(APIKeyCapability.RETRIEVE)))).isFalse();
        // 非 retrieve 能力拒绝
        assertThat(runAllowFileServe(new TenantAPIKeyScope(
                0L, "tenant", false, null, List.of(APIKeyCapability.CHAT)))).isFalse();
    }

    @Test
    void allowFileServeDenialBodyMatchesGo() throws Exception {
        com.ragagent.apikey.domain.APIKeyScopeContext.set(
                new TenantAPIKeyScope(0L, "tenant", false, List.of("kb-1"), null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/files");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerInterceptor interceptor = new AllowFileServeAPIKeyInterceptor();
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"error\":\"Forbidden: API key scope does not allow this operation\"}");
    }
}
