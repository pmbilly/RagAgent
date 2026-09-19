package com.ragagent.auth.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;

/**
 * 用户信息投影（对照 Go types/user.go UserInfo + User.ToUserInfo）。
 * 用于 GET /auth/validate 与 GET /auth/me 的 user 字段。
 *
 * 与 User 实体序列化的差别：**没有 deleted_at 字段**（UserInfo 结构体不含它）。
 * avatar 恒输出（Go 非指针 string 零值 ""）；tenant_id 恒输出（uint64 零值 0）；
 * preferences 恒输出对象（Go 值类型，空为 {}）。
 */
@JsonPropertyOrder({"id", "username", "email", "avatar", "tenant_id",
        "is_active", "can_access_all_tenants", "is_system_admin", "preferences",
        "created_at", "updated_at"})
public record UserInfo(
        @JsonProperty("id") String id,
        @JsonProperty("username") String username,
        @JsonProperty("email") String email,
        @JsonProperty("avatar") String avatar,
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("is_active") boolean isActive,
        @JsonProperty("can_access_all_tenants") boolean canAccessAllTenants,
        @JsonProperty("is_system_admin") boolean isSystemAdmin,
        @JsonProperty("preferences") UserPreferences preferences,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {

    /**
     * 对照 ToUserInfo + /auth/me 的调整：canAccessAllTenants 在 /auth/me 里还要
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
