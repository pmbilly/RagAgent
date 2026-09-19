package com.ragagent.apikey.filter;

/**
 * API-Key 策略表（对照 Go {@code internal/router/*.go} 里所有
 * {@code g.apiKeyRoute(...)} / {@code g.apiKeyGroup(...)} 声明）。
 *
 * <h2>它是什么</h2>
 * <p>Go 在**构造路由的同时**把策略登记进 {@code APIKeyRouteAuthorizer}，
 * 声明与路由注册是同一条语句（{@code apiKeyRoute} / {@code apiKeyRouteGroup.handle}），
 * 因此不可能漂移。Java 侧路由注册分散在各控制器的 {@code @RequestMapping} 上，
 * 没有等价的"同一条语句"钩子，所以策略被集中到本类：
 * <b>一处声明，启动时一次性灌进 {@link APIKeyRouteAuthorizer}</b>。</p>
 *
 * <p>每个条目都用 {@link APIKeyRouteAuthorizer#registerGin} 按 **Go 原文的 gin 模板**
 * 登记（{@code :param} / {@code *wildcard} 由 authorizer 转成 Spring 的
 * {@code {param}} / {@code {*wildcard}}），这样本文件可以逐行对照 Go 源，
 * 也避免了手工转换模板时把 {@code :} 写成 {@code {}} 的错位。</p>
 *
 * <h2>覆盖范围（重要）</h2>
 * <p><b>只登记 Java 侧已经翻译出来的路由。</b>Go 的策略表还覆盖 agents / sessions /
 * 会话文件 / sandbox / evaluation / 数据源 / 渠道 / 向量库 / 存储后端 / 组织 / 系统管理等，
 * 对应模块尚未翻译，登记了也只是死条目。它们随各自模块一起回补——
 * 每条都从对应的 Go {@code routes_*.go} 抄过来即可。</p>
 *
 * <p><b>未登记 = default deny</b>，这正是我们要的语义。当前 Java 已有但**刻意不登记**的：</p>
 * <ul>
 *   <li>{@code /api/v1/tenants/{id}/api-keys}（四个端点，本模块自己的）——
 *       Go 里注册在原始 group 上，没有 apiKeyRoute 包装，因此 API Key 一律 403。
 *       对照 Go 测试 {@code TestAPIKeyGateDeniesTenantKeyManagementPaths}：
 *       Key 管理端点连 full-access Key 都进不去（否则一把 Key 可以给自己续命/扩权）；</li>
 *   <li>{@code /api/v1/agent/tool-approvals/{pending_id}} 与
 *       {@code /api/v1/agent/mcp-oauth-resolutions/**} —— 同样是"注册在原始 group 上"
 *       的 default-deny 例子（Go routes_infra.go L190-202）。</li>
 * </ul>
 */
public final class APIKeyRoutePolicies {

    private APIKeyRoutePolicies() {
    }

    /**
     * 把当前已翻译路由的策略全部登记进 {@code authorizer}。
     *
     * <p>由组合根（{@code WebConfig}）在装配时调用一次——对照 Go 在
     * {@code NewRouter} 里构造 {@code rbacGuards} 时建立 authorizer。
     * 调用方随后把同一个 authorizer 交给
     * {@link APIKeyGateInterceptor} 注册为 {@code /api/v1/**} 的拦截器。</p>
     */
    public static void registerAll(APIKeyRouteAuthorizer authorizer) {
        registerAuthRoutes(authorizer);
        registerModelRoutes(authorizer);
        registerKnowledgeAndWikiRoutes(authorizer);
        registerMcpRoutes(authorizer);
        registerSessionRoutes(authorizer);
        registerMessageRoutes(authorizer);
        registerMemoryRoutes(authorizer);
        registerDataSourceRoutes(authorizer);
        registerInfraConfigRoutes(authorizer);
        registerTenantMemberRoutes(authorizer);
        registerSystemRoutes(authorizer);
        registerEvaluationRoutes(authorizer);
        registerSandboxConfigRoutes(authorizer);
        registerOrganizationRoutes(authorizer);
    }

    /**
     * 组织与跨空间共享（波 3 协作面批次，对照 Go routes_agent.go RegisterOrganizationRoutes）。
     *
     * <p><b>organizations 组</b> = {@code manageSpaces(fullAccess())}（scoped key 凭
     * manage_spaces 能力可进——空间协作是它的本职）；<b>kbShares / agentShares</b> 组 =
     * 纯 {@code fullAccess()}（Go 注释原文：分享管理不通过 capability 授予，manage_spaces
     * 也不含，scoped key 保持 default-deny）；<b>shared-* 三条</b> = {@code manageSpaces(fullAccess())}。</p>
     */
    private static void registerOrganizationRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy orgs = APIKeyRoutePolicy.manageSpaces(APIKeyRoutePolicy.fullAccess());
        final String orgBase = "/api/v1/organizations";
        a.registerGin("POST", orgBase, orgs);
        a.registerGin("GET", orgBase, orgs);
        a.registerGin("GET", orgBase + "/preview/:code", orgs);
        a.registerGin("POST", orgBase + "/join", orgs);
        a.registerGin("POST", orgBase + "/join-request", orgs);
        a.registerGin("GET", orgBase + "/search", orgs);
        a.registerGin("POST", orgBase + "/join-by-id", orgs);
        a.registerGin("GET", orgBase + "/:id", orgs);
        a.registerGin("PUT", orgBase + "/:id", orgs);
        a.registerGin("DELETE", orgBase + "/:id", orgs);
        a.registerGin("POST", orgBase + "/:id/leave", orgs);
        a.registerGin("POST", orgBase + "/:id/request-upgrade", orgs);
        a.registerGin("POST", orgBase + "/:id/invite-code", orgs);
        a.registerGin("GET", orgBase + "/:id/search-tenants", orgs);
        a.registerGin("GET", orgBase + "/:id/search-users", orgs);
        a.registerGin("POST", orgBase + "/:id/invite", orgs);
        a.registerGin("GET", orgBase + "/:id/members", orgs);
        a.registerGin("PUT", orgBase + "/:id/members/:tenant_id", orgs);
        a.registerGin("DELETE", orgBase + "/:id/members/:tenant_id", orgs);
        a.registerGin("GET", orgBase + "/:id/join-requests", orgs);
        a.registerGin("PUT", orgBase + "/:id/join-requests/:request_id/review", orgs);
        a.registerGin("GET", orgBase + "/:id/shares", orgs);
        a.registerGin("GET", orgBase + "/:id/agent-shares", orgs);
        a.registerGin("GET", orgBase + "/:id/shared-knowledge-bases", orgs);
        a.registerGin("GET", orgBase + "/:id/shared-agents", orgs);

        APIKeyRoutePolicy full = APIKeyRoutePolicy.fullAccess();
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/shares", full);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/shares", full);
        a.registerGin("PUT", "/api/v1/knowledge-bases/:id/shares/:share_id", full);
        a.registerGin("DELETE", "/api/v1/knowledge-bases/:id/shares/:share_id", full);

        a.registerGin("POST", "/api/v1/agents/:id/shares", full);
        a.registerGin("GET", "/api/v1/agents/:id/shares", full);
        a.registerGin("DELETE", "/api/v1/agents/:id/shares/:share_id", full);

        a.registerGin("GET", "/api/v1/shared-knowledge-bases", orgs);
        a.registerGin("GET", "/api/v1/shared-agents", orgs);
        a.registerGin("POST", "/api/v1/shared-agents/disabled", orgs);

        // ── 波 3 agents 批（对照 Go routes_agent.go RegisterCustomAgentRoutes L22-51）──
        // agents 组 base=fullAccess；读面 read_agents/manage_agents/chat/full 任一；
        // 写面 manage_agents/full 任一；suggested-questions 注册在组外但能力同读面。
        // （agents/:id/shares 三条已在上面登记，全 fullAccess。）
        APIKeyRoutePolicy agFull = APIKeyRoutePolicy.fullAccess();
        APIKeyRoutePolicy agRead = APIKeyRoutePolicy.readAgents(
                APIKeyRoutePolicy.manageAgents(APIKeyRoutePolicy.chat(agFull)));
        APIKeyRoutePolicy agWrite = APIKeyRoutePolicy.manageAgents(agFull);
        a.registerGin("GET", "/api/v1/agents/placeholders", agRead);
        a.registerGin("GET", "/api/v1/agents/type-presets", agRead);
        a.registerGin("POST", "/api/v1/agents", agWrite);
        a.registerGin("GET", "/api/v1/agents", agRead);
        a.registerGin("GET", "/api/v1/agents/:id", agRead);
        a.registerGin("PUT", "/api/v1/agents/:id", agWrite);
        a.registerGin("DELETE", "/api/v1/agents/:id", agWrite);
        a.registerGin("POST", "/api/v1/agents/:id/copy", agWrite);
        a.registerGin("GET", "/api/v1/agents/:id/suggested-questions", agRead);

        // initialization 三条（对照 routes_infra.go L96-105；KB 依赖属 knowledge 域）
        a.registerGin("GET", "/api/v1/initialization/config/:kbId",
                APIKeyRoutePolicy.retrieve(agFull));
        a.registerGin("POST", "/api/v1/initialization/initialize/:kbId",
                APIKeyRoutePolicy.manageKnowledgeBases(agFull));
        a.registerGin("PUT", "/api/v1/initialization/config/:kbId",
                APIKeyRoutePolicy.manageKnowledgeBases(agFull));
    }

    /**
     * 系统管理端（波 2 收官批，对照 Go routes_auth_tenant.go L246-258 / L260-335）。
     *
     * <p><b>/system 组</b>共用 {@code apiKeyManageVectorStores(apiKeyFullAccess())}
     * （full-access 或显式 manage_vector_stores——Go 的注释把"为何是它"写成了
     * 兼容遗留路由的历史决定）；其中 capabilities 单独覆写为 {@code apiKeyAny()}
     * （scoped key 也能读部署能力清单，"leaving it default-deny was why scoped keys
     * got a 403 here"）。**同一条路由的覆写按 Go 的注册序后登记覆盖**。</p>
     *
     * <p><b>/system/admin 组</b>只有 settings/runtime/tenants/audit-log 用
     * {@code g.apiKeyRoute(...)} 包装（platform 能力）；promote / revoke / list /
     * users/reset-password、users/create、api-keys 的 CRUD 注册在**原始 group**上 →
     * Key default-deny（不能给自己扩权），与 Go 完全一致，刻意不登记。</p>
     *
     * <p><b>POST /system/sandbox-check 不登记</b>：路由未实现（波 3），
     * 登记了也只是死条目。</p>
     */
    private static void registerSystemRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy system = APIKeyRoutePolicy.manageVectorStores(APIKeyRoutePolicy.fullAccess());
        final String base = "/api/v1/system";
        a.registerGin("GET", base + "/info", system);
        a.registerGin("GET", base + "/parser-engines", system);
        a.registerGin("POST", base + "/parser-engines/check", system);
        a.registerGin("POST", base + "/docreader/reconnect", system);
        a.registerGin("GET", base + "/storage-engine-status", system);
        a.registerGin("POST", base + "/storage-engine-check", system);
        // capabilities 的 apiKeyAny 覆写在组策略之后（Go：systemRoutes.With(apiKeyAny()).GET(...)）
        a.registerGin("GET", base + "/capabilities", APIKeyRoutePolicy.any());

        APIKeyRoutePolicy settingsRead = APIKeyRoutePolicy.platform(
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_SETTINGS_READ,
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_SETTINGS_MANAGE);
        APIKeyRoutePolicy settingsManage = APIKeyRoutePolicy.platform(
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_SETTINGS_MANAGE);
        a.registerGin("GET", "/api/v1/system/admin/settings", settingsRead);
        a.registerGin("GET", "/api/v1/system/admin/settings/:key", settingsRead);
        a.registerGin("PUT", "/api/v1/system/admin/settings/:key", settingsManage);
        a.registerGin("DELETE", "/api/v1/system/admin/settings/:key", settingsManage);

        APIKeyRoutePolicy runtimeRead = APIKeyRoutePolicy.platform(
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_RUNTIME_READ,
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_RUNTIME_MANAGE);
        APIKeyRoutePolicy runtimeManage = APIKeyRoutePolicy.platform(
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_RUNTIME_MANAGE);
        a.registerGin("GET", "/api/v1/system/admin/runtime/queues", runtimeRead);
        a.registerGin("GET", "/api/v1/system/admin/runtime/queues/:queue/tasks", runtimeRead);
        a.registerGin("POST", "/api/v1/system/admin/runtime/queues/:queue/tasks/:task_id/actions/:action",
                runtimeManage);
        a.registerGin("DELETE", "/api/v1/system/admin/runtime/queues/:queue/archived", runtimeManage);

        a.registerGin("POST", "/api/v1/system/admin/tenants/apply-default-storage-quota",
                APIKeyRoutePolicy.platform(
                        com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_MANAGE));

        // 已翻译的 /system/admin/audit-log（audit 模块回补时落地）——platform 审计读能力
        a.registerGin("GET", "/api/v1/system/admin/audit-log",
                APIKeyRoutePolicy.platform(
                        com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_AUDIT_READ));
    }

    /**
     * 评估（对照 Go routes_infra.go L81-89）：整组
     * {@code apiKeyRunEvaluations(apiKeyFullAccess())}——full-access 或显式
     * run_evaluations；POST/GET 同策略（角色维度 Admin/Viewer 由 RBAC 另行把关）。
     */
    private static void registerEvaluationRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy evaluation = APIKeyRoutePolicy.runEvaluations(APIKeyRoutePolicy.fullAccess());
        a.registerGin("POST", "/api/v1/evaluation", evaluation);
        a.registerGin("GET", "/api/v1/evaluation", evaluation);
    }

    /**
     * 空间成员 / 邀请 / API-Principal（波 2 第六批，对照 Go routes_auth_tenant.go:88-137）。
     *
     * <p>三档策略：</p>
     * <ul>
     *   <li><b>members + invitations</b>（7 条）＝
     *       {@code apiKeyManageMembers(apiKeyFullAccess())}——直加/改角色/撤销/建共享链接
     *       都在 manage_members 能力面；{@code rejectAPIKeyOwnerAssignment} 在 service 层
     *       禁止机器主体授 Owner（能力面可管低角色、绝不能铸 Owner）。</li>
     *   <li><b>api-principal 三条</b>＝platform 能力：GET 是
     *       {@code system_tenants_read|system_tenants_manage}（读或管皆可），
     *       PUT / test-token 只有 {@code system_tenants_manage}（写面）。</li>
     * </ul>
     *
     * <p><b>刻意不登记</b>（Go 注册在原始 group、无 apiKeyRoute 包装 → Key default-deny）：
     * {@code POST /tenants/{id}/leave}（自助退出是"人的动作"，机器主体无从谈起）；
     * {@code /me/invitations**} 五条收件箱路由（按登录用户自证，同 Go）。</p>
     */
    private static void registerTenantMemberRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy members = APIKeyRoutePolicy.manageMembers(APIKeyRoutePolicy.fullAccess());
        a.registerGin("GET", "/api/v1/tenants/:id/members", members);
        a.registerGin("POST", "/api/v1/tenants/:id/members", members);
        a.registerGin("PUT", "/api/v1/tenants/:id/members/:user_id", members);
        a.registerGin("DELETE", "/api/v1/tenants/:id/members/:user_id", members);
        a.registerGin("GET", "/api/v1/tenants/:id/invitations", members);
        a.registerGin("POST", "/api/v1/tenants/:id/invitations", members);
        a.registerGin("DELETE", "/api/v1/tenants/:id/invitations/:inv_id", members);
        a.registerGin("POST", "/api/v1/tenants/:id/invite-links", members);

        // platform 租户读/管理能力（对照 Go 的 apiKeyPlatform(SystemTenantsRead/Manage)）
        a.registerGin("GET", "/api/v1/tenants/:id/api-principal-config",
                APIKeyRoutePolicy.platform(
                        com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_READ,
                        com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_MANAGE));
        a.registerGin("PUT", "/api/v1/tenants/:id/api-principal-config",
                APIKeyRoutePolicy.platform(
                        com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_MANAGE));
        a.registerGin("POST", "/api/v1/tenants/:id/api-principal-test-token",
                APIKeyRoutePolicy.platform(
                        com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_MANAGE));

        // 跨空间租户目录 + KV 分发器（波 2 扫尾批 3，对照 Go routes_auth_tenant.go
        // L53-76）：
        // - all/search ＝ platform(system_tenants_read | system_tenants_manage)；
        // - POST /tenants ＝ platform(system_tenants_manage)（Go 注释："工作区 Key
        //   对租户目录操作 default-deny"）；
        // - kv 两条 ＝ manage_tenant_settings 叠加 full-access（租户级配置面）。
        APIKeyRoutePolicy catalogRead = APIKeyRoutePolicy.platform(
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_READ,
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_MANAGE);
        a.registerGin("GET", "/api/v1/tenants/all", catalogRead);
        a.registerGin("GET", "/api/v1/tenants/search", catalogRead);
        a.registerGin("POST", "/api/v1/tenants",
                APIKeyRoutePolicy.platform(
                        com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_MANAGE));
        APIKeyRoutePolicy kv = APIKeyRoutePolicy.manageTenantSettings(APIKeyRoutePolicy.fullAccess());
        a.registerGin("GET", "/api/v1/tenants/kv/:key", kv);
        a.registerGin("PUT", "/api/v1/tenants/kv/:key", kv);
    }

    /**
     * 基础设施配置三组（波 2 第五批，对照 Go routes_infra.go L205-284）。
     *
     * <p>三组各挂自己的管理能力（均叠加 full-access）：
     * {@code /web-search-providers}=manage_web_search、
     * {@code /vector-stores}=manage_vector_stores、
     * {@code /storage-backends}=manage_storage_backends。</p>
     *
     * <p><b>刻意不登记</b>：{@code /api/v1/web-search/providers}（旧版运行时 provider
     * 列表）在 Go 里注册于**原始 group**（无 apiKeyGroup 包装）→ Key default-deny。</p>
     */
    private static void registerInfraConfigRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy wsp = APIKeyRoutePolicy.manageWebSearch(APIKeyRoutePolicy.fullAccess());
        final String wspBase = "/api/v1/web-search-providers";
        a.registerGin("GET", wspBase + "/types", wsp);
        a.registerGin("POST", wspBase + "/test", wsp);
        a.registerGin("POST", wspBase, wsp);
        a.registerGin("GET", wspBase, wsp);
        a.registerGin("GET", wspBase + "/:id", wsp);
        a.registerGin("PUT", wspBase + "/:id", wsp);
        a.registerGin("DELETE", wspBase + "/:id", wsp);
        a.registerGin("PUT", wspBase + "/:id/credentials", wsp);
        a.registerGin("DELETE", wspBase + "/:id/credentials/:field", wsp);
        a.registerGin("POST", wspBase + "/:id/test", wsp);

        APIKeyRoutePolicy vs = APIKeyRoutePolicy.manageVectorStores(APIKeyRoutePolicy.fullAccess());
        final String vsBase = "/api/v1/vector-stores";
        a.registerGin("GET", vsBase + "/types", vs);
        a.registerGin("POST", vsBase + "/test", vs);
        a.registerGin("POST", vsBase, vs);
        a.registerGin("GET", vsBase, vs);
        a.registerGin("GET", vsBase + "/:id", vs);
        a.registerGin("PUT", vsBase + "/:id", vs);
        a.registerGin("DELETE", vsBase + "/:id", vs);
        a.registerGin("POST", vsBase + "/:id/test", vs);

        APIKeyRoutePolicy sb = APIKeyRoutePolicy.manageStorageBackends(APIKeyRoutePolicy.fullAccess());
        final String sbBase = "/api/v1/storage-backends";
        a.registerGin("GET", sbBase + "/types", sb);
        a.registerGin("POST", sbBase + "/test", sb);
        a.registerGin("POST", sbBase, sb);
        a.registerGin("GET", sbBase, sb);
        a.registerGin("GET", sbBase + "/:id", sb);
        a.registerGin("PUT", sbBase + "/:id", sb);
        a.registerGin("DELETE", sbBase + "/:id", sb);
        a.registerGin("POST", sbBase + "/:id/test", sb);
        a.registerGin("PUT", sbBase + "/:id/default", sb);
    }

    /**
     * 数据源（对照 Go {@code router/routes_infra.go} L299-332 的 {@code /datasource} 组）。
     *
     * <p>整组共用 {@code apiKeyManageDataSources(apiKeyFullAccess())} = 要求 full-access
     * 且能力清单含 {@code manage_data_sources}——与 {@code /models}、{@code /mcp-services}
     * 的处置完全同构（"full-access 或显式带该能力"）。</p>
     *
     * <p><b>17 条</b>——与 {@code RegisterDataSourceRoutes} 注册的条数一致。读端与写端
     * 都在这一条策略下；角色维度（Viewer / Admin）由 {@code RbacInterceptor} 另行把关，
     * 两个维度互相独立（Go 里能力判定先于角色判定）。</p>
     */
    private static void registerDataSourceRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy ds = APIKeyRoutePolicy.manageDataSources(APIKeyRoutePolicy.fullAccess());
        final String base = "/api/v1/datasource";
        a.registerGin("GET", base + "/types", ds);
        a.registerGin("POST", base + "/validate-credentials", ds);
        a.registerGin("POST", base, ds);
        a.registerGin("GET", base, ds);
        a.registerGin("GET", base + "/:id", ds);
        a.registerGin("PUT", base + "/:id", ds);
        a.registerGin("DELETE", base + "/:id", ds);
        a.registerGin("PUT", base + "/:id/credentials", ds);
        a.registerGin("DELETE", base + "/:id/credentials/:field", ds);
        a.registerGin("POST", base + "/:id/validate", ds);
        a.registerGin("GET", base + "/:id/resources", ds);
        a.registerGin("POST", base + "/:id/resource-ancestors", ds);
        a.registerGin("POST", base + "/:id/sync", ds);
        a.registerGin("POST", base + "/:id/pause", ds);
        a.registerGin("POST", base + "/:id/resume", ds);
        a.registerGin("GET", base + "/:id/logs", ds);
        a.registerGin("GET", base + "/logs/:log_id", ds);
    }

    /**
     * 长期记忆（对照 Go {@code router/routes_memory.go} L17-40 的 16 条路由）。
     *
     * <p>整组共用 {@code apiKeyFullAccess()} = {@code {RequireFullAccess: true}}，
     * 而且<b>刻意不带任何能力清单</b>——Go 的注释把理由写死了：
     * 「记忆空间属于一个人，scoped 集成 key 不该继承一个」。
     * 所以带 {@code chat} 的 Key 能跑完整对话流程，却读不到同一批记忆：
     * 记忆是<b>按人</b>的（{@code subject_id = principal.StorageID()}），
     * 而一把集成 Key 代表的是一个系统、不是一个人。</p>
     *
     * <p>注意与 {@code /api/v1/sessions/continue-stream/*} 的对比：那边是
     * {@code chat(fullAccess())}（scoped Key 凭 {@code chat} 能力即可进），
     * 这边是纯 full-access。两者的差别是有意的，别顺手统一。</p>
     */
    private static void registerMemoryRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy memory = APIKeyRoutePolicy.fullAccess();
        final String base = "/api/v1/memory";
        a.registerGin("GET", base + "/settings", memory);
        a.registerGin("PUT", base + "/settings", memory);
        a.registerGin("GET", base + "/items", memory);
        a.registerGin("POST", base + "/items", memory);
        a.registerGin("DELETE", base + "/items", memory);
        a.registerGin("PUT", base + "/items/:id", memory);
        a.registerGin("DELETE", base + "/items/:id", memory);
        a.registerGin("POST", base + "/items/:id/confirm", memory);
        a.registerGin("POST", base + "/items/:id/reject", memory);
        a.registerGin("GET", base + "/topics", memory);
        a.registerGin("DELETE", base + "/topics/:id", memory);
        a.registerGin("POST", base + "/topics/:id/promote", memory);
        a.registerGin("GET", base + "/documents", memory);
        a.registerGin("DELETE", base + "/documents/:id", memory);
        a.registerGin("GET", base + "/export", memory);
        a.registerGin("POST", base + "/consolidate", memory);
    }

    /**
     * 会话路由（对照 Go router/routes_chat.go L53-83 的 {@code /sessions} 组 + L85 的
     * {@code continue-stream}）。
     *
     * <p>会话是**按用户的聊天状态**、不是知识库内容，所以整组共用
     * {@code apiKeyChat(apiKeyFullAccess())}：一把 scoped key 因此能跑完整的对话流程
     * （建/管自己的会话）而无需全租户权限。消息 / steer / 附件 / 产物等端点在各自落地时补。</p>
     */
    private static void registerSessionRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy sessions = APIKeyRoutePolicy.chat(APIKeyRoutePolicy.fullAccess());
        a.registerGin("GET", "/api/v1/sessions/continue-stream/:session_id", sessions);
        // 波 1 G1：会话 CRUD + 置顶
        a.registerGin("POST", "/api/v1/sessions", sessions);
        a.registerGin("GET", "/api/v1/sessions", sessions);
        a.registerGin("GET", "/api/v1/sessions/:id", sessions);
        a.registerGin("PUT", "/api/v1/sessions/:id", sessions);
        a.registerGin("DELETE", "/api/v1/sessions/:id", sessions);
        a.registerGin("DELETE", "/api/v1/sessions/batch", sessions);
        a.registerGin("POST", "/api/v1/sessions/:session_id/pin", sessions);
        a.registerGin("DELETE", "/api/v1/sessions/:id/pin", sessions);
        a.registerGin("DELETE", "/api/v1/sessions/:id/messages", sessions);
        // 追问建议 3 条（routes_chat.go L89-91，同组 chat 能力）
        a.registerGin("POST", "/api/v1/sessions/:session_id/messages/:message_id/suggestions", sessions);
        a.registerGin("GET", "/api/v1/sessions/:id/messages/:message_id/suggestions", sessions);
        a.registerGin("POST", "/api/v1/sessions/:session_id/suggestion-events", sessions);
        // 产物 3 条 + generate_title + stop（routes_chat.go L60-67、L105-107，同组 chat 能力）
        a.registerGin("GET", "/api/v1/sessions/:id/artifacts", sessions);
        a.registerGin("GET", "/api/v1/sessions/:id/messages/:message_id/artifacts", sessions);
        a.registerGin("GET", "/api/v1/sessions/:id/messages/:message_id/artifacts/:index/download", sessions);
        a.registerGin("POST", "/api/v1/sessions/:session_id/generate_title", sessions);
        a.registerGin("POST", "/api/v1/sessions/:session_id/stop", sessions);
        // steer 4 条（routes_chat.go L74-77，同组 chat 能力）
        a.registerGin("POST", "/api/v1/sessions/:session_id/steer", sessions);
        a.registerGin("GET", "/api/v1/sessions/:id/steer", sessions);
        a.registerGin("DELETE", "/api/v1/sessions/:id/steer/:steer_id", sessions);
        a.registerGin("POST", "/api/v1/sessions/:session_id/steer/:steer_id/inject", sessions);
        // 临时文档 attachments 5 条（routes_chat.go L61-65，同组 chat 能力）
        a.registerGin("POST", "/api/v1/sessions/:session_id/attachments", sessions);
        a.registerGin("GET", "/api/v1/sessions/:id/attachments", sessions);
        a.registerGin("GET", "/api/v1/sessions/:id/attachments/:attachment_id", sessions);
        a.registerGin("GET", "/api/v1/sessions/:id/attachments/:attachment_id/preview", sessions);
        a.registerGin("DELETE", "/api/v1/sessions/:id/attachments/:attachment_id", sessions);
    }

    /**
     * 消息路由（对照 Go router/routes_chat.go L16-33 的 {@code /messages} 组）。
     *
     * <p>消息历史对 API Key 默认是 full-access 面，但细分子能力：
     * {@code /search} 与 {@code /chat-history-stats} 要 {@code message_history}
     * （租户级聊天历史元数据），{@code /load} 与 DELETE 要 {@code chat}
     * （操作自己会话里的消息，所有权由 message service 把关）。</p>
     */
    private static void registerMessageRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy full = APIKeyRoutePolicy.fullAccess();
        APIKeyRoutePolicy history = APIKeyRoutePolicy.messageHistory(full);
        APIKeyRoutePolicy chat = APIKeyRoutePolicy.chat(full);
        a.registerGin("POST", "/api/v1/messages/search", history);
        a.registerGin("GET", "/api/v1/messages/chat-history-stats", history);
        a.registerGin("GET", "/api/v1/messages/:session_id/load", chat);
        a.registerGin("DELETE", "/api/v1/messages/:session_id/:id", chat);
    }

    /**
     * 认证路由（对照 Go router/routes_auth_tenant.go L231）：
     * {@code GET /api/v1/auth/me} 用 {@code apiKeyAny()}——
     * 任何有效 Key（含 scoped）都能读自己的身份，这是集成方探测"我这把 key 是谁"的入口。
     */
    private static void registerAuthRoutes(APIKeyRouteAuthorizer a) {
        a.registerGin("GET", "/api/v1/auth/me", APIKeyRoutePolicy.any());
    }

    /**
     * 模型 + WeKnoraCloud（对照 Go router/routes_infra.go L21-39 与 L341-342）。
     *
     * <p>整个 {@code /models} 分组共用 {@code apiKeyManageModels(apiKeyFullAccess())}：
     * {@code RequireFullAccess = true} 且能力清单是 {@code [manage_models]}。
     * 语义是"要么 full-access，要么显式带 manage_models"——
     * 只带 {@code ingest} 的 Key 连 {@code GET /models} 都读不了
     * （Go 测试 {@code TestGateFullAccessAndCapabilityPolicies} 钉了这一点）。</p>
     */
    private static void registerModelRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy models = APIKeyRoutePolicy.manageModels(APIKeyRoutePolicy.fullAccess());
        a.registerGin("GET", "/api/v1/models/providers", models);
        a.registerGin("POST", "/api/v1/models", models);
        a.registerGin("GET", "/api/v1/models", models);
        a.registerGin("GET", "/api/v1/models/:id", models);
        a.registerGin("PUT", "/api/v1/models/:id", models);
        a.registerGin("DELETE", "/api/v1/models/:id", models);
        a.registerGin("PUT", "/api/v1/models/:id/credentials", models);
        a.registerGin("DELETE", "/api/v1/models/:id/credentials/:field", models);
        // WeKnoraCloud（routes_infra.go:341-342）
        a.registerGin("POST", "/api/v1/weknoracloud/credentials", models);
        a.registerGin("GET", "/api/v1/models/weknoracloud/status", models);
    }

    /**
     * 知识库 / 文档 / Wiki（对照 Go router/routes_knowledge.go L19-334）。
     *
     * <p>两档能力是刻意分开的（Go 注释反复强调）：</p>
     * <ul>
     *   <li><b>retrieve</b>：读/检索知识库数据、hybrid-search、下载、移动目标、
     *       Wiki 的全部读端点。<b>不含</b>任何写操作；</li>
     *   <li><b>ingest</b>：把内容写进知识库（上传文档、改分块/FAQ/标签/Wiki 页）。
     *       <b>不含</b>建知识库、建 agent、清空 KB；且 KB 白名单仍逐次生效。</li>
     *   <li><b>manage_kbs</b>：知识库自身生命周期（建/复制/改/删 + config）。
     *       <b>不是</b> ingest 的升级版——"能改内容"与"能管知识库"是两件事。</li>
     * </ul>
     */
    private static void registerKnowledgeAndWikiRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy kbRead = APIKeyRoutePolicy.retrieve(APIKeyRoutePolicy.fullAccess());
        APIKeyRoutePolicy kbManage = APIKeyRoutePolicy.manageKnowledgeBases(APIKeyRoutePolicy.fullAccess());
        APIKeyRoutePolicy kbIngest = APIKeyRoutePolicy.ingest(APIKeyRoutePolicy.fullAccess());

        // /knowledge-bases 分组（routes_knowledge.go:198-244；base=retrieve，kbManagement=manage_kbs）
        a.registerGin("POST", "/api/v1/knowledge-bases", kbManage);
        a.registerGin("GET", "/api/v1/knowledge-bases", kbRead);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id", kbRead);
        a.registerGin("PUT", "/api/v1/knowledge-bases/:id", kbManage);
        a.registerGin("DELETE", "/api/v1/knowledge-bases/:id", kbManage);
        a.registerGin("PUT", "/api/v1/knowledge-bases/:id/pin", kbRead);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/move-targets", kbRead);

        // 波 2 第三批（routes_knowledge.go:227-242）：copy/duplicate 与 create 同档
        // （manage_kbs）；hybrid-search 的 POST/GET 都在 kb 组（retrieve）；copy/progress
        // 是独立的 retrieve|manage_kbs 组合（Go: kb.With(apiKeyRetrieve(apiKeyManageKnowledgeBases(...)))）。
        // 注意 /copy 静态段先于 /:id 登记。
        a.registerGin("POST", "/api/v1/knowledge-bases/copy", kbManage);
        a.registerGin("GET", "/api/v1/knowledge-bases/copy/progress/:task_id",
                APIKeyRoutePolicy.retrieve(APIKeyRoutePolicy.manageKnowledgeBases(APIKeyRoutePolicy.fullAccess())));
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/hybrid-search", kbRead);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/hybrid-search", kbRead);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/duplicate", kbManage);

        // KB 作用域的文档写入 / 读（routes_knowledge.go:70-81；kb=ingest，kbRead=retrieve；
        // 清空 KB 整库内容只允许 full-access key：kb.With(apiKeyFullAccess()).DELETE(...)）
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/knowledge/file", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/knowledge/url", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/knowledge/manual", kbIngest);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/knowledge", kbRead);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/knowledge/folders", kbRead);
        a.registerGin("PUT", "/api/v1/knowledge-bases/:id/knowledge/folders", kbIngest);
        a.registerGin("DELETE", "/api/v1/knowledge-bases/:id/knowledge", APIKeyRoutePolicy.fullAccess());

        // 文档（routes_knowledge.go:86-132；k=ingest，kRead=retrieve。波 2 第二批补齐：
        // 批处理/跨文档端点没有单一 :id 可挂，能力照单文档兄弟登记 ingest/retrieve，
        // KB 白名单在 handler/service 内逐次收口）
        a.registerGin("GET", "/api/v1/knowledge/batch", kbRead);
        a.registerGin("GET", "/api/v1/knowledge/:id", kbRead);
        a.registerGin("GET", "/api/v1/knowledge/:id/stages", kbRead);
        a.registerGin("GET", "/api/v1/knowledge/:id/spans", kbRead);
        a.registerGin("POST", "/api/v1/knowledge/:id/regenerate-summary", kbIngest);
        a.registerGin("PUT", "/api/v1/knowledge/manual/:id", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge/:id/reparse", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge/:id/cancel-parse", kbIngest);
        a.registerGin("GET", "/api/v1/knowledge/:id/download", kbRead);
        a.registerGin("GET", "/api/v1/knowledge/:id/preview", kbRead);
        a.registerGin("PUT", "/api/v1/knowledge/image/:id/:chunk_id", kbIngest);
        a.registerGin("PUT", "/api/v1/knowledge/tags", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge/batch-reparse", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge/batch-delete", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge/folder", kbIngest);

        // FAQ（routes_knowledge.go:149-180；faq=ingest，faqRead=retrieve——entries 读=retrieve、
        // 写=ingest，与文档同档；search 是 POST 但挂 faqRead；导入进度路由在 KB 组外，
        // retrieve|ingest 都可轮询发起过的任务）。KB 白名单仍由 KBAccess 层逐次收口。
        APIKeyRoutePolicy faqRead = APIKeyRoutePolicy.retrieve(APIKeyRoutePolicy.fullAccess());
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/faq/entries", faqRead);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/faq/entries/export", faqRead);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/faq/entries/:entry_id", faqRead);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/faq/search", faqRead);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/faq/entries", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/faq/entry", kbIngest);
        a.registerGin("PUT", "/api/v1/knowledge-bases/:id/faq/entries/:entry_id", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/faq/entries/:entry_id/similar-questions", kbIngest);
        a.registerGin("PUT", "/api/v1/knowledge-bases/:id/faq/entries/fields", kbIngest);
        a.registerGin("PUT", "/api/v1/knowledge-bases/:id/faq/entries/tags", kbIngest);
        a.registerGin("DELETE", "/api/v1/knowledge-bases/:id/faq/entries", kbIngest);
        a.registerGin("PUT", "/api/v1/knowledge-bases/:id/faq/import/last-result/display", kbIngest);
        a.registerGin("GET", "/api/v1/faq/import/progress/:task_id",
                APIKeyRoutePolicy.retrieve(APIKeyRoutePolicy.ingest(APIKeyRoutePolicy.fullAccess())));
        // 波 2 第三批（routes_knowledge.go:121-132）：search/move-progress 是 retrieve
        // （kRead），move 是内容写（k=ingest，requireTenantAPIKeyKnowledgeBases 在
        // handler 内把 source+target 兜进白名单）
        a.registerGin("GET", "/api/v1/knowledge/search", kbRead);
        a.registerGin("GET", "/api/v1/knowledge/move/progress/:task_id", kbRead);
        a.registerGin("POST", "/api/v1/knowledge/move", kbIngest);
        a.registerGin("PUT", "/api/v1/knowledge/:id", kbIngest);
        a.registerGin("DELETE", "/api/v1/knowledge/:id", kbIngest);

        // Wiki（routes_knowledge.go:295-334；wiki=ingest，wikiRead=retrieve）
        // 注意前缀是 /knowledgebase（**无连字符**），与知识库的 /knowledge-bases 不同。
        final String wiki = "/api/v1/knowledgebase/:kb_id/wiki";
        a.registerGin("GET", wiki + "/pages", kbRead);
        a.registerGin("POST", wiki + "/pages", kbIngest);
        a.registerGin("PUT", wiki + "/move-page", kbIngest);
        a.registerGin("GET", wiki + "/pages/*slug", kbRead);
        a.registerGin("PUT", wiki + "/pages/*slug", kbIngest);
        a.registerGin("DELETE", wiki + "/pages/*slug", kbIngest);
        a.registerGin("GET", wiki + "/revisions/*slug", kbRead);
        a.registerGin("POST", wiki + "/revert", kbIngest);
        a.registerGin("GET", wiki + "/folders", kbRead);
        a.registerGin("POST", wiki + "/folders", kbIngest);
        a.registerGin("PUT", wiki + "/folders/:folder_id", kbIngest);
        a.registerGin("DELETE", wiki + "/folders/:folder_id", kbIngest);
        a.registerGin("GET", wiki + "/index", kbRead);
        a.registerGin("GET", wiki + "/graph", kbRead);
        a.registerGin("GET", wiki + "/stats", kbRead);
        a.registerGin("GET", wiki + "/search", kbRead);
        a.registerGin("POST", wiki + "/rebuild-links", kbIngest);
        a.registerGin("GET", wiki + "/lint", kbRead);
        a.registerGin("POST", wiki + "/auto-fix", kbIngest);
        a.registerGin("GET", wiki + "/issues", kbRead);
        a.registerGin("PUT", wiki + "/issues/:issue_id/status", kbIngest);

        // chunks（routes_knowledge.go:27-53；chunks 组=ingest，chunkRead=retrieve；
        // 注释原文：Scoped API key 需要 ingest 能力写内容，retrieve 能力读内容，
        // 两者仍受 KB 白名单约束——白名单校验在 ChunkAccessGuard.requireKbAccess）
        final APIKeyRoutePolicy chunkIngest = APIKeyRoutePolicy.ingest(APIKeyRoutePolicy.fullAccess());
        final APIKeyRoutePolicy chunkRead = APIKeyRoutePolicy.retrieve(APIKeyRoutePolicy.fullAccess());
        a.registerGin("GET", "/api/v1/chunks/:knowledge_id", chunkRead);
        a.registerGin("GET", "/api/v1/chunks/by-id/:id", chunkRead);
        a.registerGin("GET", "/api/v1/chunks/:knowledge_id/:id/revisions", chunkRead);
        a.registerGin("DELETE", "/api/v1/chunks/:knowledge_id/:id", chunkIngest);
        a.registerGin("DELETE", "/api/v1/chunks/:knowledge_id", chunkIngest);
        a.registerGin("PUT", "/api/v1/chunks/:knowledge_id/:id", chunkIngest);
        a.registerGin("POST", "/api/v1/chunks/:knowledge_id/:id/revert", chunkIngest);
        a.registerGin("DELETE", "/api/v1/chunks/by-id/:id/questions", chunkIngest);
        a.registerGin("PUT", "/api/v1/chunks/by-id/:id/questions", chunkIngest);
        a.registerGin("POST", "/api/v1/chunks/by-id/:id/questions/regenerate", chunkIngest);

        // chunker 预览（routes_knowledge.go:19 RegisterChunkerDebugRoutes）：
        // apiKeyRetrieve(apiKeyIngest(apiKeyFullAccess()))——单条路由同时要
        // retrieve+ingest 两个能力（读内容面 + 写内容面的组合门），无 KB 白名单
        // 可挂（不碰任何存储）。favorites 不在此登记：Go 注释原文 "not declared
        // for API keys (default-deny)"，未声明的路由走默认拒绝。
        a.registerGin("POST", "/api/v1/chunker/preview",
                APIKeyRoutePolicy.retrieve(APIKeyRoutePolicy.ingest(APIKeyRoutePolicy.fullAccess())));
    }

    /**
     * 沙箱配置（对照 Go router/routes_infra.go L49-74 RegisterSandboxConfigRoutes）：
     * 整组 {@code apiKeyGroup(..., apiKeyFullAccess())}——Go 注释原文：这些是持有
     * provider 凭据的工作区基础设施，scoped key 不能安全地获得部分权限（变更可能
     * 遗弃远端沙箱），所以只有 full-access key 能进，子批 2 的 skills 子资源同组同档。
     */
    private static void registerSandboxConfigRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy sandboxConfigs = APIKeyRoutePolicy.fullAccess();
        a.registerGin("GET", "/api/v1/sandbox-configs", sandboxConfigs);
        a.registerGin("PUT", "/api/v1/sandbox-configs/workspace-policy", sandboxConfigs);
        a.registerGin("POST", "/api/v1/sandbox-configs/templates/query", sandboxConfigs);
        a.registerGin("POST", "/api/v1/sandbox-configs", sandboxConfigs);
        a.registerGin("GET", "/api/v1/sandbox-configs/:id", sandboxConfigs);
        a.registerGin("PUT", "/api/v1/sandbox-configs/:id", sandboxConfigs);
        a.registerGin("DELETE", "/api/v1/sandbox-configs/:id", sandboxConfigs);
        a.registerGin("GET", "/api/v1/sandbox-configs/:id/sandboxes", sandboxConfigs);
        // 波 3 子批 2：skills 子资源（同组同档 fullAccess）+ sandbox-check（/system 组
        // 的 manageVectorStores(fullAccess())，与 storage-engine-check 同档）
        a.registerGin("GET", "/api/v1/sandbox-configs/:id/skills", sandboxConfigs);
        a.registerGin("POST", "/api/v1/sandbox-configs/:id/skills", sandboxConfigs);
        a.registerGin("GET", "/api/v1/sandbox-configs/:id/skills/:skillId", sandboxConfigs);
        a.registerGin("PATCH", "/api/v1/sandbox-configs/:id/skills/:skillId", sandboxConfigs);
        a.registerGin("DELETE", "/api/v1/sandbox-configs/:id/skills/:skillId", sandboxConfigs);
        a.registerGin("GET", "/api/v1/sandbox-configs/:id/skills/:skillId/files", sandboxConfigs);
        a.registerGin("GET", "/api/v1/sandbox-configs/:id/skills/:skillId/files/content", sandboxConfigs);
        a.registerGin("POST", "/api/v1/sandbox-configs/:id/skills/:skillId/reinstall", sandboxConfigs);
        a.registerGin("GET", "/api/v1/sandbox-configs/:id/skills/:skillId/guidance", sandboxConfigs);
        a.registerGin("POST", "/api/v1/sandbox-configs/:id/skills/:skillId/guidance", sandboxConfigs);
        a.registerGin("POST", "/api/v1/sandbox-configs/:id/skills/:skillId/stop", sandboxConfigs);
        a.registerGin("GET", "/api/v1/sandbox-configs/:id/skills/:skillId/install-events", sandboxConfigs);
        a.registerGin("GET", "/api/v1/sandbox-configs/:id/skills/:skillId/transcript", sandboxConfigs);
        a.registerGin("POST", "/api/v1/system/sandbox-check",
                APIKeyRoutePolicy.manageVectorStores(APIKeyRoutePolicy.fullAccess()));
        // 波 3 子批 4（routes_agent.go RegisterSkillRoutes L70-90）：catalogWrite 组
        // = apiKeyGroup(fullAccess)——catalog 写会烤进沙箱镜像，只有 full-access key
        // 能进。GET /skills 与 GET /skills/catalog **不登记**：Go 里这两条只挂角色门
        // 未声明 API-key 能力 → 未声明 = 默认拒绝（与 favorites 同款注释）。
        APIKeyRoutePolicy catalogWrite = APIKeyRoutePolicy.fullAccess();
        a.registerGin("POST", "/api/v1/skills/catalog", catalogWrite);
        a.registerGin("POST", "/api/v1/skills/catalog/:id/install", catalogWrite);
        a.registerGin("GET", "/api/v1/skills/catalog/:id/files", catalogWrite);
        a.registerGin("GET", "/api/v1/skills/catalog/:id/files/content", catalogWrite);
        a.registerGin("DELETE", "/api/v1/skills/catalog/:id", catalogWrite);
    }

    /**
     * MCP 服务（对照 Go router/routes_infra.go L149-185）：
     * 整组共用 {@code apiKeyManageMCPServices(apiKeyFullAccess())}——
     * CRUD、测试、工具/资源/元数据、工具策略、凭据、OAuth 发起/查询/撤销全在列。
     *
     * <p>{@code /api/v1/mcp-oauth/callback} 是**公开路由**（靠一次性 state 自证），
     * 不走门禁，因此这里不登记。</p>
     */
    private static void registerMcpRoutes(APIKeyRouteAuthorizer a) {
        APIKeyRoutePolicy mcp = APIKeyRoutePolicy.manageMcpServices(APIKeyRoutePolicy.fullAccess());
        a.registerGin("POST", "/api/v1/mcp-services", mcp);
        a.registerGin("GET", "/api/v1/mcp-services", mcp);
        a.registerGin("GET", "/api/v1/mcp-services/:id", mcp);
        a.registerGin("PUT", "/api/v1/mcp-services/:id", mcp);
        a.registerGin("DELETE", "/api/v1/mcp-services/:id", mcp);
        a.registerGin("POST", "/api/v1/mcp-services/:id/test", mcp);
        a.registerGin("GET", "/api/v1/mcp-services/:id/tools", mcp);
        a.registerGin("GET", "/api/v1/mcp-services/:id/resources", mcp);
        a.registerGin("GET", "/api/v1/mcp-services/:id/metadata", mcp);
        a.registerGin("POST", "/api/v1/mcp-services/:id/metadata/refresh", mcp);
        a.registerGin("POST", "/api/v1/mcp-services/:id/usage-instructions/generate", mcp);
        a.registerGin("GET", "/api/v1/mcp-services/:id/tool-approvals", mcp);
        a.registerGin("PUT", "/api/v1/mcp-services/:id/tool-approvals/:tool_name", mcp);
        a.registerGin("PUT", "/api/v1/mcp-services/:id/credentials", mcp);
        a.registerGin("DELETE", "/api/v1/mcp-services/:id/credentials/:field", mcp);
        a.registerGin("POST", "/api/v1/mcp-services/:id/oauth/authorize-url", mcp);
        a.registerGin("GET", "/api/v1/mcp-services/:id/oauth/status", mcp);
        a.registerGin("DELETE", "/api/v1/mcp-services/:id/oauth/token", mcp);
    }

    // ── 尚未翻译、随模块回补的策略（保留在此处作为清单，暂不登记） ──
    //
    // 对照 Go 源，回补时把对应行搬进上面的 registerXxx 即可：
    //   routes_infra.go        L52-126   sandbox-configs(fullAccess only)、evaluation(run_evaluations)、
    //                                    initialization(retrieve / manage_kbs / manage_models)、channels
    //   routes_infra.go        L149-201  MCP（已登记）+ /agent/tool-approvals（**default deny**）
    //   routes_infra.go        L210       /web-search/providers（**default deny**，本批确认）
    //   routes_infra.go        L205-284  web-search-providers / vector-stores / storage-backends（已登记）
    //   routes_infra.go        L297-342   system/admin 控制面（PlatformOnly）
    //   routes_knowledge.go    L19-53    chunker/preview、chunks/**（chunks 段已登记）
    //   routes_knowledge.go    L118-244  标签、copy/duplicate/progress（文档操作面与 FAQ 已登记）
    //   routes_chat.go         L24-131   messages、sessions、knowledge-chat、agent-chat、knowledge-search
    //   routes_agent.go        L22-136   agents、favorites、skills、organizations、shares
    //   routes_auth_tenant.go  L53-136   members/invitations/api-principal（已登记）；
    //                                    /leave 与 /me/invitations**（default deny，本批确认）
    //   files.go               L336-510  KB 作用域文件代理（retrieve + AllowFileServeAPIKey）

    // ── 供测试/后续模块复用的策略样例（与 Go router/rbac.go 的构造器同构） ──

    /** 平台控制面（对照 routes_auth_tenant.go L53-57 与 L293 的 system/admin 组）。 */
    public static APIKeyRoutePolicy platformTenantsReadPolicy() {
        return APIKeyRoutePolicy.platform(
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_READ,
                com.ragagent.apikey.domain.APIKeyCapability.SYSTEM_TENANTS_MANAGE);
    }

    /** 会话类路由的 chat 策略（对照 routes_chat.go L51）。 */
    public static APIKeyRoutePolicy chatPolicy() {
        return APIKeyRoutePolicy.chat(APIKeyRoutePolicy.fullAccess());
    }

    /** agent 读路由的三能力叠加（对照 routes_agent.go L26）。 */
    public static APIKeyRoutePolicy agentReadPolicy() {
        return APIKeyRoutePolicy.readAgents(
                APIKeyRoutePolicy.manageAgents(
                        APIKeyRoutePolicy.chat(APIKeyRoutePolicy.fullAccess())));
    }

    /** 租户设置读写（对照 routes_auth_tenant.go L73-81）。 */
    public static APIKeyRoutePolicy tenantSettingsPolicy() {
        return APIKeyRoutePolicy.manageTenantSettings(APIKeyRoutePolicy.fullAccess());
    }
}
