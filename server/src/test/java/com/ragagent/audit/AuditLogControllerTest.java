package com.ragagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.audit.controller.AuditLogController;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditLogQuery;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.GlobalExceptionHandler;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 审计端点契约测试——对照 Go internal/handler/audit_log_test.go（L46-410）。
 *
 * <p>用 standalone MockMvc（等价 Go 测试里只挂 handler 的 {@code gin.New()}）+
 * 生产 {@link GlobalExceptionHandler}，所以错误信封的形态、状态码与键序都是真的。
 * 服务层用 Mockito 桩，等价 Go 的 {@code stubAuditService}。
 * 租户/角色上下文由测试手工写入 {@link TenantContext}（等价 Go 测试中间件里
 * 往 gin.Context 塞 TenantID/KBAccess/UserID/TenantRole 的那段）。</p>
 *
 * <p><b>三条断言直接抄自对运行中 Go dev server 的实测</b>（2026-09-18）：</p>
 * <ul>
 *   <li>空页响应体是 {@code {"success":true,"data":[],"next_cursor":0}}——
 *       Go 的 {@code []*types.AuditLog} 经 GORM {@code Find} 后是<b>非 nil 空切片</b>，
 *       序列化成 {@code []} 而不是 {@code null}（与 Wiki 的 nil slice 不同）；</li>
 *   <li>非法租户 ID 是 400 {@code {"error":{"code":1010,...}},"success":false}；</li>
 *   <li>非创建者读他人 KB 活动流是 <b>403 守卫形态</b>（纯字符串），
 *       因为线上是 {@code g.OwnedKBOrAdmin()} 中间件先拒。</li>
 * </ul>
 */
class AuditLogControllerTest {

    private AuditLogService svc;
    private KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
    private MockMvc mvc;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 建一套 harness：服务桩 + 可选 KB 行 + standalone MockMvc。 */
    private void harness(ListFn listFn) {
        svc = mock(AuditLogService.class);
        when(svc.list(anyLong(), any(AuditLogQuery.class)))
                .thenAnswer(inv -> listFn.apply(inv.getArgument(0), inv.getArgument(1)));
        mvc = MockMvcBuilders
                .standaloneSetup(new AuditLogController(svc, kbMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 服务桩在不应被调用时的守卫版本。 */
    private void harnessForbidden() {
        harness((tenantId, q) -> {
            throw new AssertionError("audit list must not be called on this path");
        });
    }

    private void seedKb(String kbId, long tenantId, String creatorId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setTenantId(tenantId);
        kb.setCreatorId(creatorId);
        when(kbMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(kb);
    }

    private void seedNoKb() {
        when(kbMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    }

    private void context(Long tenantId, String userId, String role) {
        TenantContext.set(tenantId, TenantContext.webUserPrincipal(userId), role, false, userId, false);
    }

    private static AuditLog entry(long id, long tenantId, String action, String outcome) {
        AuditLog e = new AuditLog();
        e.setId(id);
        e.setTenantId(tenantId);
        e.setAction(action);
        e.setOutcome(outcome);
        return e;
    }

    private String perform(String url, String... params) throws Exception {
        var req = get(url);
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ── GET /tenants/{id}/audit-log ──────────────────────────────────────

    /** 对照 {@code TestAuditLogHandler_ReturnsEnvelopeAndCursor}。 */
    @Test
    void tenantListReturnsEnvelopeAndCursor() throws Exception {
        harness((tenantId, q) -> {
            assertThat(tenantId).isEqualTo(7L);
            return List.of(
                    entry(102, 7, AuditAction.MEMBER_ADDED, AuditOutcome.SUCCESS),
                    entry(95, 7, AuditAction.ACCESS_DENIED, AuditOutcome.DENIED));
        });

        String body = perform("/api/v1/tenants/7/audit-log");

        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("\"next_cursor\":95");   // 本页最小 id
        // 键序对照 Go 的 struct 声明序：success → data → next_cursor
        assertThat(body).startsWith("{\"success\":true,\"data\":[");
        assertThat(body).endsWith(",\"next_cursor\":95}");
    }

    /** 对照 {@code TestAuditLogHandler_PassesQueryFiltersThrough}：五个参数逐一直传。 */
    @Test
    void tenantListPassesQueryFiltersThrough() throws Exception {
        AtomicReference<AuditLogQuery> seen = new AtomicReference<>();
        harness((tenantId, q) -> {
            seen.set(q);
            return List.of();
        });

        perform("/api/v1/tenants/7/audit-log",
                "after_id", "100", "limit", "25",
                "action", "rbac.access_denied", "outcome", "denied", "actor", "u-probing");

        AuditLogQuery q = seen.get();
        assertThat(q.afterId()).isEqualTo(100L);
        assertThat(q.limit()).isEqualTo(25);
        assertThat(q.action()).isEqualTo("rbac.access_denied");
        assertThat(q.outcome()).isEqualTo("denied");
        assertThat(q.actorUserId()).isEqualTo("u-probing");
        assertThat(q.unscopedOnly()).isTrue();   // 租户流必须排除资源作用域行
        assertThat(q.scopeType()).isEmpty();
        assertThat(q.scopeId()).isEmpty();
    }

    /**
     * 对照 {@code TestAuditLogHandler_EmptyResultProducesZeroCursor}：
     * {@code next_cursor=0} 是文档化的"没有更老的行"信号——前端据此停止翻页。
     * 响应体逐字节对照实测的 Go 输出（<b>{@code []} 不是 {@code null}</b>）。
     */
    @Test
    void tenantListEmptyResultProducesZeroCursor() throws Exception {
        harness((tenantId, q) -> List.of());

        mvc.perform(get("/api/v1/tenants/7/audit-log"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"success\":true,\"data\":[],\"next_cursor\":0}"));
    }

    /**
     * 对照 {@code TestAuditLogHandler_InvalidTenantIDReturns400}：
     * 非数字租户 ID 在碰到服务前就被 400 挡下。响应体逐字节对照实测的 Go 输出。
     */
    @Test
    void tenantListInvalidTenantIdReturns400() throws Exception {
        harnessForbidden();

        mvc.perform(get("/api/v1/tenants/not-a-number/audit-log"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"error\":{\"code\":1010,\"details\":null,"
                        + "\"message\":\"workspace id must be a positive integer\"},\"success\":false}"));

        // 0 也不是合法空间 ID（对照 Go 的 `err != nil || v == 0`）
        mvc.perform(get("/api/v1/tenants/0/audit-log"))
                .andExpect(status().isBadRequest());
    }

    /** 对照 Go 的容错解析：垃圾 after_id / 非正 limit 一律塌成默认值，不 400。 */
    @Test
    void tenantListToleratesGarbageCursor() throws Exception {
        AtomicReference<AuditLogQuery> seen = new AtomicReference<>();
        harness((tenantId, q) -> {
            seen.set(q);
            return List.of();
        });

        mvc.perform(get("/api/v1/tenants/7/audit-log")
                        .param("after_id", "abc")
                        .param("limit", "-1"))
                .andExpect(status().isOk());

        assertThat(seen.get().afterId()).isZero();
        assertThat(seen.get().limit()).isZero();
    }

    /** 负数 after_id 也归 0（Go 用 ParseUint，负号即解析失败）。 */
    @Test
    void tenantListToleratesNegativeCursor() throws Exception {
        AtomicReference<AuditLogQuery> seen = new AtomicReference<>();
        harness((tenantId, q) -> {
            seen.set(q);
            return List.of();
        });

        perform("/api/v1/tenants/7/audit-log", "after_id", "-5");
        assertThat(seen.get().afterId()).isZero();
    }

    /** 服务层异常 → 500，且原始消息进响应体（前端抽屉里逐字展示）。 */
    @Test
    void tenantListServiceErrorReturns500() throws Exception {
        harness((tenantId, q) -> {
            throw new IllegalStateException("db: connection refused");
        });

        String body = mvc.perform(get("/api/v1/tenants/7/audit-log"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("db: connection refused");
        assertThat(body).contains("\"success\":false");
    }

    // ── GET /knowledge-bases/{id}/activity ───────────────────────────────

    /** 对照 {@code TestKnowledgeBaseActivityHandler_UsesKBScope}。 */
    @Test
    void kbActivityUsesKbScope() throws Exception {
        AtomicReference<AuditLogQuery> seen = new AtomicReference<>();
        harness((tenantId, q) -> {
            assertThat(tenantId).isEqualTo(7L);
            seen.set(q);
            return List.of(entry(21, 7, AuditAction.KB_UPDATED, AuditOutcome.SUCCESS));
        });
        seedKb("kb-1", 7, "creator");
        context(7L, "creator", "viewer");

        String body = perform("/api/v1/knowledge-bases/kb-1/activity",
                "after_id", "30", "limit", "10", "outcome", "partial");

        AuditLogQuery q = seen.get();
        assertThat(q.scopeType()).isEqualTo("knowledge_base");
        assertThat(q.scopeId()).isEqualTo("kb-1");
        assertThat(q.afterId()).isEqualTo(30L);
        assertThat(q.limit()).isEqualTo(10);
        assertThat(q.outcome()).isEqualTo("partial");
        assertThat(q.unscopedOnly()).isFalse();
        assertThat(body).endsWith(",\"next_cursor\":21}");
    }

    /**
     * 对照 {@code TestKnowledgeBaseActivityHandler_BlocksSharedWorkspace}：
     * 调用方租户 ≠ KB 归属租户 → 403（Go handler 内的 AppError 信封形态）。
     */
    @Test
    void kbActivityBlocksSharedWorkspace() throws Exception {
        harnessForbidden();
        seedKb("kb-1", 7, "creator");
        context(8L, "creator", "owner");

        mvc.perform(get("/api/v1/knowledge-bases/kb-1/activity"))
                .andExpect(status().isForbidden())
                .andExpect(content().string("{\"error\":{\"code\":1002,\"details\":null,"
                        + "\"message\":\"knowledge base activity is only available in the owner workspace\"},"
                        + "\"success\":false}"));
    }

    /**
     * 对照 {@code TestKnowledgeBaseActivityHandler_RequiresCreatorOrAdmin}：
     * 既不是创建者、角色又低于 Admin → 403。
     *
     * <p><b>形态取自实测</b>：线上先由 {@code g.OwnedKBOrAdmin()} 中间件拒绝，
     * 所以响应体是<b>守卫形态的纯字符串</b>，而不是 Go handler 里那条
     * 不可达的 AppError 信封。</p>
     */
    @Test
    void kbActivityRequiresCreatorOrAdmin() throws Exception {
        harnessForbidden();
        seedKb("kb-1", 7, "creator");
        context(7L, "other", "contributor");

        mvc.perform(get("/api/v1/knowledge-bases/kb-1/activity"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(
                        "{\"error\":\"Forbidden: must own the resource or have the required role\"}"));
    }

    /** Admin（非创建者）可以读——对照 Go {@code role.HasPermission(TenantRoleAdmin)}。 */
    @Test
    void kbActivityAllowsAdminNonCreator() throws Exception {
        harness((tenantId, q) -> List.of());
        seedKb("kb-1", 7, "creator");
        context(7L, "other", "admin");

        mvc.perform(get("/api/v1/knowledge-bases/kb-1/activity"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"success\":true,\"data\":[],\"next_cursor\":0}"));
    }

    /** KB 不存在 → 404 AppError 信封（实测 Go：{@code {"error":{"code":1003,...}}}）。 */
    @Test
    void kbActivityMissingKbReturns404() throws Exception {
        harnessForbidden();
        seedNoKb();
        context(7L, "creator", "owner");

        mvc.perform(get("/api/v1/knowledge-bases/no-such-kb/activity"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("{\"error\":{\"code\":1003,\"details\":null,"
                        + "\"message\":\"knowledge base not found\"},\"success\":false}"));
    }

    /** 未附加角色时回落 Viewer（对照 Go {@code TenantRoleFromContext} 的 fail-closed 默认）。 */
    @Test
    void kbActivityFailsClosedWhenRoleMissing() throws Exception {
        harnessForbidden();
        seedKb("kb-1", 7, "creator");
        context(7L, "other", null);

        mvc.perform(get("/api/v1/knowledge-bases/kb-1/activity"))
                .andExpect(status().isForbidden());
    }

    // ── GET /system/admin/audit-log ──────────────────────────────────────

    /** 对照 {@code TestSystemAuditLogHandler_AlwaysQueriesTenantZero}：硬钉 tenant_id=0。 */
    @Test
    void systemAuditAlwaysQueriesTenantZero() throws Exception {
        AtomicReference<Long> seenTenant = new AtomicReference<>();
        harness((tenantId, q) -> {
            seenTenant.set(tenantId);
            return List.of(
                    entry(50, 0, AuditAction.SYSTEM_SETTING_CHANGED, AuditOutcome.SUCCESS),
                    entry(42, 0, AuditAction.SYSTEM_ADMIN_PROMOTED, AuditOutcome.SUCCESS));
        });

        String body = perform("/api/v1/system/admin/audit-log");

        assertThat(seenTenant.get()).isZero();
        assertThat(body).startsWith("{\"success\":true,\"data\":[");
        assertThat(body).endsWith(",\"next_cursor\":42}");
    }

    /** 对照 {@code TestSystemAuditLogHandler_PassesQueryFiltersThrough}。 */
    @Test
    void systemAuditPassesQueryFiltersThrough() throws Exception {
        AtomicReference<AuditLogQuery> seen = new AtomicReference<>();
        harness((tenantId, q) -> {
            assertThat(tenantId).isZero();
            seen.set(q);
            return List.of();
        });

        perform("/api/v1/system/admin/audit-log",
                "after_id", "200", "limit", "10",
                "action", "system.setting_changed", "outcome", "success", "actor", "u-admin-1");

        AuditLogQuery q = seen.get();
        assertThat(q.afterId()).isEqualTo(200L);
        assertThat(q.limit()).isEqualTo(10);
        assertThat(q.action()).isEqualTo("system.setting_changed");
        assertThat(q.outcome()).isEqualTo("success");
        assertThat(q.actorUserId()).isEqualTo("u-admin-1");
        // 平台流<b>不</b>加 UnscopedOnly（与租户流不同，对照 Go 的查询构造）
        assertThat(q.unscopedOnly()).isFalse();
    }

    /** 对照 {@code TestSystemAuditLogHandler_EmptyResultProducesZeroCursor}。 */
    @Test
    void systemAuditEmptyResultProducesZeroCursor() throws Exception {
        harness((tenantId, q) -> List.of());

        mvc.perform(get("/api/v1/system/admin/audit-log").param("after_id", "10"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"success\":true,\"data\":[],\"next_cursor\":0}"));
    }

    /** 对照 {@code TestSystemAuditLogHandler_GarbageCursorAndLimitTolerated}。 */
    @Test
    void systemAuditToleratesGarbageCursorAndLimit() throws Exception {
        AtomicReference<AuditLogQuery> seen = new AtomicReference<>();
        harness((tenantId, q) -> {
            seen.set(q);
            return List.of();
        });

        mvc.perform(get("/api/v1/system/admin/audit-log")
                        .param("after_id", "abc")
                        .param("limit", "-1"))
                .andExpect(status().isOk());

        assertThat(seen.get().afterId()).isZero();
        assertThat(seen.get().limit()).isZero();
    }

    /** 对照 {@code TestSystemAuditLogHandler_ServiceErrorReturns500}。 */
    @Test
    void systemAuditServiceErrorReturns500() throws Exception {
        harness((tenantId, q) -> {
            throw new IllegalStateException("db: connection refused");
        });

        String body = mvc.perform(get("/api/v1/system/admin/audit-log"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isNotEmpty();
        assertThat(body).contains("db: connection refused");
    }

    // ── 桩 ───────────────────────────────────────────────────────────────

    /** 对照 Go 的 {@code stubAuditService}：只实现 List。 */
    @FunctionalInterface
    private interface ListFn {
        List<AuditLog> apply(long tenantId, AuditLogQuery q);
    }
}
