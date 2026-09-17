package com.ragagent.auth.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.mapper.TenantMemberMapper;
import org.springframework.stereotype.Service;

/**
 * 对照 Go TenantMemberService（internal/application/service/tenant_member.go）。
 *
 * 本阶段消费的四个方法：
 * - GetMembership(user, tenant)：单条 active 成员查询（软删除过滤）
 * - ListByUser(user)：按 joined_at 稳定排序（Go 注释 "stably ordered by join time"）
 * - HasAnyMembers(tenant)：孤儿空间自愈判定（active 行计数）
 * - AddMember：孤儿空间自愈写入（role=owner，status=active）
 */
@Service
public class TenantMemberService {

    public static final String STATUS_ACTIVE = "active";

    private final TenantMemberMapper memberMapper;

    public TenantMemberService(TenantMemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    /** 对照 GetMembership：找不到返回 null（Go err != nil 语义由调用方统一处理） */
    public TenantMember getMembership(String userId, long tenantId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getUserId, userId)
                .eq(TenantMember::getTenantId, tenantId)
                .isNull(TenantMember::getDeletedAt)
                .orderByAsc(TenantMember::getId)
                .last("LIMIT 1"));
    }

    /** 对照 ListByUser：按 joined_at, id 升序（稳定序） */
    public List<TenantMember> listByUser(String userId) {
        return memberMapper.selectList(new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getUserId, userId)
                .isNull(TenantMember::getDeletedAt)
                .orderByAsc(TenantMember::getJoinedAt)
                .orderByAsc(TenantMember::getId));
    }

    /** 对照 HasAnyMembers：目标空间是否存在 active 成员（不含软删除行） */
    public boolean hasAnyActiveMembers(long tenantId) {
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<TenantMember>()
                .eq(TenantMember::getTenantId, tenantId)
                .eq(TenantMember::getStatus, STATUS_ACTIVE)
                .isNull(TenantMember::getDeletedAt));
        return count != null && count > 0;
    }

    /**
     * 对照 AddMember：孤儿空间自愈路径插入 Owner 行。
     * Go 侧 joined_at 语义为"成为成员的时间"，此处显式写入当前时间。
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
}
