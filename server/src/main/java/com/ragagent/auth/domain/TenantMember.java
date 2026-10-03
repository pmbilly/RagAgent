package com.ragagent.auth.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * tenant_members 表实体。
 *
 * 落库行为清单：
 * - 软删除 → 显式 isNull("deleted_at") 条件；部分唯一索引
 *   uniq_user_tenant ON (user_id, tenant_id) WHERE deleted_at IS NULL（迁移 000043）
 * - id 自增主键 → @TableId(type = AUTO)
 * - role 默认 'contributor'；status 默认 'active'
 * - 查询排序约定：ListByUser 按 joined_at 稳定排序（"stably ordered by join time"）
 */
@TableName("tenant_members")
public class TenantMember {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long tenantId;
    /** owner/admin/contributor/viewer（TenantRole 的字符串值） */
    private String role;
    /** active/invited/suspended */
    private String status;
    private String invitedBy;
    private OffsetDateTime joinedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { userId = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { role = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getInvitedBy() { return invitedBy; }
    public void setInvitedBy(String v) { invitedBy = v; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(OffsetDateTime v) { joinedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
