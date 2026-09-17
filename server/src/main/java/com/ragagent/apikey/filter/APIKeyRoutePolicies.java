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

        // KB 作用域的文档写入 / 读（routes_knowledge.go:70-81；kb=ingest，kbRead=retrieve）
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/knowledge/file", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/knowledge/url", kbIngest);
        a.registerGin("POST", "/api/v1/knowledge-bases/:id/knowledge/manual", kbIngest);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/knowledge", kbRead);
        a.registerGin("GET", "/api/v1/knowledge-bases/:id/knowledge/folders", kbRead);

        // 文档（routes_knowledge.go:86-132；k=ingest，kRead=retrieve）
        a.registerGin("GET", "/api/v1/knowledge/:id", kbRead);
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
    //                                    initialization(retrieve / manage_kbs / manage_models)、
    //                                    web-search-providers、vector-stores、storage-backends、
    //                                    datasource、channels
    //   routes_infra.go        L149-201  MCP（已登记）+ /agent/tool-approvals（**default deny**）
    //   routes_infra.go        L210       /web-search/providers
    //   routes_infra.go        L297-342   system/admin 控制面（PlatformOnly）
    //   routes_knowledge.go    L19-53    chunker/preview、chunks/**
    //   routes_knowledge.go    L118-244  文档下载/预览/批量、FAQ、标签、copy/duplicate/progress
    //   routes_chat.go         L24-131   messages、sessions、knowledge-chat、agent-chat、knowledge-search
    //   routes_agent.go        L22-136   agents、favorites、skills、organizations、shares
    //   routes_auth_tenant.go  L53-136   tenants/**、members、invitations（注意 /api-keys 是 default deny）
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
