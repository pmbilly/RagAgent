package com.ragagent.org.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

/** KB 共享记录（对照 Go KnowledgeBaseShare，表 kb_shares）。 */
@TableName("kb_shares")
public class KbShare {
    private String id;
    private String knowledgeBaseId;
    private String organizationId;
    private String sharedByUserId;
    private Long sourceTenantId;
    private String permission;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String v) { this.organizationId = v; }
    public String getSharedByUserId() { return sharedByUserId; }
    public void setSharedByUserId(String v) { this.sharedByUserId = v; }
    public Long getSourceTenantId() { return sourceTenantId; }
    public void setSourceTenantId(Long v) { this.sourceTenantId = v; }
    public String getPermission() { return permission; }
    public void setPermission(String v) { this.permission = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { this.deletedAt = v; }
}
