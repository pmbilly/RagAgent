package com.ragagent.im.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.agentm.mapper.JsonbRawStringTypeHandler;

/**
 * IM 渠道会话：平台 (user×chat[×thread]) 组合 ↔ WeKnora 会话的映射
 * （对照 Go {@code im.ChannelSession}，internal/im/types.go L221-236）。
 * IM 集成靠它维持会话连续性。
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ul>
 *   <li>BeforeCreate（Go L242-250）：ID 空时 UUID；status 空兜底 "active"。</li>
 *   <li>软删除走显式 {@code deleted_at IS NULL}（约定 §9，不用 @TableLogic）。</li>
 *   <li>metadata 是 jsonb；Java 侧 raw 文本直通（im_channels 的 credentials 同款）。</li>
 * </ul>
 */
@TableName(value = "im_channel_sessions", autoResultMap = true)
public class ChannelSessionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String platform;

    private String userId;

    private String chatId;

    private String threadId;

    private String sessionId;

    private Long tenantId;

    private String agentId;

    private String imChannelId;

    private String status;

    @TableField(value = "metadata", typeHandler = JsonbRawStringTypeHandler.class)
    private String metadata;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    @JsonIgnore
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getImChannelId() { return imChannelId; }
    public void setImChannelId(String imChannelId) { this.imChannelId = imChannelId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
