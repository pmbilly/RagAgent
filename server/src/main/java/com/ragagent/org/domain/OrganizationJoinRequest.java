package com.ragagent.org.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

/** 加入/升级申请（对照 Go OrganizationJoinRequest）。 */
@TableName("organization_join_requests")
public class OrganizationJoinRequest {
    private String id;
    private String organizationId;
    private String userId;
    private Long tenantId;
    private String requestType;
    private String prevRole;
    private String requestedRole;
    private String status;
    private String message;
    private String reviewedBy;
    private OffsetDateTime reviewedAt;
    private String reviewMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String v) { this.organizationId = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }
    public String getRequestType() { return requestType; }
    public void setRequestType(String v) { this.requestType = v; }
    public String getPrevRole() { return prevRole; }
    public void setPrevRole(String v) { this.prevRole = v; }
    public String getRequestedRole() { return requestedRole; }
    public void setRequestedRole(String v) { this.requestedRole = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { this.reviewedBy = v; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime v) { this.reviewedAt = v; }
    public String getReviewMessage() { return reviewMessage; }
    public void setReviewMessage(String v) { this.reviewMessage = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
