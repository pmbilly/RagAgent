package com.ragagent.auth.apikey.filter;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.auth.apikey.domain.APIKeyCapability;

/**
 * 单条路由的 API-Key 策略。
 *
 * <p><b>设计要点</b>：API-Key 授权是一套**独立的权威**，
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

    /** 任何有效 API Key 都能过。 */
    public static APIKeyRoutePolicy any() {
        return new APIKeyRoutePolicy(false, false, List.of());
    }

    /** 只放行 full-access 的 Key。 */
    public static APIKeyRoutePolicy fullAccess() {
        return new APIKeyRoutePolicy(false, true, List.of());
    }

    /**
     * 平台专用 + any-of 能力。
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
     * 追加一条能力，**多次调用累积（any-of 语义），重复项忽略**。
     *
     * <p>返回新实例，不修改接收者；record + 不可变 {@code List.copyOf} 保证
     * 返回的策略不与调用方共享底层数据。</p>
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

    /** 策略是否未声明任何能力（capabilities 为空）。 */
    public boolean hasNoCapabilities() {
        return capabilities.isEmpty();
    }

    /** 是否携带某条能力。 */
    public boolean hasCapability(String capability) {
        return capability != null && capabilities.contains(capability);
    }

    // ── 组合式策略构造器（供策略表与测试复用） ──

    /** 在 base 上叠加 retrieve 能力。 */
    public static APIKeyRoutePolicy retrieve(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.RETRIEVE);
    }

    /** 在 base 上叠加 chat 能力。 */
    public static APIKeyRoutePolicy chat(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.CHAT);
    }

    /** 在 base 上叠加 read_agents 能力。 */
    public static APIKeyRoutePolicy readAgents(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.READ_AGENTS);
    }

    /** 在 base 上叠加 ingest 能力。 */
    public static APIKeyRoutePolicy ingest(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.INGEST);
    }

    /** 在 base 上叠加 manage_kbs 能力。 */
    public static APIKeyRoutePolicy manageKnowledgeBases(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_KBS);
    }

    /** 在 base 上叠加 manage_agents 能力。 */
    public static APIKeyRoutePolicy manageAgents(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_AGENTS);
    }

    /** 在 base 上叠加 message_history 能力。 */
    public static APIKeyRoutePolicy messageHistory(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MESSAGE_HISTORY);
    }

    /** 在 base 上叠加 manage_models 能力。 */
    public static APIKeyRoutePolicy manageModels(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_MODELS);
    }

    /** 在 base 上叠加 manage_mcp_services 能力。 */
    public static APIKeyRoutePolicy manageMcpServices(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_MCP_SERVICES);
    }

    /** 在 base 上叠加 manage_data_sources 能力。 */
    public static APIKeyRoutePolicy manageDataSources(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_DATASOURCES);
    }

    /** 在 base 上叠加 manage_channels 能力。 */
    public static APIKeyRoutePolicy manageChannels(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_CHANNELS);
    }

    /** 在 base 上叠加 manage_vector_stores 能力。 */
    public static APIKeyRoutePolicy manageVectorStores(APIKeyRoutePolicy base) {
        return base.withCapability(APIKeyCapability.MANAGE_VECTOR_STORES);
    }

    /** 在 base 上叠加 manage_storage_backends 能力。 */
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
