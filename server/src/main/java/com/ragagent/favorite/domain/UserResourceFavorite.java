package com.ragagent.favorite.domain;

import java.time.OffsetDateTime;

/**
 * 单条收藏。
 *
 * <h2>复合主键，无外键（刻意的）</h2>
 * <p>主键 {@code (user_id, tenant_id, resource_type, resource_id)}；不挂指向
 * knowledge_bases / custom_agents 的外键——收藏在分享撤销、软删→硬删窗口内
 * 依然保留，读侧对看不见的资源静默丢弃。</p>
 *
 * <p>响应键名即 Java 字段名（camelCase），时间 ISO-8601 带时区。
 * 本实体从不落 jsonb，也不做 jsonb 往返。</p>
 */
public class UserResourceFavorite {

    public static final String RESOURCE_TYPE_KB = "kb";
    public static final String RESOURCE_TYPE_AGENT = "agent";

    private String userId;

    private Long tenantId;

    private String resourceType;

    private String resourceId;

    /** insert 时由应用侧写入 now（DB 列另有 DEFAULT 兜底）。 */
    private OffsetDateTime createdAt;

    /** 可收藏类型的白名单。 */
    public static boolean isValidResourceType(String t) {
        return RESOURCE_TYPE_KB.equals(t) || RESOURCE_TYPE_AGENT.equals(t);
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
