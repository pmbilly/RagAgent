package com.ragagent.auth.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.mapper.AuthTokenMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 成员域的 service。
 *
 * <p>登录链消费的四个方法：</p>
 * <ul>
 *   <li>{@link #getMembership}：单条 active 成员查询（软删除过滤）</li>
 *   <li>{@link #listByUser}：按 joined_at 稳定排序</li>
 *   <li>{@link #hasAnyActiveMembers}：孤儿空间自愈判定</li>
 *   <li>{@link #addMember}：孤儿空间自愈写入（"不存在则建 Owner 行"分支）</li>
 * </ul>
 *
 * <p>HTTP 面方法（与端点一一对应）：
 * {@link #listMembersPage}、{@link #addMemberChecked}
 * （含 API-Key 禁授 Owner 与审计）、{@link #updateRole}（+ 最后 Owner 保护）、
 * {@link #removeMember}（+ 最后 Owner 保护 + 成员移除后的清理）。
 * 哨兵错误以 {@link TenantRbacException} 承载，HTTP 形态由 controller 逐端点映射。</p>
 *
 * <p><b>已知简化</b>：对"最后一位 Owner"的判定，
 * 本实现用"先数其他 active Owner 再写"的等价判定（无行级锁）。
 * 并发双降级的极端窗口仍可能放过第二笔——多实例部署需数据库层约束兜底。</p>
 */
@Service
public class TenantMemberService {

    private static final Logger log = LoggerFactory.getLogger(TenantMemberService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String STATUS_ACTIVE = "active";
    public static final int LIST_DEFAULT_PAGE_SIZE = 20;
    public static final int LIST_MAX_PAGE_SIZE = 100;

    private final TenantMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final AuthTokenMapper authTokenMapper;
    private final AuditLogService auditService;

    public TenantMemberService(TenantMemberMapper memberMapper,
                               UserMapper userMapper,
                               AuthTokenMapper authTokenMapper,
                               AuditLogService auditService) {
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
        this.authTokenMapper = authTokenMapper;
        this.auditService = auditService;
    }

    // ── 登录链 ────────────────────────────────────────────────────────────

    /** 单条成员查询：找不到返回 null */
    public TenantMember getMembership(String userId, long tenantId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getUserId, userId)
                .eq(TenantMember::getTenantId, tenantId)
                .isNull(TenantMember::getDeletedAt)
                .orderByAsc(TenantMember::getId)
                .last("LIMIT 1"));
    }

    /** 用户的成员行列表：按 joined_at, id 升序（稳定序） */
    public List<TenantMember> listByUser(String userId) {
        return memberMapper.selectList(new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getUserId, userId)
                .isNull(TenantMember::getDeletedAt)
                .orderByAsc(TenantMember::getJoinedAt)
                .orderByAsc(TenantMember::getId));
    }

    /**
     * 幂等建 Owner：已有成员行
     * 原样返回；否则插 owner/active 行。并发下唯一索引拒绝时重读胜出行
     * （DuplicateKeyException → 重读）。
     * POST /tenants 的 owner 引导走这里。
     */
    public TenantMember ensureOwner(String userId, long tenantId) {
        TenantMember existing = getMembership(userId, tenantId);
        if (existing != null) {
            return existing;
        }
        try {
            return addMember(userId, tenantId, TenantRole.OWNER.value(), null);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            TenantMember winner = getMembership(userId, tenantId);
            if (winner != null) {
                return winner;
            }
            throw e;
        }
    }

    /** 目标空间是否存在 active 成员（不含软删除行） */
    public boolean hasAnyActiveMembers(long tenantId) {
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getTenantId, tenantId)
                .eq(TenantMember::getStatus, STATUS_ACTIVE)
                .isNull(TenantMember::getDeletedAt));
        return count != null && count > 0;
    }

    /**
     * 插入成员行（孤儿空间自愈等内部路径用）：已存在则由唯一索引拒绝；
     * joined_at 语义为"成为成员的时间"。
     */
    public TenantMember addMember(String userId, long tenantId, String role, String invitedBy) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TenantMember m = new TenantMember();
        m.setUserId(userId);
        m.setTenantId(tenantId);
        m.setRole(role);
        m.setStatus(STATUS_ACTIVE);
        m.setInvitedBy(invitedBy);
        m.setJoinedAt(now);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        memberMapper.insert(m);
        return m;
    }

    // ── HTTP 面 ────────────────────────────────────────────────────────────

    /** 分页结果（成员行 + 总数） */
    public record MemberPage(List<TenantMember> members, long total) {
    }

    /**
     * 成员分页：trim query、page&lt;1→1、size&lt;1→20、size&gt;100→100，
     * joined_at ASC, id ASC。q 非空时按 LOWER(email/username) LIKE 命中的用户 id
     * 做 user_id IN 过滤；users 行缺失（悬挂成员）
     * 在 q 非空时被排除（join 语义），q 为空时全部计入。
     */
    public MemberPage listMembersPage(long tenantId, String query, int page, int pageSize) {
        String q = query == null ? "" : query.trim();
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = LIST_DEFAULT_PAGE_SIZE;
        }
        if (pageSize > LIST_MAX_PAGE_SIZE) {
            pageSize = LIST_MAX_PAGE_SIZE;
        }

        LambdaQueryWrapper<TenantMember> scope = new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getTenantId, tenantId)
                .isNull(TenantMember::getDeletedAt);
        if (!q.isEmpty()) {
            List<String> ids = userIdsMatching(q);
            if (ids.isEmpty()) {
                return new MemberPage(new ArrayList<>(), 0);
            }
            scope.in(TenantMember::getUserId, ids);
        }
        long total = memberMapper.selectCount(scope);

        LambdaQueryWrapper<TenantMember> listScope = new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getTenantId, tenantId)
                .isNull(TenantMember::getDeletedAt)
                .orderByAsc(TenantMember::getJoinedAt)
                .orderByAsc(TenantMember::getId);
        if (!q.isEmpty()) {
            listScope.in(TenantMember::getUserId, userIdsMatching(q));
        }
        listScope.last("LIMIT " + pageSize + " OFFSET " + (long) (page - 1) * pageSize);
        return new MemberPage(memberMapper.selectList(listScope), total);
    }

    /** escapeLikePattern（\ % _ 依次转义）+ % 包裹，交由 LOWER(... LIKE LOWER(?)) 匹配 */
    private List<String> userIdsMatching(String q) {
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String like = "%" + escaped + "%";
        QueryWrapper<User> w = new QueryWrapper<User>()
                .select("id")
                .apply("(LOWER(email) LIKE LOWER({0}) OR LOWER(username) LIKE LOWER({1}))", like, like);
        List<String> ids = new ArrayList<>();
        for (User u : userMapper.selectList(w)) {
            ids.add(u.getId());
        }
        return ids;
    }

    /**
     * 直加成员（HTTP 路径）：role 校验、API-Key 禁授 Owner、
     * 已存在冲突与审计（rbac.member_added）。
     */
    public TenantMember addMemberChecked(String userId, long tenantId, TenantRole role, String invitedBy) {
        if (!role.isValid()) {
            throw TenantRbacException.invalidTenantRole();
        }
        rejectAPIKeyOwnerAssignment(role);
        TenantMember existing = getMembership(userId, tenantId);
        if (existing != null) {
            throw TenantRbacException.membershipAlreadyExists();
        }
        TenantMember member = addMember(userId, tenantId, role.value(), invitedBy);
        emitAudit(tenantId, AuditAction.MEMBER_ADDED, member.getUserId(), null);
        return member;
    }

    /**
     * API-Key 禁授 Owner：manage_members 刻意不含所有权转移——
     * 机器主体可管低角色，但绝不能铸造一个持久的 Owner。
     * 包内可见：邀请创建路径（Create/CreateShareLink）共用同一条边界。
     */
    static void rejectAPIKeyOwnerAssignmentForInvitation(TenantRole role) {
        rejectAPIKeyOwnerAssignment(role);
    }

    private static void rejectAPIKeyOwnerAssignment(TenantRole role) {
        if (role != TenantRole.OWNER) {
            return;
        }
        if (APIKeyScopeContext.present()) {
            throw TenantRbacException.apiKeyCannotAssignOwner();
        }
    }

    /**
     * 改角色：同角色 no-op（不审计）；Owner 降级走最后 Owner 判定。
     */
    public void updateRole(String userId, long tenantId, TenantRole newRole) {
        if (!newRole.isValid()) {
            throw TenantRbacException.invalidTenantRole();
        }
        rejectAPIKeyOwnerAssignment(newRole);
        TenantMember current = getMembership(userId, tenantId);
        if (current == null) {
            throw TenantRbacException.membershipNotFound();
        }
        if (TenantRole.fromString(current.getRole()) == newRole) {
            return;
        }
        String oldRole = current.getRole();
        if (TenantRole.OWNER.value().equals(oldRole) && newRole != TenantRole.OWNER) {
            if (!hasOtherActiveOwner(userId, tenantId)) {
                throw TenantRbacException.lastOwner();
            }
        }
        memberMapper.update(null, new UpdateWrapper<TenantMember>()
                .eq("user_id", userId)
                .eq("tenant_id", tenantId)
                .isNull("deleted_at")
                .set("role", newRole.value())
                .set("updated_at", OffsetDateTime.now(ZoneOffset.UTC)));
        emitRoleChangeAudit(tenantId, userId, oldRole, newRole.value());
    }

    /**
     * 移除成员：软删除 + 最后 Owner 保护 + 移除后清理
     * （清悬挂 home/偏好指针 + 吊销 token——都是尽力而为，绝不使移除失败）。
     */
    public void removeMember(String userId, long tenantId) {
        TenantMember current = getMembership(userId, tenantId);
        if (current == null) {
            throw TenantRbacException.membershipNotFound();
        }
        if (TenantRole.OWNER.value().equals(current.getRole()) && !hasOtherActiveOwner(userId, tenantId)) {
            throw TenantRbacException.lastOwner();
        }
        memberMapper.update(null, new UpdateWrapper<TenantMember>()
                .eq("user_id", userId)
                .eq("tenant_id", tenantId)
                .isNull("deleted_at")
                .set("deleted_at", OffsetDateTime.now(ZoneOffset.UTC)));

        // 审计区分"自愿 leave"（caller == target）与"被移除"
        String actor = TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId();
        String action = !actor.isEmpty() && actor.equals(userId)
                ? AuditAction.MEMBER_LEFT
                : AuditAction.MEMBER_REMOVED;
        emitAudit(tenantId, action, userId, null);
        cleanupRemovedMemberState(userId, tenantId);
    }

    /** 最后 Owner 判定：是否存在**其他** active Owner */
    private boolean hasOtherActiveOwner(String userId, long tenantId) {
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getTenantId, tenantId)
                .ne(TenantMember::getUserId, userId)
                .eq(TenantMember::getRole, TenantRole.OWNER.value())
                .eq(TenantMember::getStatus, STATUS_ACTIVE)
                .isNull(TenantMember::getDeletedAt));
        return count != null && count > 0;
    }

    /**
     * 成员移除后的状态清理：清 users.tenant_id / preferences.last_active_tenant_id
     * 的悬挂指针并吊销该用户的全部 token。全部尽力而为——失败只记日志。
     * <p><b>tenant_id 清空写 SQL NULL</b>——PG 的 fk_users_tenant
     * 不认 0，写 0 会静默失败成"只记 warn"的空转。</p>
     */
    private void cleanupRemovedMemberState(String userId, long tenantId) {
        try {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getId, userId)
                    .isNull(User::getDeletedAt)
                    .last("LIMIT 1"));
            if (user != null) {
                boolean tenantPointerChanged = false;
                boolean prefChanged = false;
                if (user.getTenantId() != null && user.getTenantId() == tenantId) {
                    tenantPointerChanged = true;
                }
                if (user.getPreferences() != null && user.getPreferences().getLastActiveTenantId() != null
                        && user.getPreferences().getLastActiveTenantId() == tenantId) {
                    user.getPreferences().setLastActiveTenantId(null);
                    prefChanged = true;
                }
                if (prefChanged) {
                    user.setTenantId(null); // 让 updateById 跳过该列，tenant_id 由下方显式置 NULL
                    user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    userMapper.updateById(user);
                }
                if (tenantPointerChanged) {
                    // 显式写 NULL，别写 0（FK 拒绝）
                    userMapper.update(null, new UpdateWrapper<User>()
                            .eq("id", userId)
                            .set("tenant_id", null));
                }
            }
        } catch (RuntimeException e) {
            log.warn("RemoveMember cleanup: failed to clear stale tenant pointers for user {} tenant {}: {}",
                    userId, tenantId, e.toString());
        }
        try {
            authTokenMapper.update(null, new UpdateWrapper<com.ragagent.auth.domain.AuthToken>()
                    .eq("user_id", userId)
                    .eq("is_revoked", false)
                    .set("is_revoked", true));
        } catch (RuntimeException e) {
            log.warn("RemoveMember cleanup: failed to revoke tokens for user {}: {}", userId, e.toString());
        }
    }

    /** best-effort 审计（审计失败绝不拖垮业务操作） */
    private void emitAudit(long tenantId, String action, String targetUserId, ObjectNode details) {
        AuditLog entry = new AuditLog();
        entry.setTenantId(tenantId);
        entry.setActorUserId(TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId());
        entry.setActorRole(TenantContext.currentRole() == null ? "" : TenantContext.currentRole());
        entry.setAction(action);
        entry.setTargetType("tenant_member");
        entry.setTargetUserId(targetUserId);
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setDetails(details);
        auditService.logBestEffort(entry);
    }

    /** 角色变更审计：old_role/new_role 进 Details 供审计 UI 渲染 */
    private void emitRoleChangeAudit(long tenantId, String targetUserId, String oldRole, String newRole) {
        ObjectNode details = MAPPER.createObjectNode();
        details.put("old_role", oldRole);
        details.put("new_role", newRole);
        emitAudit(tenantId, AuditAction.MEMBER_ROLE_CHANGED, targetUserId, details);
    }
}
