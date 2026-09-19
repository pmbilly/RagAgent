package com.ragagent.org.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 组织（对照 Go internal/types/organization.go 的 Organization）。
 *
 * <p>GORM 清单（§3）：软删除 deleted_at → 显式 isNull 条件（不用 @TableLogic，见约定 §9）；
 * 无钩子；默认排序由各查询显式携带（created_at DESC / ASC）；invite_code 唯一索引以迁移为准
 * （部分唯一索引 WHERE deleted_at IS NULL，Java 侧 Create 生成的随机码天然不冲突，不落约束）。</p>
 */
@TableName("organizations")
public class Organization {
    private String id;
    private String name;
    private String description;
    private String avatar;
    private String ownerId;
    private Long ownerTenantId;
    private String inviteCode;
    private OffsetDateTime inviteCodeExpiresAt;
    private int inviteCodeValidityDays;
    private boolean requireApproval;
    private boolean searchable;
    private int memberLimit;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public Long getOwnerTenantId() { return ownerTenantId; }
    public void setOwnerTenantId(Long ownerTenantId) { this.ownerTenantId = ownerTenantId; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public OffsetDateTime getInviteCodeExpiresAt() { return inviteCodeExpiresAt; }
    public void setInviteCodeExpiresAt(OffsetDateTime v) { this.inviteCodeExpiresAt = v; }
    public int getInviteCodeValidityDays() { return inviteCodeValidityDays; }
    public void setInviteCodeValidityDays(int v) { this.inviteCodeValidityDays = v; }
    public boolean isRequireApproval() { return requireApproval; }
    public void setRequireApproval(boolean v) { this.requireApproval = v; }
    public boolean isSearchable() { return searchable; }
    public void setSearchable(boolean v) { this.searchable = v; }
    public int getMemberLimit() { return memberLimit; }
    public void setMemberLimit(int v) { this.memberLimit = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { this.deletedAt = v; }

    /** Go 非指针零值：NULL → 0 */
    public long ownerTenantIdOrZero() {
        return ownerTenantId == null ? 0L : ownerTenantId;
    }
}
