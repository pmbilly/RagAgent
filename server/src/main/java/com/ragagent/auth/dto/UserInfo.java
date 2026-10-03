package com.ragagent.auth.dto;

import java.time.OffsetDateTime;

import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;

/**
 * 用户信息投影。
 * 用于 GET /auth/validate 与 GET /auth/me 的 user 字段。
 *
 * 与 User 实体序列化的差别：**没有 deleted_at 字段**。
 * avatar 恒输出（零值 ""）；tenant_id 恒输出（零值 0）；
 * preferences 恒输出对象（空为 {}）。
 */
public record UserInfo(
        String id,
        String username,
        String email,
        String avatar,
        long tenantId,
        boolean isActive,
        boolean canAccessAllTenants,
        boolean isSystemAdmin,
        UserPreferences preferences,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 实体 → 投影。canAccessAllTenants 在 /auth/me 里还要
     * 与部署级 EnableCrossTenantAccess 相与（其它端点传 user.isCanAccessAllTenants()）。
     */
    public static UserInfo from(User u, boolean canAccessAllTenants) {
        return new UserInfo(
                u.getId(),
                u.getUsername() == null ? "" : u.getUsername(),
                u.getEmail() == null ? "" : u.getEmail(),
                u.getAvatar(),
                u.getTenantId() == null ? 0 : u.getTenantId(),
                u.isIsActive(),
                canAccessAllTenants,
                u.isIsSystemAdmin(),
                u.getPreferences() == null ? new UserPreferences() : u.getPreferences(),
                u.getCreatedAt(),
                u.getUpdatedAt());
    }
}
