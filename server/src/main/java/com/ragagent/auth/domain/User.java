package com.ragagent.auth.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * users 表实体。
 *
 * 落库行为清单：
 * - 软删除：显式 isNull("deleted_at") 条件（不用 @TableLogic）
 * - 唯一索引：username、email（迁移 000001 的 UNIQUE 约束）
 * - 默认值：is_active=true、can_access_all_tenants=false、is_system_admin=false、preferences='{}'
 * - 无 BeforeCreate/AfterFind 钩子；created_at/updated_at 由 DB DEFAULT 填充
 *
 * JSON 输出契约（字段声明序，controller 直接序列化 User，故字段序必须一致）：
 * id, username, email, (password_hash 不输出), avatar, tenant_id,
 * is_active, can_access_all_tenants, is_system_admin, preferences,
 * created_at, updated_at, deleted_at（未删除时序列化为 null）
 */
@TableName(value = "users", autoResultMap = true)
public class User {

    @TableId(type = IdType.INPUT)
    private String id;
    private String username;
    private String email;
    /** 数据库真实列 password_hash；永不出现在响应中 */
    @JsonIgnore
    private String passwordHash;

    private String avatar;
    /** 非空 Long：0 表示无租户 */
    private Long tenantId;
    /** 布尔（列默认 true） */
    private boolean isActive;
    private boolean canAccessAllTenants;
    private boolean isSystemAdmin;
    /** jsonb 列；UserPreferences 键恒输出，空对象为 {} */
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private UserPreferences preferences;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    /** 软删除标记；null 时 JSON 输出 "deleted_at":null */
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    /**
     * avatar 列 DB NULL 归一为 ""，响应恒输出 "avatar":""。
     */
    public String getAvatar() { return avatar == null ? "" : avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    /**
     * tenantId 列 DB NULL 归一为 0，响应恒输出数字。
     * getter 归一化后 AuthFilter 的 null 检查恒不命中。
     */
    public Long getTenantId() { return tenantId == null ? 0 : tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public boolean isIsActive() { return isActive; }
    public void setIsActive(boolean active) { isActive = active; }
    public boolean isCanAccessAllTenants() { return canAccessAllTenants; }
    public void setCanAccessAllTenants(boolean v) { canAccessAllTenants = v; }
    public boolean isIsSystemAdmin() { return isSystemAdmin; }
    public void setIsSystemAdmin(boolean v) { isSystemAdmin = v; }
    /** preferences 恒输出对象（空为 {}）；DB NULL 兜底为空对象 */
    public UserPreferences getPreferences() {
        if (preferences == null) {
            preferences = new UserPreferences();
        }
        return preferences;
    }
    public void setPreferences(UserPreferences preferences) { this.preferences = preferences; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
