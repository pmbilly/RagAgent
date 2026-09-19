package com.ragagent.config;

import java.util.List;

import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.filter.AuthFilter;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.filter.RequestIdFilter;
import com.ragagent.common.web.RbacInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 对照 Go internal/router/router.go 的全局装配：
 * CORS → RequestID → (Language/Logger/Recovery 由 Spring 等价物承担) → Auth。
 * 错误处理：Go 的 Recovery/ErrorHandler 由 GlobalExceptionHandler + Spring 默认错误机制承担，
 * 响应契约由 golden 测试锁定。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantProperties tenantProperties;

    public WebConfig(TenantProperties tenantProperties) {
        this.tenantProperties = tenantProperties;
    }

    /** 对照 gin cors.Config：通配 Origin、显式头清单、MaxAge 12h */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Origin", "Content-Type", "Accept", "Authorization", "X-API-Key",
                "X-Request-ID", "X-Tenant-ID", "X-Embed-Session",
                "X-External-User-ID", "X-External-User-Token"));
        config.setExposedHeaders(List.of("Content-Length", "Access-Control-Allow-Origin"));
        config.setAllowCredentials(true);
        config.setMaxAge(12L * 3600);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(corsConfigurationSource()));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> bean = new FilterRegistrationBean<>(new RequestIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /** 对照 Go Auth 中间件（engine 全局，覆盖 /*） */
    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(UserService userService,
                                                         TenantService tenantService,
                                                         TenantMemberService memberService,
                                                         TenantProperties tenantProperties,
                                                         com.ragagent.apikey.filter.APIKeyAuthChannel apiKeyAuthChannel) {
        FilterRegistrationBean<AuthFilter> bean =
                new FilterRegistrationBean<>(new AuthFilter(userService, tenantService, memberService,
                        tenantProperties, apiKeyAuthChannel));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /**
     * 清理 API Key 作用域 ThreadLocal。Servlet 线程池会复用线程，
     * 不清理会让后续的 JWT 请求被误判成 API Key 主体（对照 Go 的 per-request 值语义）。
     */
    @Bean
    public FilterRegistrationBean<com.ragagent.apikey.filter.APIKeyScopeCleanupFilter> apiKeyScopeCleanupFilter() {
        FilterRegistrationBean<com.ragagent.apikey.filter.APIKeyScopeCleanupFilter> bean =
                new FilterRegistrationBean<>(new com.ragagent.apikey.filter.APIKeyScopeCleanupFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /**
     * 对照 Go router/rbac.go 的守卫矩阵（阶段 2：models + weknoracloud）。
     * 拦截器运行在 servlet filter（Auth）之后、controller 之前，顺序与 Go 中间件链一致。
     * 静态段（providers / weknoracloud/status）规则先于 /{id} 通配注册，等价 gin 静态优先。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RbacInterceptor rbac = new RbacInterceptor(tenantProperties);

        // API Key 能力维度的门禁（对照 Go middleware.APIKeyRouteAuthorizer.Middleware）。
        // 必须**排在角色维度的 RbacInterceptor 之前**：Go 里能力判定先于角色判定，
        // 且 RbacInterceptor 对 API Key 主体短路（见其 apiKeyShortCircuit）。
        com.ragagent.apikey.filter.APIKeyRouteAuthorizer apiKeyAuthorizer =
                new com.ragagent.apikey.filter.APIKeyRouteAuthorizer();
        com.ragagent.apikey.filter.APIKeyRoutePolicies.registerAll(apiKeyAuthorizer);
        registry.addInterceptor(new com.ragagent.apikey.filter.APIKeyGateInterceptor(apiKeyAuthorizer))
                .addPathPatterns("/api/v1/**")
                .order(-1);

        // /models 组（对照 RegisterModelRoutes）
        rbac.addRule("GET", "/api/v1/models", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/models/providers", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/models/weknoracloud/status", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/models/*/debug", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/models", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/models/*/credentials", TenantRole.ADMIN, true);
        rbac.addRule("DELETE", "/api/v1/models/*/credentials/*", TenantRole.ADMIN, true);
        rbac.addRule("PUT", "/api/v1/models/*", TenantRole.ADMIN, true);
        rbac.addRule("DELETE", "/api/v1/models/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/models/*", TenantRole.VIEWER, false);
        // weknoracloud（对照 RegisterWeKnoraCloudRoutes）
        rbac.addRule("POST", "/api/v1/weknoracloud/credentials", TenantRole.ADMIN, false);
        // 知识库（对照 RegisterKnowledgeBaseRoutes/RegisterKnowledgeRoutes 阶段 3 子集）
        rbac.addRule("POST", "/api/v1/knowledge-bases", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/pin", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/move-targets", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("DELETE", "/api/v1/knowledge-bases/*", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*", TenantRole.VIEWER, false);
        // 波 2 第三批（对照 routes_knowledge.go L227-242）：
        // copy 是静态段，必须先于 /knowledge-bases/* 通配登记（AntPathMatcher 取首个命中）；
        // hybrid-search 的 POST/GET 都登记（Go 两条同一 handler）；duplicate=Contributor（create 档）。
        rbac.addRule("POST", "/api/v1/knowledge-bases/copy", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/copy/progress/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/hybrid-search", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/hybrid-search", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/duplicate", TenantRole.CONTRIBUTOR, false);
        // 文档（OwnedKBOrAdmin 的所有权判定在 controller/service 层，拦截器只做角色下限）
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/knowledge/file", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/knowledge/url", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/knowledge/manual", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/knowledge/folders", TenantRole.VIEWER, false);
        // 清空 KB 内容：Go 是 g.Admin()（Admin+），比同组写端更严
        rbac.addRule("DELETE", "/api/v1/knowledge-bases/*/knowledge", TenantRole.ADMIN, false);
        // 重命名文件夹：Go 无角色门（只有 OwnedKBOrAdmin + KBAccessWrite）→ 取最低的 VIEWER 下限
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/knowledge/folders", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/knowledge", TenantRole.VIEWER, false);
        // 文档操作面（波 2 第二批，对照 RegisterKnowledgeRoutes L86-133）：
        // 静态段（batch/tags/folder/batch-*）先于 /knowledge/* 通配登记（AntPathMatcher 取首个命中）；
        // 带 :id 的写端在 Go 里没有角色门（ownership 在控制器内）→ 一律 VIEWER 下限；
        // download 是 Contributor（比 preview 严）、批处理写是 Contributor。
        rbac.addRule("GET", "/api/v1/knowledge/batch", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge/tags", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge/batch-delete", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge/batch-reparse", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("POST", "/api/v1/knowledge/folder", TenantRole.CONTRIBUTOR, false);
        // 波 2 第三批（对照 routes_knowledge.go L121-132）：search/move/progress 与
        // 批处理同组——静态段先于 /knowledge/* 通配（move 是两段静态，search 单段）
        rbac.addRule("GET", "/api/v1/knowledge/search", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge/move/progress/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge/move", TenantRole.CONTRIBUTOR, false);
        // 两段路径（Ant 的 * 不跨 /，与 /knowledge/* 互不遮蔽，仍按静态段先登记）
        rbac.addRule("GET", "/api/v1/knowledge/*/stages", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge/*/spans", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge/*/download", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge/*/preview", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge/*/regenerate-summary", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge/manual/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge/*/reparse", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge/*/cancel-parse", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge/image/*/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge/*", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("DELETE", "/api/v1/knowledge/*", TenantRole.CONTRIBUTOR, false);
        rbac.addRule("GET", "/api/v1/knowledge/*", TenantRole.VIEWER, false);
        // chunks 读组（对照 RegisterChunkRoutes，routes_knowledge.go:27-53：
        // 读 = Viewer+；写无角色门，ownership 守卫在 ChunkController 内判定）
        rbac.addRule("GET", "/api/v1/chunks/by-id/*", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/chunks/*/*/revisions", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/chunks/*", TenantRole.VIEWER, false);
        // FAQ（对照 RegisterFAQRoutes，routes_knowledge.go:141-180）：读 = Viewer+
        // （KBAccessRead 在 FaqController）；写路由在 Go 里是 OwnedKBOrAdmin +
        // KBAccessWrite、**无角色门**（Viewer 创建的 KB 其本人可写）→ 一律 VIEWER 下限，
        // 所有权判定在 FaqController 内（requireKbWrite）。静态段（entries/fields/tags、
        // entry、search、import）先于 /entries/* 通配登记（AntPathMatcher 取首个命中）；
        // search 是 POST 但走 faqRead（retrieve 能力）。
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/faq/entries/export", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/faq/entries", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/faq/entries", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/knowledge-bases/*/faq/entries", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/faq/entry", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/faq/search", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/faq/entries/fields", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/faq/entries/tags", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/faq/import/last-result/display", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledge-bases/*/faq/entries/*/similar-questions", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/faq/entries/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledge-bases/*/faq/entries/*", TenantRole.VIEWER, false);
        // FAQ 导入进度（KB 作用域外）：Viewer+（对照 g.apiKeyRoute 的 g.Viewer()）
        rbac.addRule("GET", "/api/v1/faq/import/progress/*", TenantRole.VIEWER, false);
        // MCP 服务（对照 RegisterMCPServiceRoutes，routes_infra.go:149-185）
        // 更具体的路径必须排在 /mcp-services/* 之前，与 Go 的注册序一致
        rbac.addRule("POST", "/api/v1/mcp-services", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/mcp-services", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/mcp-services/*/test", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/tools", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/resources", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/mcp-services/*/metadata/refresh", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/metadata", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/mcp-services/*/usage-instructions/generate", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/mcp-services/*/credentials", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/mcp-services/*/credentials/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/tool-approvals", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/mcp-services/*/tool-approvals/*", TenantRole.ADMIN, false);
        // OAuth（routes_infra.go:183-185）：发起/查询/撤销都是 Viewer+
        rbac.addRule("POST", "/api/v1/mcp-services/*/oauth/authorize-url", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*/oauth/status", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/mcp-services/*/oauth/token", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/mcp-services/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/mcp-services/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/mcp-services/*", TenantRole.VIEWER, false);
        // agent 会话内的审批 / OAuth 决议（routes_infra.go:200-201）
        rbac.addRule("POST", "/api/v1/agent/tool-approvals/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/agent/mcp-oauth-resolutions/*", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/agent/mcp-oauth-resolutions/*/cancel", TenantRole.VIEWER, false);
        // 注意：/api/v1/mcp-oauth/callback 是**公开路由**（靠一次性 state 自证），不注册规则

        // Wiki（对照 RegisterWikiPageRoutes，routes_knowledge.go:294-334）
        // 注意路径前缀是 /knowledgebase（**无连字符**，与知识库的 /knowledge-bases 不同）
        // 写端点在 Go 里是 OwnedWikiKBOrAdmin = "Admin 或创建者本人"，**没有 Contributor 下限**
        // （Viewer 创建的 KB 其本人可写），所以这里只设 VIEWER 下限，所有权判定在控制器内。
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/pages", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/pages", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledgebase/*/wiki/move-page", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/pages/**", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledgebase/*/wiki/pages/**", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/knowledgebase/*/wiki/pages/**", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/revisions/**", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/revert", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/folders", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/folders", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledgebase/*/wiki/folders/*", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/knowledgebase/*/wiki/folders/*", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/index", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/graph", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/stats", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/search", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/rebuild-links", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/lint", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/knowledgebase/*/wiki/auto-fix", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/knowledgebase/*/wiki/issues", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/knowledgebase/*/wiki/issues/*/status", TenantRole.VIEWER, false);

        // 审计日志（对照 routes_auth_tenant.go:145 / knowledgebase 活动流 / routes 的 system 段）
        rbac.addRule("GET", "/api/v1/tenants/*/audit-log", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/knowledge-bases/*/activity", TenantRole.VIEWER, false);
        // 平台级审计：**仅系统管理员**（租户角色再高也不放行）
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/audit-log");

        // 系统管理端（波 2 收官批，对照 routes_auth_tenant.go L246-258 / L260-335）：
        // /system 组读端 Viewer+（"is the parser reachable"），主动探测远端的 check/
        // reconnect/storage-check Admin+（会拿租户凭据发起网络扇出）。/system/admin 组
        // 全部**仅系统管理员**（组级 SystemAdmin() → 逐条 addSystemAdminRule；
        // 静态段先于通配段登记，AntPathMatcher 取首个命中）。
        // POST /system/sandbox-check 未实现（依赖波 3 sandbox）→ 不登记规则、不登记策略。
        rbac.addRule("GET", "/api/v1/system/capabilities", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/system/info", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/system/parser-engines", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/system/parser-engines/check", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/system/docreader/reconnect", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/system/storage-engine-status", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/system/storage-engine-check", TenantRole.ADMIN, false);
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/promote");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/revoke");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/list");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/users/reset-password");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/users/create");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/api-keys");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/api-keys");
        rbac.addSystemAdminRule("DELETE", "/api/v1/system/admin/api-keys/*");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/settings");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/settings/*");
        rbac.addSystemAdminRule("PUT", "/api/v1/system/admin/settings/*");
        rbac.addSystemAdminRule("DELETE", "/api/v1/system/admin/settings/*");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/runtime/queues");
        rbac.addSystemAdminRule("GET", "/api/v1/system/admin/runtime/queues/*/tasks");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/runtime/queues/*/tasks/*/actions/*");
        rbac.addSystemAdminRule("DELETE", "/api/v1/system/admin/runtime/queues/*/archived");
        rbac.addSystemAdminRule("POST", "/api/v1/system/admin/tenants/apply-default-storage-quota");

        // 评估（对照 routes_infra.go L81-89）：POST 驱动 LLM+检索（Admin+），
        // GET 读结果（Viewer+）。
        rbac.addRule("POST", "/api/v1/evaluation", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/evaluation", TenantRole.VIEWER, false);

        // 会话（对照 routes_chat.go 的 sessions 组）：整组 Viewer 起步。
        // 目前只登记了已翻译的 continue-stream；同组其余端点在各自落地时补。
        rbac.addRule("GET", "/api/v1/sessions/continue-stream/*", TenantRole.VIEWER, false);

        // 长期记忆（对照 RegisterMemoryRoutes，routes_memory.go:17-40）
        // 守卫**只有 Viewer**：路径里没有任何 subject 参数，记忆空间一律从请求主体推导，
        // 所以不存在需要所有权判定的"别人的资源"。这里也**没有**管理端。
        // API-Key 侧要求 full-access（见 APIKeyRoutePolicies）——记忆空间属于个人，
        // scoped 集成 Key 不该继承一个。
        // 静态段先于通配段登记（与 Go 的注册序一致）：AntPathMatcher 取**首个**匹配，
        // 而 `/items/**` 这种 Ant 模式连 `/items` 本身都能匹配上。
        rbac.addRule("GET", "/api/v1/memory/settings", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/memory/settings", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/memory/items", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/memory/items", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/memory/items", TenantRole.VIEWER, false);
        // Go 的 `:id` 是**单段**参数，但 AntPathMatcher 的 `*` 不跨 `/`，
        // 够不到 `/items/:id/confirm` 这类两段路径 → 用 `/**` 覆盖（同 Wiki 段的写法）。
        rbac.addRule("PUT", "/api/v1/memory/items/**", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/memory/items/**", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/memory/items/**", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/memory/topics", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/memory/topics/**", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/memory/topics/**", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/memory/documents", TenantRole.VIEWER, false);
        rbac.addRule("DELETE", "/api/v1/memory/documents/**", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/memory/export", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/memory/consolidate", TenantRole.VIEWER, false);

        // 数据源（对照 RegisterDataSourceRoutes，routes_infra.go:292-333）
        // 读端（types / 列表 / 详情 / 同步日志）Viewer+，其余（CRUD、校验、资源枚举、
        // 同步控制、凭据子资源）Admin+。数据源持有外部服务凭据、并能触发改动整个
        // 知识库内容的同步任务，所以写端门槛取 Admin。
        // 两段路径的规则先登记（Ant 的 `*` 不跨 `/`，够不到 `/x/sync` 这类两段路径）。
        rbac.addRule("GET", "/api/v1/datasource/types", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/datasource/validate-credentials", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource/logs/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/datasource/*/credentials", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/datasource/*/credentials/*", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/validate", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource/*/resources", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/resource-ancestors", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/sync", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/pause", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/datasource/*/resume", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource/*/logs", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/datasource", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/datasource/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/datasource/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/datasource/*", TenantRole.VIEWER, false);

        // 基础设施配置三组（波 2 第五批，对照 routes_infra.go L205-284）：
        // 租户级基础设施，读 Viewer+、写与连通测试 Admin+（无 ownership 守卫）。
        // 静态段（types/test/*/credentials/*/default）先于 /x/* 通配登记（首个命中生效）。
        rbac.addRule("GET", "/api/v1/web-search-providers/types", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/web-search-providers/test", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/web-search-providers", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/web-search-providers", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/web-search-providers/*/credentials", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/web-search-providers/*/credentials/*", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/web-search-providers/*/test", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/web-search-providers/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/web-search-providers/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/web-search-providers/*", TenantRole.VIEWER, false);
        // 旧版运行时 provider 列表（Viewer+）；原始 group 注册 → API Key default-deny
        rbac.addRule("GET", "/api/v1/web-search/providers", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/vector-stores/types", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/vector-stores/test", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/vector-stores", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/vector-stores", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/vector-stores/*/test", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/vector-stores/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/vector-stores/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/vector-stores/*", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/storage-backends/types", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/storage-backends/test", TenantRole.ADMIN, false);
        rbac.addRule("POST", "/api/v1/storage-backends", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/storage-backends", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/storage-backends/*/test", TenantRole.ADMIN, false);
        // /default 是两段路径，必须先于 /storage-backends/* 登记（Ant 的 * 不跨 /）
        rbac.addRule("PUT", "/api/v1/storage-backends/*/default", TenantRole.ADMIN, false);
        rbac.addRule("PUT", "/api/v1/storage-backends/*", TenantRole.ADMIN, false);
        rbac.addRule("DELETE", "/api/v1/storage-backends/*", TenantRole.ADMIN, false);
        rbac.addRule("GET", "/api/v1/storage-backends/*", TenantRole.VIEWER, false);

        // 跨空间租户目录（波 2 扫尾批 3，对照 routes_auth_tenant.go L53-61）：
        // g.CrossTenant() 守卫（flag + CanAccessAllTenants，不受 EnableRBAC 调制）。
        // POST /tenants 不登记规则——Go 该路由只有 Auth（自助创建对普通用户开放）。
        rbac.addCrossTenantRule("GET", "/api/v1/tenants/all");
        rbac.addCrossTenantRule("GET", "/api/v1/tenants/search");
        // 租户 KV 配置分发器（对照 L75-76）：GET Viewer+、PUT Admin+；
        // 三条敏感 key 的 admin 门在控制器内（CanViewIntegrationSecrets）。
        rbac.addRule("GET", "/api/v1/tenants/kv/*", TenantRole.VIEWER, false);
        rbac.addRule("PUT", "/api/v1/tenants/kv/*", TenantRole.ADMIN, false);

        // 租户 API Key 管理（对照 routes_auth_tenant.go）：Owner+。
        // 刻意**不**登记进 API-Key 策略表——Key 不能给自己扩权（Go 测试钉住的契约）。
        rbac.addRule("GET", "/api/v1/tenants/*/api-keys", TenantRole.ADMIN, true);
        rbac.addRule("POST", "/api/v1/tenants/*/api-keys", TenantRole.ADMIN, true);
        rbac.addRule("PUT", "/api/v1/tenants/*/api-keys/*", TenantRole.ADMIN, true);
        rbac.addRule("DELETE", "/api/v1/tenants/*/api-keys/*", TenantRole.ADMIN, true);

        // 空间成员 / 邀请 / API-Principal（波 2 第六批，对照 routes_auth_tenant.go:88-137）：
        // 列表=Viewer+（任意成员可看名册）；一切变更=Owner+（Go 的 g.Owner()，**不是**
        // ADMIN 下限——成员/角色是租户内最高影响操作）；/leave=Viewer+（成员可自助退出）。
        // PathTenantMatch 对 /api/v1/tenants/{id}/** 自动生效（RbacInterceptor 内建）。
        // /api/v1/me/invitations** 在 Go 里无角色门（只挂 Auth）→ 不登记规则、
        // 拦截器 pattern 也未覆盖 /me/**。
        rbac.addRule("GET", "/api/v1/tenants/*/members", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/members", TenantRole.OWNER, false);
        rbac.addRule("PUT", "/api/v1/tenants/*/members/*", TenantRole.OWNER, false);
        rbac.addRule("DELETE", "/api/v1/tenants/*/members/*", TenantRole.OWNER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/leave", TenantRole.VIEWER, false);
        rbac.addRule("GET", "/api/v1/tenants/*/invitations", TenantRole.VIEWER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/invitations", TenantRole.OWNER, false);
        rbac.addRule("DELETE", "/api/v1/tenants/*/invitations/*", TenantRole.OWNER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/invite-links", TenantRole.OWNER, false);
        // api-principal 三条（Go g.Owner()）：配置与测试签发都是 Owner 面
        rbac.addRule("GET", "/api/v1/tenants/*/api-principal-config", TenantRole.OWNER, false);
        rbac.addRule("PUT", "/api/v1/tenants/*/api-principal-config", TenantRole.OWNER, false);
        rbac.addRule("POST", "/api/v1/tenants/*/api-principal-test-token", TenantRole.OWNER, false);

        registry.addInterceptor(rbac).addPathPatterns("/api/v1/sessions/**",
                "/api/v1/models/**",
                "/api/v1/weknoracloud/credentials", "/api/v1/knowledge-bases/**", "/api/v1/knowledge/**",
                "/api/v1/mcp-services/**", "/api/v1/agent/**", "/api/v1/knowledgebase/**",
                "/api/v1/tenants/**", "/api/v1/system/**", "/api/v1/memory/**",
                "/api/v1/datasource/**",
                "/api/v1/web-search-providers/**", "/api/v1/web-search/**",
                "/api/v1/vector-stores/**", "/api/v1/storage-backends/**",
                "/api/v1/evaluation/**");
    }

    /**
     * GET /system/capabilities 的启动快照（对照 Go router.NewRouter 尾部的
     * BindDeploymentCapabilities(deploymentCapabilitiesFromRouter(params))）。
     * Java 侧以"模块是否已翻译注册"等价 Go 的"handler 是否被注入"（当前部署状态：
     * organizations/agents/im/embed/sandbox 未注册 → route_not_registered，随波 3/4/5/7
     * 推进在各自批次翻真——这是**部署状态**而非代码契约，A/B 按部署各自断言）。
     */
    @org.springframework.context.annotation.Bean
    public com.ragagent.system.service.DeploymentCapabilitiesHolder deploymentCapabilitiesHolder(
            com.ragagent.apikey.service.TenantAPIKeyService apiKeyService) {
        var holder = new com.ragagent.system.service.DeploymentCapabilitiesHolder();
        holder.bind(
                /* organizations */ false,
                /* agents */ false,
                /* im */ false,
                /* embed */ false,
                /* api */ apiKeyService != null,
                /* mcp */ true,
                /* webSearch */ true,
                /* vectorStore */ true,
                /* storage */ true,
                /* sandbox */ false);
        return holder;
    }
}
