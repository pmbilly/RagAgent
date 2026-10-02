package com.ragagent.auth.service;

import java.security.SecureRandom;
import com.ragagent.common.deployment.AppEnvLookup;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.auth.domain.TenantInvitation;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.auth.mapper.TenantInvitationMapper;
import com.ragagent.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对照 Go TenantInvitationService（internal/application/service/tenant_invitation.go）。
 *
 * <p>状态机（迁移 000048 注释原文）：pending → accepted | declined | revoked | expired，
 * 终态只进不出；terminal 行留作审计痕迹。所有 List/Accept/Decline/Count 读路径先跑
 * {@link #sweep}（惰性清扫过期 pending 行），清扫失败只记日志绝不阻塞收件箱。</p>
 *
 * <p><b>事务边界照抄 Go 的取舍</b>：Accept 的"邀请行翻转 + 成员行写入"**不在**
 * 同一个 DB 事务里——成员行的插入失败（唯一索引冲突=已是成员）折叠成幂等成功，
 * 其余失败表现为"邀请已 accepted 但没进成员表"，再次 Accept 得 409 not-pending。
 * Java 逐语义复刻，包括 {@code ErrMembershipAlreadyExists → 返回既有成员行} 的分支。</p>
 *
 * <p><b>share-link token 是明文一次生成、多次消费</b>：32 字节 SecureRandom →
 * base64url 无填充（43 字符），落库明文（管理端要"复制链接"），消费不改行、只增
 * {@code accepted_count}。不存在哈希存储——威胁模型由短 TTL + 可撤销 + 单空间授权兜住
 * （Go 注释原文语义，照抄别"加固"）。</p>
 *
 * <p><b>已知简化（记录在类注释）</b>：① Create 的 pending 唯一性以事务内预检查实现，
 * 并发双击的索引兜底路径不复刻（单实例语义一致）；② {@code tenant.auto_accept_invitation}
 * 只读 env 层（{@link #autoAcceptInvitationEnabled()}），system_settings DB 层随系统设置
 * 模块收口（同 {@code TenantAPIKeyBootstrap} 的既有取舍）。</p>
 */
@Service
public class TenantInvitationService {

    private static final Logger log = LoggerFactory.getLogger(TenantInvitationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 对照 defaultInvitationTTL：7 天。env 覆盖见 {@link #invitationTtl()} */
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);
    /** 对照 invitationTokenBytes：32 字节 → base64url 43 字符 */
    private static final int TOKEN_BYTES = 32;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_DECLINED = "declined";
    public static final String STATUS_REVOKED = "revoked";
    public static final String STATUS_EXPIRED = "expired";

    private final TenantInvitationMapper invitationMapper;
    private final TenantMemberService memberService;
    private final AuditLogService auditService;

    public TenantInvitationService(TenantInvitationMapper invitationMapper,
                                   TenantMemberService memberService,
                                   AuditLogService auditService) {
        this.invitationMapper = invitationMapper;
        this.memberService = memberService;
        this.auditService = auditService;
    }

    // ── TTL / 开关 ─────────────────────────────────────────────────────────

    /**
     * 对照 invitationTTL：WEKNORA_INVITATION_TTL 支持 Go duration（"168h"）与
     * 裸秒数（"604800"）两种写法；解析失败或非正值回落默认。
     */
    static Duration invitationTtl() {
        String raw = AppEnvLookup.get("WEKNORA_INVITATION_TTL");
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_TTL;
        }
        Duration d = parseGoDuration(raw);
        if (d != null && !d.isNegative() && !d.isZero()) {
            return d;
        }
        try {
            long secs = Long.parseLong(raw.trim());
            if (secs > 0) {
                return Duration.ofSeconds(secs);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return DEFAULT_TTL;
    }

    /** Go duration 子集解析（h/m/s 组合，够 env 覆盖用；不是完整实现） */
    private static Duration parseGoDuration(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^((\\d+)h)?((\\d+)m)?((\\d+)s)?$").matcher(raw.trim());
        if (!m.matches() || (m.group(2) == null && m.group(4) == null && m.group(6) == null)) {
            return null;
        }
        long secs = 0;
        if (m.group(2) != null) {
            secs += Long.parseLong(m.group(2)) * 3600;
        }
        if (m.group(4) != null) {
            secs += Long.parseLong(m.group(4)) * 60;
        }
        if (m.group(6) != null) {
            secs += Long.parseLong(m.group(6));
        }
        return Duration.ofSeconds(secs);
    }

    /**
     * 对照 CreateInvitation 的 auto-accept 开关判定
     * （{@code tenant.auto_accept_invitation} / {@code WEKNORA_TENANT_AUTO_ACCEPT_INVITATION}）。
     * 已知差异：Go 走 SystemSettingService 的 DB&gt;env&gt;false 三层；Java 尚无
     * SystemSettingService，只保留 env 层（默认 false），DB 层随系统设置模块收口。
     */
    public static boolean autoAcceptInvitationEnabled() {
        String raw = AppEnvLookup.get("WEKNORA_TENANT_AUTO_ACCEPT_INVITATION");
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "1", "t", "true", "yes", "y", "on" -> true;
            default -> false;
        };
    }

    // ── 读路径（全部先 sweep） ─────────────────────────────────────────────

    /** 对照 GetByID：无 sweep 的窄查询，找不到返回 null */
    public TenantInvitation getById(long id) {
        return invitationMapper.selectOne(new LambdaQueryWrapper<TenantInvitation>()
                .eq(TenantInvitation::getId, id)
                .isNull(TenantInvitation::getDeletedAt)
                .last("LIMIT 1"));
    }

    /** 对照 ListTenantInvitationsPage：id DESC + pending 过滤（include_terminal=false） */
    public InvitationPage listTenantInvitationsPage(long tenantId, boolean includeTerminal, int page, int pageSize) {
        sweep();
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
        // count 查询不能带 ORDER BY（H2 报 "Column ID must be in the GROUP BY list"）
        LambdaQueryWrapper<TenantInvitation> scope = new LambdaQueryWrapper<TenantInvitation>()
                .eq(TenantInvitation::getTenantId, tenantId)
                .isNull(TenantInvitation::getDeletedAt);
        if (!includeTerminal) {
            scope.eq(TenantInvitation::getStatus, STATUS_PENDING);
        }
        long total = invitationMapper.selectCount(scope);

        LambdaQueryWrapper<TenantInvitation> listScope = new LambdaQueryWrapper<TenantInvitation>()
                .eq(TenantInvitation::getTenantId, tenantId)
                .isNull(TenantInvitation::getDeletedAt)
                .orderByDesc(TenantInvitation::getId);
        if (!includeTerminal) {
            listScope.eq(TenantInvitation::getStatus, STATUS_PENDING);
        }
        listScope.last("LIMIT " + pageSize + " OFFSET " + (long) (page - 1) * pageSize);
        return new InvitationPage(invitationMapper.selectList(listScope), total);
    }

    public record InvitationPage(List<TenantInvitation> invitations, long total) {
    }

    /** 对照 ListByInvitee：跨空间收件箱，id DESC，无分页 */
    public List<TenantInvitation> listByInvitee(String inviteeUserId, boolean includeTerminal) {
        sweep();
        LambdaQueryWrapper<TenantInvitation> q = new LambdaQueryWrapper<TenantInvitation>()
                .eq(TenantInvitation::getInviteeUserId, inviteeUserId)
                .isNull(TenantInvitation::getDeletedAt)
                .orderByDesc(TenantInvitation::getId);
        if (!includeTerminal) {
            q.eq(TenantInvitation::getStatus, STATUS_PENDING);
        }
        return invitationMapper.selectList(q);
    }

    /** 对照 CountPendingByInvitee */
    public long countPendingByInvitee(String inviteeUserId) {
        sweep();
        Long count = invitationMapper.selectCount(new LambdaQueryWrapper<TenantInvitation>()
                .eq(TenantInvitation::getInviteeUserId, inviteeUserId)
                .eq(TenantInvitation::getStatus, STATUS_PENDING)
                .isNull(TenantInvitation::getDeletedAt));
        return count == null ? 0 : count;
    }

    // ── 创建 ───────────────────────────────────────────────────────────────

    /**
     * 对照 Create：已注册成员拒发（ErrAlreadyMember）、pending 唯一（预检查），
     * TTL 在创建时刻定型（expires_at 为**内存值**——响应里渲染调用方本地时区形态）。
     */
    public TenantInvitation create(long tenantId, String inviteeUserId, TenantRole role,
                                   String invitedBy, String message) {
        if (!role.isValid()) {
            throw TenantRbacException.invalidTenantRole();
        }
        TenantMemberService.rejectAPIKeyOwnerAssignmentForInvitation(role);
        TenantMember existing = memberService.getMembership(inviteeUserId, tenantId);
        if (existing != null && TenantMemberService.STATUS_ACTIVE.equals(existing.getStatus())) {
            throw TenantRbacException.alreadyMember();
        }
        TenantInvitation pending = getPendingByPair(tenantId, inviteeUserId);
        if (pending != null) {
            throw TenantRbacException.pendingInvitationExists();
        }
        OffsetDateTime now = OffsetDateTime.now();
        TenantInvitation inv = new TenantInvitation();
        inv.setTenantId(tenantId);
        inv.setInviteeUserId(inviteeUserId);
        inv.setInvitedBy(invitedBy);
        inv.setRole(role.value());
        inv.setStatus(STATUS_PENDING);
        inv.setMessage(message == null ? "" : message);
        inv.setExpiresAt(now.plus(invitationTtl()));
        OffsetDateTime dbNow = OffsetDateTime.now(ZoneOffset.UTC);
        inv.setCreatedAt(dbNow);
        inv.setUpdatedAt(dbNow);
        invitationMapper.insert(inv);
        emitAudit(tenantId, AuditAction.INVITATION_SENT, String.valueOf(inv.getId()), inviteeUserId, inv.getRole());
        return inv;
    }

    /**
     * 对照 CreateShareLink：服务端生成 32B 明文 token；per-user 约束
     * （already-member / duplicate-pending）**不适用**——share-link 行无特定 invitee、
     * 可并存、消费不毁行。
     */
    public TenantInvitation createShareLink(long tenantId, TenantRole role, String invitedBy, String message) {
        if (!role.isValid()) {
            throw TenantRbacException.invalidTenantRole();
        }
        TenantMemberService.rejectAPIKeyOwnerAssignmentForInvitation(role);
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        OffsetDateTime now = OffsetDateTime.now();
        TenantInvitation inv = new TenantInvitation();
        inv.setTenantId(tenantId);
        inv.setInviteeUserId("");
        inv.setToken(token);
        inv.setInvitedBy(invitedBy);
        inv.setRole(role.value());
        inv.setStatus(STATUS_PENDING);
        inv.setMessage(message == null ? "" : message);
        inv.setExpiresAt(now.plus(invitationTtl()));
        OffsetDateTime dbNow = OffsetDateTime.now(ZoneOffset.UTC);
        inv.setCreatedAt(dbNow);
        inv.setUpdatedAt(dbNow);
        invitationMapper.insert(inv);
        // TargetUserID 刻意为空——share-link 还没有 invitee（Go 注释原文）
        emitAudit(tenantId, AuditAction.INVITATION_SENT, String.valueOf(inv.getId()), "", inv.getRole());
        return inv;
    }

    // ── 状态机 ─────────────────────────────────────────────────────────────

    /**
     * 对照 Accept：pending → accepted（原子翻转）+ 写成员行（跨服务、无共事务）。
     * "已是成员"折叠为幂等成功（返回既有行）。首页采纳默认空间在 controller
     * （对照 Go：TenantID 只补 login/navigation 默认）。
     */
    public TenantMember accept(long invId, String callerUserId) {
        sweep();
        TenantInvitation inv = getById(invId);
        if (inv == null) {
            throw TenantRbacException.invitationNotFound();
        }
        if (!callerUserId.equals(inv.getInviteeUserId())) {
            throw TenantRbacException.invitationForbidden();
        }
        if (!STATUS_PENDING.equals(inv.getStatus())) {
            throw TenantRbacException.invitationNotPending();
        }
        if (inv.isExpiredAt(OffsetDateTime.now())) {
            throw TenantRbacException.invitationExpired();
        }
        markStatusIfPending(invId, STATUS_ACCEPTED);
        return addMemberIdempotent(inv);
    }

    /** 对照 Decline：仅被邀请人，pending → declined，不建成员行 */
    public void decline(long invId, String callerUserId) {
        sweep();
        TenantInvitation inv = getById(invId);
        if (inv == null) {
            throw TenantRbacException.invitationNotFound();
        }
        if (!callerUserId.equals(inv.getInviteeUserId())) {
            throw TenantRbacException.invitationForbidden();
        }
        if (!STATUS_PENDING.equals(inv.getStatus())) {
            throw TenantRbacException.invitationNotPending();
        }
        if (inv.isExpiredAt(OffsetDateTime.now())) {
            throw TenantRbacException.invitationExpired();
        }
        markStatusIfPending(invId, STATUS_DECLINED);
        emitAudit(inv.getTenantId(), AuditAction.INVITATION_DECLINED,
                String.valueOf(inv.getId()), inv.getInviteeUserId(), inv.getRole());
    }

    /**
     * 对照 Revoke：Owner 路由层把关后调用（这里不复查角色），
     * pending → revoked。跨租户的"以 A 撤 B 的邀请"由 controller 的
     * inv.TenantID != tenantID 检查兜住（404 不泄漏存在性）。
     */
    public void revoke(long invId) {
        sweep();
        TenantInvitation inv = getById(invId);
        if (inv == null) {
            throw TenantRbacException.invitationNotFound();
        }
        if (!STATUS_PENDING.equals(inv.getStatus())) {
            throw TenantRbacException.invitationNotPending();
        }
        markStatusIfPending(invId, STATUS_REVOKED);
        emitAudit(inv.getTenantId(), AuditAction.INVITATION_REVOKED,
                String.valueOf(inv.getId()), inv.getInviteeUserId(), inv.getRole());
    }

    /**
     * 对照 MarkPendingAcceptedIfExists：auto-accept / share-link 消费后
     * 对账同 (tenant, invitee) 的 stale pending 行。Go 两个调用点对返回 error 的
     * 处理都是 log-warn，故这里同样**不外抛**（bookkeeping 失败不能把已成功的
     * 加入变成失败）。
     */
    public void markPendingAcceptedIfExists(long tenantId, String inviteeUserId) {
        try {
            sweep();
            TenantInvitation inv = getPendingByPair(tenantId, inviteeUserId);
            if (inv == null) {
                return;
            }
            markStatusIfPending(inv.getId(), STATUS_ACCEPTED);
        } catch (RuntimeException e) {
            log.warn("failed to reconcile pending invitation for tenant {} user {}: {}",
                    tenantId, inviteeUserId, e.getMessage());
        }
    }

    // ── token 路径 ─────────────────────────────────────────────────────────

    /**
     * 对照 LookupByToken：sweep 后按明文 token 找 pending 行；空/未知/已过期
     * 统一折叠成 TOKEN_INVALID（不泄漏"曾经存在"）。
     */
    public TenantInvitation lookupByToken(String plainToken) {
        String token = plainToken == null ? "" : plainToken.trim();
        if (token.isEmpty()) {
            throw TenantRbacException.invitationTokenInvalid();
        }
        sweep();
        TenantInvitation inv = invitationMapper.selectOne(new LambdaQueryWrapper<TenantInvitation>()
                .eq(TenantInvitation::getToken, token)
                .eq(TenantInvitation::getStatus, STATUS_PENDING)
                .isNull(TenantInvitation::getDeletedAt)
                .last("LIMIT 1"));
        if (inv == null) {
            throw TenantRbacException.invitationTokenInvalid();
        }
        if (inv.isExpiredAt(OffsetDateTime.now())) {
            throw TenantRbacException.invitationTokenInvalid();
        }
        return inv;
    }

    /**
     * 对照 AcceptByToken：多-use——邀请行**不**翻转，accepted_count 尽力 +1；
     * 已是成员返回既有行（幂等，不让"重复点链接"看起来像角色变更）并对账
     * 同租户的 stale per-user pending 行。
     */
    public TenantMember acceptByToken(String plainToken, String newUserId) {
        if (newUserId == null || newUserId.isEmpty()) {
            throw new IllegalArgumentException("newUserID is required");
        }
        TenantInvitation inv = lookupByToken(plainToken);
        try {
            TenantMember member = memberService.addMemberChecked(
                    newUserId, inv.getTenantId(), TenantRole.fromString(inv.getRole()), inv.getInvitedBy());
            bumpAcceptedCountBestEffort(inv.getId());
            reconcilePendingInvitation(inv.getTenantId(), newUserId);
            emitAudit(inv.getTenantId(), AuditAction.INVITATION_ACCEPTED,
                    String.valueOf(inv.getId()), newUserId, inv.getRole());
            return member;
        } catch (TenantRbacException e) {
            if (e.kind() == TenantRbacException.Kind.MEMBERSHIP_ALREADY_EXISTS) {
                TenantMember existing = memberService.getMembership(newUserId, inv.getTenantId());
                if (existing != null) {
                    reconcilePendingInvitation(inv.getTenantId(), newUserId);
                    return existing;
                }
            }
            throw e;
        }
    }

    /** 对照 reconcilePendingInvitation：对账失败只记日志（bookkeeping 不反悔已成功的加入） */
    private void reconcilePendingInvitation(long tenantId, String inviteeUserId) {
        try {
            markPendingAcceptedIfExists(tenantId, inviteeUserId);
        } catch (RuntimeException e) {
            log.warn("failed to reconcile pending invitation for tenant {} user {}: {}",
                    tenantId, inviteeUserId, e.getMessage());
        }
    }

    private void bumpAcceptedCountBestEffort(long id) {
        try {
            int rows = invitationMapper.update(null, new UpdateWrapper<TenantInvitation>()
                    .eq("id", id)
                    .setSql("accepted_count = accepted_count + 1"));
            if (rows == 0) {
                log.warn("share-link {} accepted_count bump failed: row missing", id);
            }
        } catch (RuntimeException e) {
            // 对照 Go：计数只服务于管理端展示；失败不撤销已获得的成员资格
            log.warn("share-link {} accepted_count bump failed (membership still created): {}", id, e.getMessage());
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /** 对照 GetPendingByPair：partial unique index 保证 ≤1 行 */
    private TenantInvitation getPendingByPair(long tenantId, String inviteeUserId) {
        return invitationMapper.selectOne(new LambdaQueryWrapper<TenantInvitation>()
                .eq(TenantInvitation::getTenantId, tenantId)
                .eq(TenantInvitation::getInviteeUserId, inviteeUserId)
                .eq(TenantInvitation::getStatus, STATUS_PENDING)
                .isNull(TenantInvitation::getDeletedAt)
                .orderByAsc(TenantInvitation::getId)
                .last("LIMIT 1"));
    }

    /**
     * 对照 MarkStatusIfPending：WHERE status='pending' 的原子翻转；
     * 影响 0 行 = 并发点击输掉了竞态 → ErrInvitationNotPending。
     */
    private void markStatusIfPending(long id, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int rows = invitationMapper.update(null, new UpdateWrapper<TenantInvitation>()
                .eq("id", id)
                .eq("status", STATUS_PENDING)
                .isNull("deleted_at")
                .set("status", status)
                .set("responded_at", now)
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        if (rows == 0) {
            throw TenantRbacException.invitationNotPending();
        }
    }

    /** Accept 的成员写入：翻转成功后插入成员行；"已是成员"→ 返回既有行（幂等成功） */
    private TenantMember addMemberIdempotent(TenantInvitation inv) {
        try {
            TenantMember member = memberService.addMemberChecked(
                    inv.getInviteeUserId(), inv.getTenantId(), TenantRole.fromString(inv.getRole()), inv.getInvitedBy());
            emitAudit(inv.getTenantId(), AuditAction.INVITATION_ACCEPTED,
                    String.valueOf(inv.getId()), inv.getInviteeUserId(), inv.getRole());
            return member;
        } catch (TenantRbacException e) {
            if (e.kind() == TenantRbacException.Kind.MEMBERSHIP_ALREADY_EXISTS) {
                TenantMember existing = memberService.getMembership(inv.getInviteeUserId(), inv.getTenantId());
                if (existing != null) {
                    emitAudit(inv.getTenantId(), AuditAction.INVITATION_ACCEPTED,
                            String.valueOf(inv.getId()), inv.getInviteeUserId(), inv.getRole());
                    return existing;
                }
            }
            log.error("invitation {} accepted but tenant_members insert failed: {}", inv.getId(), e.getMessage());
            throw e;
        }
    }

    /**
     * 对照 sweep：把 expires_at 已过的 pending 行批量翻成 expired（responded_at=now）。
     * 刻意不给清扫行发逐行审计（Go 注释：清扫可能一次翻掉大量行，审计扇出失控）。
     * 失败只记日志——下次读路径会再试。
     */
    private void sweep() {
        try {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            invitationMapper.update(null, new UpdateWrapper<TenantInvitation>()
                    .eq("status", STATUS_PENDING)
                    .lt("expires_at", now)
                    .set("status", STATUS_EXPIRED)
                    .set("responded_at", now)
                    .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        } catch (RuntimeException e) {
            log.warn("tenant_invitation lazy sweep failed: {}", e.getMessage());
        }
    }

    /** 对照 detailsFor + emitAudit：invitation_id / role 进 Details（map 字母序） */
    private void emitAudit(long tenantId, String action, String invId, String targetUserId, String role) {
        ObjectNode details = MAPPER.createObjectNode();
        details.put("invitation_id", Long.parseLong(invId));
        details.put("role", role);
        AuditLog entry = new AuditLog();
        entry.setTenantId(tenantId);
        entry.setActorUserId(TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId());
        entry.setActorRole(TenantContext.currentRole() == null ? "" : TenantContext.currentRole());
        entry.setAction(action);
        entry.setTargetType("tenant_invitation");
        entry.setTargetId(invId);
        entry.setTargetUserId(targetUserId);
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setDetails(details);
        auditService.logBestEffort(entry);
    }
}
