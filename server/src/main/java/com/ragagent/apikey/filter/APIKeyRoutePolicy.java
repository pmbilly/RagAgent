package com.ragagent.apikey.filter;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.apikey.domain.APIKeyCapability;

/**
 * 单条路由的 API-Key 策略（对照 Go {@code middleware.APIKeyRoutePolicy}，
 * internal/middleware/api_key_gate.go L23-54）。
 *
 * <p><b>设计要点（Go 注释原文的要点）</b>：API-Key 授权是一套**独立的权威**，
 * 与 JWT 的角色/所有权守卫**并列**。所有权（"创建者 OR Admin+"）是人类概念，
 * 对机器主体永不适用；取而代之的是每条可被 API Key 访问的路由在这里声明一条策略，
 * 由门禁（{@link APIKeyRouteAuthorizer}）作为**唯一**执行点。
 * <b>没声明策略的路由对 API Key 一律拒绝</b>（fail-closed），
 * 这消除了旧版"记得加 APIKeyDeny"的坑。</p>
 *
 * @param platformOnly     拒绝租户绑定的 Key——**哪怕它是 full-access**。
 *                         用于 /system/admin 下的控制面路由与跨空间租户生命周期 API。
 * @param requireFullAccess 只放行 full-access 的租户 Key，除非命中了
 *                         {@code capabilities} 里的某条。
 *                         **既不要 full-access 也没有能力清单的路由，对任何有效 API Key 开放。**
 * @param capabilities     作用域 Key 的 any-of 白名单。能力**永远不放宽**
 *                         一把 Key 能碰哪些知识库——KB 白名单由下游
 *                         KBAccess 守卫与 handler 的 scope 检查执行。
 */
public record APIKeyRoutePolicy(boolean platformOnly, boolean requireFullAccess, List<String> capabilities) {

    /** 对照 {@code apiKeyAny()}：任何有效 API Key 都能过。 */
    public static APIKeyRoutePolicy any() {
        return new APIKeyRoutePolicy(false, false, List.of());
    }

    /** 对照 {@code apiKeyFullAccess()}。 */
    public static APIKeyRoutePolicy fullAccess() {
        return new APIKeyRoutePolicy(false, true, List.of());
    }

    /**
     * 对照 {@code apiKeyPlatform(capabilities...)}：平台专用 + any-of 能力。
     *
     * <p>注意 {@code RequireFullAccess} 保持 false——平台 Key 的
     * {@code full_access} 列被 CHECK 约束钉死为 FALSE，判定完全靠能力清单。</p>
     */
    public static APIKeyRoutePolicy platform(String... capabilities) {
        APIKeyRoutePolicy policy = new APIKeyRoutePolicy(true, false, List.of());
        for (String capability : capabilities) {
            policy = policy.withCapability(capability);
        }
        return policy;
    }

    /**
     * 对照 {@code (APIKeyRoutePolicy).WithCapability}（L42-54）：
     * 追加一条能力，**多次调用累积（any-of 语义），重复项忽略**。
     *
     * <p>Go 显式拷贝切片，保证返回的策略不会与接收者共享底层数组——
     * Java 的 record + 不可变 {@code List.copyOf} 天然满足。</p>
     */
    public APIKeyRoutePolicy withCapability(String capability) {
        if (capability == null) {
            return this;
        }
        List<String> next = new ArrayList<>(capabilities);
        if (next.contains(capability)) {
            return this;
        }
        next.add(capability);
        return new APIKeyRoutePolicy(platformOnly, requireFullAccess, List.copyOf(next));
    }

    /** 策略是否声明了任何能力（对照 {@code len(policy.Capabilities) == 0}）。 */
    public boolean hasNoCapabilities() {
        return capabilities.isEmpty();
    }

    /** 是否携带某条能力（对照 Go 测试里的 {@code policyHasCapability}）。 */
    public boolean hasCapability(String capability) {
        return capability != null && capabilities.contains(capability);
    }

    // ── 对照 router/rbac.go 的策略构造器（L226-333），方便策略表按 Go 原文抄 ──

    /** 对照 {@code apiKeyRetrieve(base)}。 */
    public static APIKeyRoutePolicy retrieve(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.RETRIEVE);
    }

    /** 对照 {@code apiKeyChat(base)}。 */
    public static APIKeyRoutePolicy chat(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.CHAT);
    }

    /** 对照 {@code apiKeyReadAgents(base)}。 */
    public static APIKeyRoutePolicy readAgents(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.READ_AGENTS);
    }

    /** 对照 {@code apiKeyIngest(base)}。 */
    public static APIKeyRoutePolicy ingest(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.INGEST);
    }

    /** 对照 {@code apiKeyManageKnowledgeBases(base)}。 */
    public static APIKeyRoutePolicy manageKnowledgeBases(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_KBS);
    }

    /** 对照 {@code apiKeyManageAgents(base)}。 */
    public static APIKeyRoutePolicy manageAgents(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_AGENTS);
    }

    /** 对照 {@code apiKeyMessageHistory(base)}。 */
    public static APIKeyRoutePolicy messageHistory(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MESSAGE_HISTORY);
    }

    /** 对照 {@code apiKeyManageModels(base)}。 */
    public static APIKeyRoutePolicy manageModels(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_MODELS);
    }

    /** 对照 {@code apiKeyManageMCPServices(base)}。 */
    public static APIKeyRoutePolicy manageMcpServices(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_MCP_SERVICES);
    }

    /** 对照 {@code apiKeyManageDataSources(base)}。 */
    public static APIKeyRoutePolicy manageDataSources(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_DATASOURCES);
    }

    /** 对照 {@code apiKeyManageChannels(base)}。 */
    public static APIKeyRoutePolicy manageChannels(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_CHANNELS);
    }

    /** 对照 {@code apiKeyManageVectorStores(base)}。 */
    public static APIKeyRoutePolicy manageVectorStores(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_VECTOR_STORES);
    }

    /** 对照 {@code apiKeyManageStorageBackends(base)}。 */
    public static APIKeyRoutePolicy manageStorageBackends(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_STORAGE_BACKENDS);
    }

    /** 对照 {@code apiKeyManageWebSearch(base)}。 */
    public static APIKeyRoutePolicy manageWebSearch(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_WEB_SEARCH);
    }

    /** 对照 {@code apiKeyRunEvaluations(base)}。 */
    public static APIKeyRoutePolicy runEvaluations(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.RUN_EVALUATIONS);
    }

    /** 对照 {@code apiKeyManageMembers(base)}。 */
    public static APIKeyRoutePolicy manageMembers(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_MEMBERS);
    }

    /** 对照 {@code apiKeyManageSpaces(base)}。 */
    public static APIKeyRoutePolicy manageSpaces(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_SPACES);
    }

    /** 对照 {@code apiKeyManageTenantSettings(base)}。 */
    public static APIKeyRoutePolicy manageTenantSettings(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_TENANT_SETTINGS);
    }
}
