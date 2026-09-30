package com.ragagent.audit.controller;

import java.util.List;

import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditLogQuery;
import com.ragagent.audit.dto.AuditLogListResponse;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.GuardForbiddenException;
import com.ragagent.common.knowledge.KnowledgeBaseFacts;
import com.ragagent.common.knowledge.KnowledgeBaseGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志端点（对照 Go internal/handler/audit_log.go 整个文件）。
 *
 * <h2>三个端点与 Go 的路由矩阵</h2>
 * <ul>
 *   <li>{@code GET /api/v1/tenants/{id}/audit-log} — 空间审计流。
 *       Go 挂在 {@code tenantByID} 组下，守卫 {@code PathTenantMatch} + {@code g.Admin()}
 *       （routes_auth_tenant.go L143-146）。<b>Admin+ 是刻意的</b>：拒绝历史不该让普通成员看到。</li>
 *   <li>{@code GET /api/v1/knowledge-bases/{id}/activity} — 单个知识库的活动投影。
 *       Go 守卫 {@code OwnedKBOrAdmin} + {@code KBAccessRead("id")}，
 *       <b>刻意只对 JWT 开放</b>（没有任何工作区 API-Key 能力授予审计读取，
 *       routes_knowledge.go L249-257）。</li>
 *   <li>{@code GET /api/v1/system/admin/audit-log} — 平台流（{@code tenant_id=0} 的行）。
 *       Go 挂在 {@code /system/admin} 组，组级守卫 {@code SystemAdmin()}，
 *       外加 API-Key 能力 {@code system_audit_read}（routes_auth_tenant.go L341-343）。
 *       这条路由<b>不是</b>租户作用域的——system_settings 变更、管理员升/降级、
 *       配额批量写都写 tenant_id=0 的审计行，租户流永远看不到它们。</li>
 * </ul>
 *
 * <h2>响应形态</h2>
 * <p>三个端点都是 {@link AuditLogListResponse}：{@code {"success":true,"data":[...],"next_cursor":N}}
 * ——<b>不带</b> {@code {"data":...,"success":true}} 那种 R 信封。错误则走
 * {@code GlobalExceptionHandler} 的 AppError 信封（租户 ID 非法 400、KB 权限 403、查询失败 500）。</p>
 *
 * <h2>分页语义</h2>
 * <p>游标是单调递增的 id（不是 created_at——重复时间戳不会打断翻页）：
 * 返回 id &lt; after_id 的行、最新在前。{@code next_cursor} 是本页最小 id，
 * 空页为 0，前端见到 0 就停止翻页。</p>
 */
@RestController
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final KnowledgeBaseGateway knowledgeBaseGateway;

    public AuditLogController(AuditLogService auditLogService, KnowledgeBaseGateway knowledgeBaseGateway) {
        this.auditLogService = auditLogService;
        this.knowledgeBaseGateway = knowledgeBaseGateway;
    }

    // ── 空间审计流 ───────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ListTenantAuditLog}（L53-107）。
     *
     * <p>游标与页大小的解析是<b>容错</b>的：非法值一律当成"从最新开始"，
     * 免得配错的客户端在空请求/首页请求上直接吃 400。更严的校验属于前端。</p>
     *
     * <p>{@code UnscopedOnly=true}：租户流<b>只</b>看无作用域的行
     * （{@code scope_type = ''}）——知识库活动那些 {@code scope_type='knowledge_base'}
     * 的行属于各自的 KB 流，不该混进空间级 feed。</p>
     */
    @GetMapping("/api/v1/tenants/{id}/audit-log")
    public ResponseEntity<AuditLogListResponse> listTenantAuditLog(
            @PathVariable("id") String rawTenantId,
            @RequestParam(value = "after_id", required = false) String afterId,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam(value = "actor", required = false) String actor) {
        long tenantId = parseTenantIdFromPath(rawTenantId);

        long[] cursor = parseAuditCursor(afterId, limit);
        AuditLogQuery q = new AuditLogQuery(
                cursor[0], (int) cursor[1],
                action, outcome, actor,
                "", "", true);

        List<AuditLog> entries = listOrInternalError(tenantId, q);
        return ResponseEntity.ok(AuditLogListResponse.of(entries));
    }

    // ── 知识库活动流 ─────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ListKnowledgeBaseActivity}（L127-170）：单个知识库的 durable 活动投影。
     *
     * <p>路由侧已经解析过 KB 访问权；本方法补一道<b>归属空间</b>检查，
     * 让组织级共享的消费方无法窥探源工作空间的 actor 与配置历史。</p>
     *
     * <h2>三层判定（顺序即 Go 的实际生效顺序）</h2>
     * <ol>
     *   <li><b>KB 不存在</b> → 404 AppError 信封 {@code knowledge base not found}
     *       （对照 Go handler L131-134）。</li>
     *   <li><b>调用方租户 ≠ KB 归属租户</b> → 403 AppError 信封
     *       {@code knowledge base activity is only available in the owner workspace}
     *       （对照 Go handler L135-139）。</li>
     *   <li><b>既不是创建者、角色又低于 Admin</b> → 403 <b>路由守卫形态</b>
     *       {@code {"error":"Forbidden: must own the resource or have the required role"}}
     *       ——这一条对应 Go 中间件链里的 {@code g.OwnedKBOrAdmin()}，它<b>先于</b>
     *       handler 执行，所以线上看到的是守卫形态。（{@code 2026-09-18} 对运行中的
     *       Go dev server 实测确认：viewer 读他人 KB 的活动流返回的正是这条纯字符串。）
     *       <br>Go handler 内部另有一条<b>不可达</b>的同义检查（L142-145，文案
     *       {@code knowledge base activity requires creator or admin access}，
     *       AppError 信封）——中间件已先拒，这里不再复刻死代码。</li>
     * </ol>
     */
    @GetMapping("/api/v1/knowledge-bases/{id}/activity")
    public ResponseEntity<AuditLogListResponse> listKnowledgeBaseActivity(
            @PathVariable("id") String kbId,
            @RequestParam(value = "after_id", required = false) String afterId,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam(value = "actor", required = false) String actor) {

        // 对照中间件 KBAccessRead 解析出的 access.KnowledgeBase（本租户 + 未删除）。
        // ⚠️ 共享空间（kb_shares）尚未翻译 → 跨租户的 KB 在这里直接落 404，
        // 与 Go 在共享场景下的 403 存在差异（见报告）。
        KnowledgeBaseFacts kb = knowledgeBaseGateway.findFacts(kbId);
        if (kb == null) {
            throw new BizException(AppError.notFound("knowledge base not found"));
        }

        Long callerTenantId = TenantContext.currentTenantId();
        long caller = callerTenantId == null ? 0L : callerTenantId;
        long kbTenant = kb.tenantId() == null ? 0L : kb.tenantId();
        if (caller == 0 || kbTenant != caller) {
            throw new BizException(AppError.forbidden(
                    "knowledge base activity is only available in the owner workspace"));
        }

        String actorId = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        TenantRole role = currentRoleOrViewer();
        String creatorId = kb.creatorId() == null ? "" : kb.creatorId();
        if (!creatorId.equals(actorId) && !role.hasPermission(TenantRole.ADMIN)) {
            // 对照 Go 中间件 g.OwnedKBOrAdmin() 的拒绝：**守卫形态**，
            // 不是 AppError 信封（见方法注释第 3 条）。
            throw GuardForbiddenException.mustOwnResourceOrHaveRole();
        }

        long[] cursor = parseAuditCursor(afterId, limit);
        AuditLogQuery q = new AuditLogQuery(
                cursor[0], (int) cursor[1],
                action, outcome, actor,
                "knowledge_base", kbId, false);

        List<AuditLog> entries = listOrInternalError(kbTenant, q);
        return ResponseEntity.ok(AuditLogListResponse.of(entries));
    }

    // ── 平台审计流 ───────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ListSystemAuditLog}（L211-257）。
     *
     * <p>游标/页大小解析与租户版一致，让前端共用同一套调用形状；对垃圾输入同样容错，
     * 因为空请求/首次请求不该被弹回。</p>
     *
     * <p><b>tenant_id=0 是系统作用域约定</b>——无论 URL、上下文还是请求头里有什么，
     * 都必须硬钉到 0。从 URL/上下文读 tenant_id 的回归会把各租户的 rbac.* 行泄进平台流
     * （反过来也会让 SystemAdmin 看不到平台自己的 system.* 行）。</p>
     */
    @GetMapping("/api/v1/system/admin/audit-log")
    public ResponseEntity<AuditLogListResponse> listSystemAuditLog(
            @RequestParam(value = "after_id", required = false) String afterId,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam(value = "actor", required = false) String actor) {
        long[] cursor = parseAuditCursor(afterId, limit);
        AuditLogQuery q = new AuditLogQuery(
                cursor[0], (int) cursor[1],
                action, outcome, actor,
                "", "", false);

        List<AuditLog> entries = listOrInternalError(0L, q);
        return ResponseEntity.ok(AuditLogListResponse.of(entries));
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────

    /**
     * 对照 Go 三个 handler 里统一的错误分支：
     * {@code logger.Error(...); c.Error(errors.NewInternalServerError(err.Error()))}。
     *
     * <p>要点是<b>把原始错误消息带进响应体</b>（前端在抽屉的 error alert 里逐字显示它）；
     * 若不在这里转换，异常会落到 {@code GlobalExceptionHandler.handleOther}，
     * 消息被换成固定的 {@code "Internal server error"}，与 Go 不一致。</p>
     */
    private List<AuditLog> listOrInternalError(long tenantId, AuditLogQuery q) {
        try {
            return auditLogService.list(tenantId, q);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(e.getMessage()));
        }
    }

    /**
     * 对照 Go {@code parseTenantIDFromPath}（handler/tenant_member.go L74-86）：
     * 空 → {@code workspace id is required}；非数字或 0 → {@code workspace id must be a
     * positive integer}。两者都是 400 的 AppError 校验错误。
     */
    private static long parseTenantIdFromPath(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(AppError.validation("workspace id is required"));
        }
        long v;
        try {
            v = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new BizException(AppError.validation("workspace id must be a positive integer"));
        }
        if (v <= 0) {
            throw new BizException(AppError.validation("workspace id must be a positive integer"));
        }
        return v;
    }

    /**
     * 对照 Go {@code parseAuditCursor}（L172-186）：{@code after_id} 解析失败一律归 0；
     * {@code limit} 解析失败或非正一律归 0（"0 让仓储用它自己的默认值 50"）。
     *
     * <p>注意 Go 用的是 {@code strconv.ParseUint}——负数与溢出都算解析失败，所以
     * {@code after_id=-1} 也归 0（Java 侧必须显式挡掉负值）。</p>
     *
     * @return 长度为 2 的数组 {afterId, limit}
     */
    private static long[] parseAuditCursor(String rawAfterId, String rawLimit) {
        long afterId = 0L;
        if (rawAfterId != null && !rawAfterId.isEmpty()) {
            try {
                long v = Long.parseLong(rawAfterId);
                if (v >= 0) {
                    afterId = v;
                }
            } catch (NumberFormatException ignored) {
                // 容错：垃圾游标 = 从最新开始
            }
        }
        int limit = 0;
        if (rawLimit != null && !rawLimit.isEmpty()) {
            try {
                int v = Integer.parseInt(rawLimit);
                if (v > 0) {
                    limit = v;
                }
            } catch (NumberFormatException ignored) {
                // 容错：垃圾 limit = 用仓储默认值
            }
        }
        return new long[] {afterId, limit};
    }

    /**
     * 对照 Go {@code types.TenantRoleFromContext}（context_helpers.go L136-142）：
     * 未附加或非法时<b>回落 Viewer</b>（fail-closed）。
     *
     * <p>⚠️ 与 {@code RbacInterceptor} 里的 {@code TenantRole.fromString} 写法有意不同：
     * 那边不回落到 Viewer（UNKNOWN 的 level 0 对任何角色下限都更严）。本控制器按 Go 的
     * 上下文语义取 Viewer，才能在"创建者本人但未附加角色"这类路径上与 Go 一致。</p>
     */
    private static TenantRole currentRoleOrViewer() {
        TenantRole r = TenantRole.fromString(TenantContext.currentRole());
        return r.isValid() ? r : TenantRole.VIEWER;
    }
}
