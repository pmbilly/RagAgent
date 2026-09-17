package com.ragagent.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.ragagent.apikey.domain.APIKeyCapability;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.apikey.filter.APIKeyRouteAuthorizer;
import com.ragagent.apikey.filter.APIKeyRoutePolicies;
import com.ragagent.apikey.filter.APIKeyRoutePolicy;
import org.junit.jupiter.api.Test;

/**
 * 策略注册表 / 查表 / 路径归一化的测试——对照 Go
 * internal/middleware/api_key_gate_test.go 的 {@code TestNormalizeRoutePath}（L315-327）
 * 与 internal/router/router_api_key_capabilities_test.go 的
 * {@code mustLookupAPIKeyPolicy} 断言方式（L659-680）。
 *
 * <p>另外覆盖 Java 特有的一环：gin 模板（{@code :param} / {@code *wildcard}）
 * 与 Spring pattern（{@code {param}} / {@code {*wildcard}}）的转换——
 * 策略表按 Go 原文抄写，查表用的是 Spring 上报的 best-matching pattern。</p>
 */
class APIKeyRouteAuthorizerTest {

    // ── normalizeRoutePath（对照 TestNormalizeRoutePath） ──

    @Test
    void normalizeRoutePath() {
        assertThat(APIKeyRouteAuthorizer.normalizeRoutePath("/api/v1//models")).isEqualTo("/api/v1/models");
        assertThat(APIKeyRouteAuthorizer.normalizeRoutePath("/api/v1/models/")).isEqualTo("/api/v1/models");
        assertThat(APIKeyRouteAuthorizer.normalizeRoutePath("/")).isEqualTo("/");
        assertThat(APIKeyRouteAuthorizer.normalizeRoutePath("/api/v1/agents")).isEqualTo("/api/v1/agents");
        // Java 侧补的 null 归一（Go 的 "" 分支）
        assertThat(APIKeyRouteAuthorizer.normalizeRoutePath(null)).isEmpty();
        assertThat(APIKeyRouteAuthorizer.normalizeRoutePath("")).isEmpty();
    }

    @Test
    void registerAndLookupAreNormalized() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        a.register("get", "/api/v1//models/", APIKeyRoutePolicy.any());
        assertThat(a.isDeclared("GET", "/api/v1/models")).isTrue();
        assertThat(a.isDeclared("GET", "/api/v1/models/")).isTrue();
        assertThat(a.isDeclared("POST", "/api/v1/models")).isFalse();
    }

    // ── gin → Spring 模板转换 ──

    @Test
    void ginPathToSpringPath() {
        assertThat(APIKeyRouteAuthorizer.ginPathToSpringPath(
                "/api/v1/knowledge-bases/:id/knowledge/file"))
                .isEqualTo("/api/v1/knowledge-bases/{id}/knowledge/file");
        assertThat(APIKeyRouteAuthorizer.ginPathToSpringPath(
                "/api/v1/knowledgebase/:kb_id/wiki/pages/*slug"))
                .isEqualTo("/api/v1/knowledgebase/{kb_id}/wiki/pages/{*slug}");
        assertThat(APIKeyRouteAuthorizer.ginPathToSpringPath("/api/v1/models"))
                .isEqualTo("/api/v1/models");
        // 幂等：已经是 Spring 形态的不再改写
        assertThat(APIKeyRouteAuthorizer.ginPathToSpringPath("/api/v1/models/{id}"))
                .isEqualTo("/api/v1/models/{id}");
    }

    @Test
    void ginRegisteredPolicyIsFoundBySpringPattern() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        a.registerGin("GET", "/api/v1/knowledgebase/:kb_id/wiki/pages/*slug",
                APIKeyRoutePolicy.retrieve(APIKeyRoutePolicy.fullAccess()));
        // 注册用 gin 模板，查表用 Spring 上报的 pattern —— 两者必须命中同一条
        assertThat(a.isDeclared("GET", "/api/v1/knowledgebase/{kb_id}/wiki/pages/{*slug}")).isTrue();
        assertThat(a.isDeclared("GET", "/api/v1/knowledgebase/{kb_id}/wiki/pages")).isFalse();
    }

    // ── authorize 的判定链（对照 api_key_gate.go L130-153） ──

    @Test
    void authorizeChain() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        a.register("GET", "/api/v1/system/capabilities", APIKeyRoutePolicy.any());
        a.register("GET", "/api/v1/models",
                APIKeyRoutePolicy.manageModels(APIKeyRoutePolicy.fullAccess()));
        a.register("GET", "/api/v1/system/admin/info",
                APIKeyRoutePolicy.platform(APIKeyCapability.SYSTEM_RUNTIME_READ));

        // 未声明 → 拒绝
        assertThat(a.authorize(TenantAPIKeyScope.empty(), "GET", "/api/v1/nope")).isFalse();
        // 声明了空策略 → 任何有效 Key 放行（含 scoped）
        assertThat(a.authorize(TenantAPIKeyScope.empty(), "GET", "/api/v1/system/capabilities")).isTrue();
        // RequireFullAccess + 能力清单：scoped 与 full 都放行，无能力者拒绝
        assertThat(a.authorize(new TenantAPIKeyScope(0L, "tenant", true, null, null),
                "GET", "/api/v1/models")).isTrue();
        assertThat(a.authorize(new TenantAPIKeyScope(0L, "tenant", false, null,
                List.of(APIKeyCapability.MANAGE_MODELS)), "GET", "/api/v1/models")).isTrue();
        assertThat(a.authorize(new TenantAPIKeyScope(0L, "tenant", false, null,
                List.of(APIKeyCapability.RETRIEVE)), "GET", "/api/v1/models")).isFalse();
        // 平台专用：租户 Key（哪怕 full）拒绝；平台 Key 带能力放行
        assertThat(a.authorize(new TenantAPIKeyScope(0L, "tenant", true, null, null),
                "GET", "/api/v1/system/admin/info")).isFalse();
        assertThat(a.authorize(new TenantAPIKeyScope(0L, "platform", false, null,
                List.of(APIKeyCapability.SYSTEM_RUNTIME_READ)), "GET", "/api/v1/system/admin/info")).isTrue();
    }

    @Test
    void withCapabilityAccumulatesAndDedupes() {
        APIKeyRoutePolicy p = APIKeyRoutePolicy.fullAccess()
                .withCapability(APIKeyCapability.CHAT)
                .withCapability(APIKeyCapability.MANAGE_AGENTS)
                .withCapability(APIKeyCapability.CHAT);
        assertThat(p.capabilities()).containsExactly(APIKeyCapability.CHAT, APIKeyCapability.MANAGE_AGENTS);
        assertThat(p.requireFullAccess()).isTrue();
        // 原始策略不被修改（Go 显式拷贝切片）
        APIKeyRoutePolicy base = APIKeyRoutePolicy.fullAccess();
        assertThat(base.withCapability("chat").capabilities()).containsExactly("chat");
        assertThat(base.capabilities()).isEmpty();
    }

    // ── 策略表覆盖（对照 router_api_key_capabilities_test.go 的断言方式） ──

    private static APIKeyRouteAuthorizer catalog() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        APIKeyRoutePolicies.registerAll(a);
        return a;
    }

    private static APIKeyRoutePolicy policy(APIKeyRouteAuthorizer a, String method, String path) {
        APIKeyRoutePolicy p = a.lookup(method, path);
        assertThat(p).as("missing API-key policy for %s %s", method, path).isNotNull();
        return p;
    }

    @Test
    void modelRoutesDeclareManageModelsCapability() {
        APIKeyRouteAuthorizer a = catalog();
        for (String[] methodPath : List.of(
                new String[]{"GET", "/api/v1/models"},
                new String[]{"POST", "/api/v1/models"},
                new String[]{"GET", "/api/v1/models/{id}"},
                new String[]{"PUT", "/api/v1/models/{id}"},
                new String[]{"DELETE", "/api/v1/models/{id}"},
                new String[]{"GET", "/api/v1/models/providers"},
                new String[]{"PUT", "/api/v1/models/{id}/credentials"},
                new String[]{"DELETE", "/api/v1/models/{id}/credentials/{field}"},
                new String[]{"GET", "/api/v1/models/weknoracloud/status"},
                new String[]{"POST", "/api/v1/weknoracloud/credentials"})) {
            APIKeyRoutePolicy p = policy(a, methodPath[0], methodPath[1]);
            assertThat(p.requireFullAccess()).isTrue();
            assertThat(p.hasCapability(APIKeyCapability.MANAGE_MODELS)).isTrue();
        }
    }

    @Test
    void knowledgeRoutesSplitRetrieveIngestAndManageKbs() {
        APIKeyRouteAuthorizer a = catalog();
        // 读路由：retrieve
        for (String[] mp : List.of(
                new String[]{"GET", "/api/v1/knowledge-bases"},
                new String[]{"GET", "/api/v1/knowledge-bases/{id}"},
                new String[]{"GET", "/api/v1/knowledge-bases/{id}/move-targets"},
                new String[]{"GET", "/api/v1/knowledge-bases/{id}/knowledge"},
                new String[]{"GET", "/api/v1/knowledge/{id}"})) {
            APIKeyRoutePolicy p = policy(a, mp[0], mp[1]);
            assertThat(p.hasCapability(APIKeyCapability.RETRIEVE)).isTrue();
            assertThat(p.hasCapability(APIKeyCapability.INGEST)).isFalse();
        }
        // 内容写路由：ingest（**不含** manage_kbs）
        for (String[] mp : List.of(
                new String[]{"POST", "/api/v1/knowledge-bases/{id}/knowledge/file"},
                new String[]{"POST", "/api/v1/knowledge-bases/{id}/knowledge/url"},
                new String[]{"POST", "/api/v1/knowledge-bases/{id}/knowledge/manual"},
                new String[]{"PUT", "/api/v1/knowledge/{id}"},
                new String[]{"DELETE", "/api/v1/knowledge/{id}"})) {
            APIKeyRoutePolicy p = policy(a, mp[0], mp[1]);
            assertThat(p.hasCapability(APIKeyCapability.INGEST)).isTrue();
            assertThat(p.hasCapability(APIKeyCapability.MANAGE_KBS)).isFalse();
        }
        // 知识库生命周期：manage_kbs（**不含** ingest）
        for (String[] mp : List.of(
                new String[]{"POST", "/api/v1/knowledge-bases"},
                new String[]{"PUT", "/api/v1/knowledge-bases/{id}"},
                new String[]{"DELETE", "/api/v1/knowledge-bases/{id}"})) {
            APIKeyRoutePolicy p = policy(a, mp[0], mp[1]);
            assertThat(p.hasCapability(APIKeyCapability.MANAGE_KBS)).isTrue();
            assertThat(p.hasCapability(APIKeyCapability.INGEST)).isFalse();
        }
    }

    @Test
    void wikiRoutesUseKnowledgebasePrefixWithoutHyphen() {
        APIKeyRouteAuthorizer a = catalog();
        // 读
        for (String[] mp : List.of(
                new String[]{"GET", "/api/v1/knowledgebase/{kb_id}/wiki/pages"},
                new String[]{"GET", "/api/v1/knowledgebase/{kb_id}/wiki/pages/{*slug}"},
                new String[]{"GET", "/api/v1/knowledgebase/{kb_id}/wiki/revisions/{*slug}"},
                new String[]{"GET", "/api/v1/knowledgebase/{kb_id}/wiki/index"},
                new String[]{"GET", "/api/v1/knowledgebase/{kb_id}/wiki/lint"})) {
            APIKeyRoutePolicy p = policy(a, mp[0], mp[1]);
            assertThat(p.hasCapability(APIKeyCapability.RETRIEVE)).isTrue();
            assertThat(p.hasCapability(APIKeyCapability.INGEST)).isFalse();
        }
        // 写
        for (String[] mp : List.of(
                new String[]{"POST", "/api/v1/knowledgebase/{kb_id}/wiki/pages"},
                new String[]{"PUT", "/api/v1/knowledgebase/{kb_id}/wiki/pages/{*slug}"},
                new String[]{"DELETE", "/api/v1/knowledgebase/{kb_id}/wiki/pages/{*slug}"},
                new String[]{"PUT", "/api/v1/knowledgebase/{kb_id}/wiki/move-page"},
                new String[]{"POST", "/api/v1/knowledgebase/{kb_id}/wiki/auto-fix"})) {
            APIKeyRoutePolicy p = policy(a, mp[0], mp[1]);
            assertThat(p.hasCapability(APIKeyCapability.INGEST)).isTrue();
            assertThat(p.hasCapability(APIKeyCapability.RETRIEVE)).isFalse();
        }
        // 连字符版本**没有**策略（default deny）——前缀写错会静默 403，这里钉住它
        assertThat(a.isDeclared("GET", "/api/v1/knowledge-bases/{kb_id}/wiki/pages")).isFalse();
    }

    @Test
    void mcpRoutesDeclareManageMcpServices() {
        APIKeyRouteAuthorizer a = catalog();
        for (String[] mp : List.of(
                new String[]{"POST", "/api/v1/mcp-services"},
                new String[]{"GET", "/api/v1/mcp-services"},
                new String[]{"GET", "/api/v1/mcp-services/{id}"},
                new String[]{"PUT", "/api/v1/mcp-services/{id}"},
                new String[]{"DELETE", "/api/v1/mcp-services/{id}"},
                new String[]{"POST", "/api/v1/mcp-services/{id}/test"},
                new String[]{"GET", "/api/v1/mcp-services/{id}/tool-approvals"},
                new String[]{"PUT", "/api/v1/mcp-services/{id}/tool-approvals/{tool_name}"},
                new String[]{"PUT", "/api/v1/mcp-services/{id}/credentials"},
                new String[]{"DELETE", "/api/v1/mcp-services/{id}/credentials/{field}"},
                new String[]{"POST", "/api/v1/mcp-services/{id}/oauth/authorize-url"},
                new String[]{"GET", "/api/v1/mcp-services/{id}/oauth/status"},
                new String[]{"DELETE", "/api/v1/mcp-services/{id}/oauth/token"})) {
            APIKeyRoutePolicy p = policy(a, mp[0], mp[1]);
            assertThat(p.requireFullAccess()).isTrue();
            assertThat(p.hasCapability(APIKeyCapability.MANAGE_MCP_SERVICES)).isTrue();
        }
    }

    /**
     * 对照 Go {@code TestAPIKeyGateDeniesTenantKeyManagementPaths}：
     * Key 管理端点与 API 主体配置端点**必须保持未声明**，否则一把 Key 能给自己扩权。
     */
    @Test
    void tenantApiKeyManagementPathsStayDefaultDeny() {
        APIKeyRouteAuthorizer a = catalog();
        for (String[] mp : List.of(
                new String[]{"GET", "/api/v1/tenants/{id}/api-keys"},
                new String[]{"POST", "/api/v1/tenants/{id}/api-keys"},
                new String[]{"PUT", "/api/v1/tenants/{id}/api-keys/{key_id}"},
                new String[]{"DELETE", "/api/v1/tenants/{id}/api-keys/{key_id}"})) {
            assertThat(a.isDeclared(mp[0], mp[1]))
                    .as("%s %s must stay undeclared (default deny)", mp[0], mp[1])
                    .isFalse();
            assertThat(a.authorize(new TenantAPIKeyScope(0L, "tenant", true, null, null), mp[0], mp[1]))
                    .as("full-access key must not reach %s %s", mp[0], mp[1])
                    .isFalse();
        }
    }

    /** 对照 Go：/agent/tool-approvals 注册在原始 group 上 → 对 API Key default-deny。 */
    @Test
    void agentToolApprovalRouteStaysDefaultDeny() {
        APIKeyRouteAuthorizer a = catalog();
        assertThat(a.isDeclared("POST", "/api/v1/agent/tool-approvals/{pending_id}")).isFalse();
        assertThat(a.isDeclared("POST", "/api/v1/agent/mcp-oauth-resolutions/{pending_id}")).isFalse();
    }

    @Test
    void authMeIsOpenToAnyValidKey() {
        APIKeyRouteAuthorizer a = catalog();
        APIKeyRoutePolicy p = policy(a, "GET", "/api/v1/auth/me");
        assertThat(p.requireFullAccess()).isFalse();
        assertThat(p.platformOnly()).isFalse();
        assertThat(p.capabilities()).isEmpty();
        assertThat(a.authorize(TenantAPIKeyScope.empty(), "GET", "/api/v1/auth/me")).isTrue();
    }

    /**
     * 对照 Go router/rbac.go L226-333 的策略构造器与
     * router_api_key_capabilities_test.go 的若干断言：把尚未接入 Java 路由、
     * 但后续模块必然要用的构造器先钉住形状（chat / read_agents 叠加 /
     * manage_spaces 不放宽分享 / message_history 不被 chat 满足）。
     */
    @Test
    void policyConstructorsMirrorGoRouterHelpers() {
        // 会话：chat 能力 + RequireFullAccess（routes_chat.go L51）
        APIKeyRoutePolicy chat = APIKeyRoutePolicies.chatPolicy();
        assertThat(chat.requireFullAccess()).isTrue();
        assertThat(chat.capabilities()).containsExactly(APIKeyCapability.CHAT);

        // agent 读：chat / manage_agents / read_agents 三能力 any-of（routes_agent.go L26）
        APIKeyRoutePolicy agentRead = APIKeyRoutePolicies.agentReadPolicy();
        assertThat(agentRead.capabilities()).containsExactlyInAnyOrder(
                APIKeyCapability.CHAT, APIKeyCapability.MANAGE_AGENTS, APIKeyCapability.READ_AGENTS);

        // 平台控制面：PlatformOnly + system_tenants_{read,manage}
        APIKeyRoutePolicy platform = APIKeyRoutePolicies.platformTenantsReadPolicy();
        assertThat(platform.platformOnly()).isTrue();
        assertThat(platform.hasCapability(APIKeyCapability.SYSTEM_TENANTS_READ)).isTrue();
        // 平台策略刻意**不**要求 full access（平台 Key 的 full_access 恒为 false）
        assertThat(platform.requireFullAccess()).isFalse();

        // 租户设置：manage_tenant_settings（routes_auth_tenant.go L73-81）
        APIKeyRoutePolicy settings = APIKeyRoutePolicies.tenantSettingsPolicy();
        assertThat(settings.requireFullAccess()).isTrue();
        assertThat(settings.hasCapability(APIKeyCapability.MANAGE_TENANT_SETTINGS)).isTrue();

        // manage_spaces 永不满足"知识库管理"这类需要 manage_kbs 的路由
        APIKeyRoutePolicy spaces = APIKeyRoutePolicy.manageSpaces(APIKeyRoutePolicy.fullAccess());
        assertThat(spaces.capabilities()).containsExactly(APIKeyCapability.MANAGE_SPACES);
        assertThat(spaces.hasCapability(APIKeyCapability.MANAGE_KBS)).isFalse();

        // message_history 与 chat 是两条独立能力（routes_chat.go L26）
        APIKeyRoutePolicy history = APIKeyRoutePolicy.messageHistory(APIKeyRoutePolicy.fullAccess());
        assertThat(history.hasCapability(APIKeyCapability.CHAT)).isFalse();
    }

    /** 每条策略构造器都只追加一条能力，且不污染入参（WithCapability 是纯函数）。 */
    @Test
    void everyConstructorAddsExactlyOneCapability() {
        APIKeyRoutePolicy base = APIKeyRoutePolicy.fullAccess();
        assertThat(APIKeyRoutePolicy.retrieve(base).capabilities()).containsExactly(APIKeyCapability.RETRIEVE);
        assertThat(APIKeyRoutePolicy.chat(base).capabilities()).containsExactly(APIKeyCapability.CHAT);
        assertThat(APIKeyRoutePolicy.readAgents(base).capabilities()).containsExactly(APIKeyCapability.READ_AGENTS);
        assertThat(APIKeyRoutePolicy.ingest(base).capabilities()).containsExactly(APIKeyCapability.INGEST);
        assertThat(APIKeyRoutePolicy.manageKnowledgeBases(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_KBS);
        assertThat(APIKeyRoutePolicy.manageAgents(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_AGENTS);
        assertThat(APIKeyRoutePolicy.messageHistory(base).capabilities())
                .containsExactly(APIKeyCapability.MESSAGE_HISTORY);
        assertThat(APIKeyRoutePolicy.manageModels(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_MODELS);
        assertThat(APIKeyRoutePolicy.manageMcpServices(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_MCP_SERVICES);
        assertThat(APIKeyRoutePolicy.manageDataSources(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_DATASOURCES);
        assertThat(APIKeyRoutePolicy.manageChannels(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_CHANNELS);
        assertThat(APIKeyRoutePolicy.manageVectorStores(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_VECTOR_STORES);
        assertThat(APIKeyRoutePolicy.manageStorageBackends(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_STORAGE_BACKENDS);
        assertThat(APIKeyRoutePolicy.manageWebSearch(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_WEB_SEARCH);
        assertThat(APIKeyRoutePolicy.runEvaluations(base).capabilities())
                .containsExactly(APIKeyCapability.RUN_EVALUATIONS);
        assertThat(APIKeyRoutePolicy.manageMembers(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_MEMBERS);
        assertThat(APIKeyRoutePolicy.manageTenantSettings(base).capabilities())
                .containsExactly(APIKeyCapability.MANAGE_TENANT_SETTINGS);
        assertThat(base.capabilities()).isEmpty();
    }

    @Test
    void registeredRoutesReportDeclaredKeys() {
        APIKeyRouteAuthorizer a = catalog();
        assertThat(a.registeredRoutes()).containsKey("GET");
        assertThat(a.registeredRoutes().get("GET")).contains("/api/v1/models", "/api/v1/auth/me");
        assertThat(a.declaredNodes()).contains("PUT /api/v1/knowledge-bases/{id}");
    }
}
