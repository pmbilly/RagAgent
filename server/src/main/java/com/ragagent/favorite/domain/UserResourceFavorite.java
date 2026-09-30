package com.ragagent.favorite.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * 单条收藏（对照 Go {@code internal/types/user_resource_favorite.go} 全文，
 * 迁移 000047）。
 *
 * <h2>复合主键，无外键（刻意的）</h2>
 * <p>主键 {@code (user_id, tenant_id, resource_type, resource_id)}；不挂指向
 * knowledge_bases / custom_agents 的外键——收藏在分享撤销、软删→硬删窗口内
 * 依然保留，读侧对看不见的资源静默丢弃（迁移注释原文）。</p>
 *
 * <h2>响应序列化 = struct 声明序</h2>
 * <p>Go 列表端点把实体数组直接塞进 {@code gin.H}，struct 字段按<b>声明序</b>输出：
 * {@code user_id, tenant_id, resource_type, resource_id, created_at}——golden
 * {@code fav-list-kb-after.json} 已钉。{@code created_at} 是 Go {@code time.Time}
 * （RFC3339Nano + 服务器本地时区偏移），挂 {@link GoTimeSerializer}。</p>
 *
 * <p>本实体从不落 jsonb，也不做 jsonb 往返；无需 @JsonIgnoreProperties 全家桶。</p>
 */
public class UserResourceFavorite {

    public static final String RESOURCE_TYPE_KB = "kb";
    public static final String RESOURCE_TYPE_AGENT = "agent";

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("resource_type")
    private String resourceType;

    @JsonProperty("resource_id")
    private String resourceId;

    /** Go gorm:"autoCreateTime"——insert 时由应用侧写入 now（DB 列另有 DEFAULT 兜底）。 */
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    /** 对照 Go IsValidFavoriteResourceType：可收藏类型的白名单。 */
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
