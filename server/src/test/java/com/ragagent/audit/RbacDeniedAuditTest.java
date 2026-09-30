package com.ragagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.mapper.AuditLogRepository;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.audit.service.RbacDeniedAuditorRegistrar;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.web.RbacInterceptor;
import com.ragagent.config.TenantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * RBAC 拒绝审计落库的回补测试（约定 §9「阶段 1 已知差异」第 8 条）。
 *
 * <p>Go 侧链路：{@code middleware/rbac.go} 拒绝分支 →
 * {@code AuditServiceFromContext(c)} → {@code svc.LogDenied(...)}。
 * Java 侧同一条链路：{@link RbacInterceptor} 拒绝分支 →
 * {@link RbacInterceptor#setDeniedAuditor} 注册的钩子 →
 * {@link AuditLogService#logDenied} → {@link AuditLogRepository} 落库。</p>
 *
 * <p>断言四件事：</p>
 * <ol>
 *   <li>拒绝时<b>确实</b>调了 LogDenied，且 request_path 用<b>路由模板</b>
 *       （对照实测 Go 的 {@code /api/v1/tenants/:id/audit-log}）；</li>
 *   <li>放行路径（角色达标 / EnableRBAC=false / API Key 主体短路）<b>不</b>落审计行；</li>
 *   <li>审计本身失败时 403 仍然照发（Go 的 {@code _ = svc.LogDenied(...)}）；</li>
 *   <li>未注册实现 bean 时退化为空操作（Go 的 {@code if svc := ...; svc != nil}）。</li>
 * </ol>
 */
class RbacDeniedAuditTest {

    /** 对照 Go TenantConfig 的开关构造（跨空间访问与自助创建本测试不用）。 */
    private static TenantProperties props(Boolean enableRbac) {
        return new TenantProperties(enableRbac, false, null);
    }

    @AfterEach
    void cleanup() {
        RbacInterceptor.setDeniedAuditor(null);
        APIKeyScopeContext.clear();
        TenantContext.clear();
    }

    /** 与生产同构的装配：registrar 把服务接进拦截器的静态钩子。 */
    private static void installRegistrar(AuditLogRepository repo) {
        new RbacDeniedAuditorRegistrar(new AuditLogService(repo)).afterPropertiesSet();
    }

    /** 桩：把落库行收进内存列表，并让去重探测恒为"窗口内没有"。 */
    private static List<AuditLog> captureCreatedRows(AuditLogRepository repo) {
        List<AuditLog> written = new ArrayList<>();
        doAnswer(inv -> {
            written.add(inv.getArgument(0));
            return null;
        }).when(repo).create(any(AuditLog.class));
        when(repo.countSinceForDedup(anyLong(), anyString(), anyString(), anyString(),
                any(OffsetDateTime.class))).thenReturn(0L);
        return written;
    }

    private static MockHttpServletRequest request(String method, String uri, String template) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (template != null) {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
        }
        return req;
    }

    @Test
    void deniedRequestWritesAuditRowWithRouteTemplate() throws Exception {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        List<AuditLog> written = captureCreatedRows(repo);
        installRegistrar(repo);

        RbacInterceptor rbac = new RbacInterceptor(props(true))
                .addRule("GET", "/api/v1/tenants/*/audit-log", TenantRole.ADMIN, false);
        TenantContext.set(10002L, TenantContext.webUserPrincipal("u-viewer"), "viewer",
                false, "u-viewer", false);

        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean allowed = rbac.preHandle(
                request("GET", "/api/v1/tenants/10002/audit-log", "/api/v1/tenants/{id}/audit-log"),
                res, new Object());

        assertThat(allowed).isFalse();
        // 403 形态不变（守卫纯字符串，与 golden 实测一致）
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString())
                .isEqualTo("{\"error\":\"Forbidden: insufficient workspace role\"}");

        assertThat(written).hasSize(1);
        AuditLog row = written.get(0);
        assertThat(row.getAction()).isEqualTo(AuditAction.ACCESS_DENIED);
        assertThat(row.getOutcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(row.getTenantId()).isEqualTo(10002L);
        assertThat(row.getActorUserId()).isEqualTo("u-viewer");
        assertThat(row.getActorRole()).isEqualTo("viewer");
        assertThat(row.getRequestMethod()).isEqualTo("GET");
        assertThat(row.getRequestPath()).isEqualTo("/api/v1/tenants/{id}/audit-log");
        assertThat(row.getDetails().get("required_role").asText()).isEqualTo("admin");
        assertThat(row.getDetails().get("raw_path").asText())
                .isEqualTo("/api/v1/tenants/10002/audit-log");
    }

    /** 没有 BEST_MATCHING_PATTERN 时回落到 RBAC 规则的 Ant 模式（仍不是原始 URI）。 */
    @Test
    void fallsBackToRulePatternWhenRouteTemplateMissing() throws Exception {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        List<AuditLog> written = captureCreatedRows(repo);
        installRegistrar(repo);

        RbacInterceptor rbac = new RbacInterceptor(props(true))
                .addRule("DELETE", "/api/v1/knowledge-bases/*", TenantRole.ADMIN, false);
        TenantContext.set(7L, TenantContext.webUserPrincipal("u"), "viewer", false, "u", false);

        rbac.preHandle(request("DELETE", "/api/v1/knowledge-bases/kb-1", null),
                new MockHttpServletResponse(), new Object());

        assertThat(written).hasSize(1);
        assertThat(written.get(0).getRequestPath()).isEqualTo("/api/v1/knowledge-bases/*");
    }

    /** 角色达标 → 放行，不落审计行。 */
    @Test
    void allowedRequestDoesNotWriteAuditRow() throws Exception {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        installRegistrar(repo);

        RbacInterceptor rbac = new RbacInterceptor(props(true))
                .addRule("GET", "/api/v1/tenants/*/audit-log", TenantRole.ADMIN, false);
        TenantContext.set(7L, TenantContext.webUserPrincipal("u-admin"), "admin", false, "u-admin", false);

        assertThat(rbac.preHandle(request("GET", "/api/v1/tenants/7/audit-log", null),
                new MockHttpServletResponse(), new Object())).isTrue();
        verify(repo, never()).create(any(AuditLog.class));
    }

    /** EnableRBAC=false 的滚动窗口：记日志放行，**不**落审计行（对照 Go 的分支顺序）。 */
    @Test
    void rbacDisabledLogsButDoesNotWriteAuditRow() throws Exception {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        installRegistrar(repo);

        RbacInterceptor rbac = new RbacInterceptor(props(false))
                .addRule("GET", "/api/v1/tenants/*/audit-log", TenantRole.ADMIN, false);
        TenantContext.set(7L, TenantContext.webUserPrincipal("u-viewer"), "viewer", false, "u-viewer", false);

        assertThat(rbac.preHandle(request("GET", "/api/v1/tenants/7/audit-log", null),
                new MockHttpServletResponse(), new Object())).isTrue();
        verify(repo, never()).create(any(AuditLog.class));
    }

    /**
     * API Key 主体短路（约定 §9 的 API Key 回补条目）：能力维度由 APIKeyGate 全权判定，
     * 角色维度直接放行，因此<b>不</b>会写 access_denied 行。
     */
    @Test
    void apiKeyPrincipalShortCircuitsWithoutAuditRow() throws Exception {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        installRegistrar(repo);

        RbacInterceptor rbac = new RbacInterceptor(props(true))
                .addRule("GET", "/api/v1/tenants/*/audit-log", TenantRole.ADMIN, false);
        TenantContext.set(7L, TenantContext.webUserPrincipal("u-viewer"), "viewer", false, "u-viewer", false);
        APIKeyScopeContext.set(TenantAPIKeyScope.empty());

        assertThat(rbac.preHandle(request("GET", "/api/v1/tenants/7/audit-log", null),
                new MockHttpServletResponse(), new Object())).isTrue();
        verify(repo, never()).create(any(AuditLog.class));
    }

    /** 审计落库失败**不能**把一次 403 变成 500（对照 Go 的 {@code _ = svc.LogDenied(...)}）。 */
    @Test
    void auditFailureDoesNotBreakTheRejection() throws Exception {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        doThrow(new IllegalStateException("db down")).when(repo).create(any(AuditLog.class));
        when(repo.countSinceForDedup(anyLong(), anyString(), anyString(), anyString(),
                any(OffsetDateTime.class))).thenReturn(0L);
        installRegistrar(repo);

        RbacInterceptor rbac = new RbacInterceptor(props(true))
                .addRule("GET", "/api/v1/tenants/*/audit-log", TenantRole.ADMIN, false);
        TenantContext.set(7L, TenantContext.webUserPrincipal("u-viewer"), "viewer", false, "u-viewer", false);

        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean allowed = rbac.preHandle(request("GET", "/api/v1/tenants/7/audit-log", null),
                res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString())
                .isEqualTo("{\"error\":\"Forbidden: insufficient workspace role\"}");
    }

    /** 未注册实现 bean 时退化为空操作（对照 Go 的 {@code if svc := ...; svc != nil}）。 */
    @Test
    void missingAuditorDegradesToNoOp() throws Exception {
        RbacInterceptor.setDeniedAuditor(null);

        RbacInterceptor rbac = new RbacInterceptor(props(true))
                .addRule("POST", "/api/v1/knowledge-bases", TenantRole.CONTRIBUTOR, false);
        TenantContext.set(7L, TenantContext.webUserPrincipal("u-viewer"), "viewer", false, "u-viewer", false);

        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(rbac.preHandle(request("POST", "/api/v1/knowledge-bases", null),
                res, new Object())).isFalse();
        assertThat(res.getContentAsString())
                .isEqualTo("{\"error\":\"Forbidden: insufficient workspace role\"}");
    }

    /**
     * 关停时 registrar 复位钩子——多 Spring 上下文并存的测试环境里不留陈旧引用；
     * 复位后钩子必须<b>可调用</b>（空操作），不能是 null。
     */
    @Test
    void registrarDestroyResetsHook() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        List<AuditLog> written = captureCreatedRows(repo);
        RbacDeniedAuditorRegistrar registrar = new RbacDeniedAuditorRegistrar(new AuditLogService(repo));

        registrar.afterPropertiesSet();
        registrar.destroy();

        // 复位后调用不炸、也不落库
        RbacInterceptor.deniedAuditor().logDenied(
                7L, "u", "viewer", "admin", "/api/v1/x", "GET", "/api/v1/x");
        assertThat(written).isEmpty();
    }
}
