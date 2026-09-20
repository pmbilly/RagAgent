package com.ragagent.im.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.agentm.mapper.JsonbRawStringTypeHandler;

/**
 * IM 渠道（对照 Go {@code im.IMChannel}，internal/im/types.go L18-34）。
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子 BeforeCreate</b>（Go L94-116）：ID 空时 UUID；mode 兜底（webhook:
 *       mattermost/yunzhijia，其余 websocket）；output_mode 兜底 stream；session_mode
 *       兜底 user；<b>validateSessionMode</b>（非 user/thread → Create 失败 → 500
 *       "failed to create channel"，golden 钉死）；bot_identity 重算。</li>
 *   <li><b>钩子 BeforeSave</b>（Go L120-129）：session_mode 兜底+校验、bot_identity
 *       重算——update/toggle 每次全量保存都触发。</li>
 *   <li><b>软删除</b>：显式 {@code deleted_at IS NULL}（约定 §9，不用 @TableLogic）。</li>
 *   <li><b>唯一索引</b> idx_im_channels_bot_identity（部分索引，deleted_at IS NULL AND
 *       bot_identity != ''）——迁移为准；应用层靠 checkDuplicateBot 先查（409 文案）。</li>
 *   <li><b>credentials 是 jsonb</b>：raw 文本直通；Go 的 types.JSON 保留请求原文键序，
 *       H2 里同样保留（真 PG 上由 jsonb 列的规范化统一两侧，A/B 依赖该列行为）。</li>
 * </ol>
 */
@TableName(value = "im_channels", autoResultMap = true)
public class ImChannelEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long tenantId;

    private String agentId;

    private String platform;

    private String name;

    private boolean enabled;

    private String mode;

    private String outputMode;

    private String knowledgeBaseId;

    private String botIdentity;

    private String sessionMode;

    @TableField(value = "credentials", typeHandler = JsonbRawStringTypeHandler.class)
    private String credentials;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    @JsonIgnore
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getOutputMode() { return outputMode; }
    public void setOutputMode(String outputMode) { this.outputMode = outputMode; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getBotIdentity() { return botIdentity; }
    public void setBotIdentity(String botIdentity) { this.botIdentity = botIdentity; }
    public String getSessionMode() { return sessionMode; }
    public void setSessionMode(String sessionMode) { this.sessionMode = sessionMode; }
    public String getCredentials() { return credentials; }
    public void setCredentials(String credentials) { this.credentials = credentials; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
