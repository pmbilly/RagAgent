package com.ragagent.auth.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * tenant_invitations 表实体（对照 Go types/tenant_invitation.go TenantInvitation）。
 *
 * GORM 隐式行为清单（迁移 000048 建表 + 000064 补 token/accepted_count 列）：
 * - 软删除 → 显式 isNull("deleted_at") 条件；部分唯一索引
 *   idx_tenant_invitations_unique_pending ON (tenant_id, invitee_user_id)
 *   WHERE status='pending' AND deleted_at IS NULL（Go 侧以事务内预检查为主，
 *   索引仅作并发兜底——Java 复刻预检查路径）
 * - invitee_user_id 默认 ''（share-link 行的空串哨兵，不是 NULL）
 * - token 默认 ''（share-link 明文 token；per-user 邀请恒空串）
 * - status 默认 'pending'；accepted_count 默认 0
 * - 实体不出 HTTP 响应（响应走 dto.TenantInvitationResponse），无需 @JsonProperty
 */
@TableName("tenant_invitations")
public class TenantInvitation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** share-link 行为空串（Go 的 "" 哨兵） */
    private String inviteeUserId = "";
    /** share-link 明文 token（per-user 邀请恒 ""） */
    private String token = "";
    private String invitedBy;
    private String role;
    private String status = "pending";
    private String message;
    private OffsetDateTime expiresAt;
    private OffsetDateTime respondedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;
    private int acceptedCount;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getInviteeUserId() { return inviteeUserId; }
    public void setInviteeUserId(String v) { inviteeUserId = v; }
    public String getToken() { return token; }
    public void setToken(String v) { token = v; }
    public String getInvitedBy() { return invitedBy; }
    public void setInvitedBy(String v) { invitedBy = v; }
    public String getRole() { return role; }
    public void setRole(String v) { role = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { expiresAt = v; }
    public OffsetDateTime getRespondedAt() { return respondedAt; }
    public void setRespondedAt(OffsetDateTime v) { respondedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
    public int getAcceptedCount() { return acceptedCount; }
    public void setAcceptedCount(int v) { acceptedCount = v; }

    /** 对照 TenantInvitation.IsExpired：expires_at 零值视为未过期（NULL 同义） */
    public boolean isExpiredAt(OffsetDateTime at) {
        if (expiresAt == null) {
            return false;
        }
        return expiresAt.isBefore(at);
    }
}
