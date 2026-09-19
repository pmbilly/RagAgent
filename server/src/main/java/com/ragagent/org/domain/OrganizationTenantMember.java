package com.ragagent.org.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

/** 组织成员（空间粒度，迁移 000045；对照 Go OrganizationTenantMember）。 */
@TableName("organization_tenant_members")
public class OrganizationTenantMember {
    private String id;
    private String organizationId;
    private Long tenantId;
    private String role;
    private String representativeUserId;
    private OffsetDateTime joinedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String v) { this.organizationId = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getRepresentativeUserId() { return representativeUserId; }
    public void setRepresentativeUserId(String v) { this.representativeUserId = v; }
    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(OffsetDateTime v) { this.joinedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
