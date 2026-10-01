package com.ragagent.session.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一条助手消息 + 一套生效 agent 配置的**生成/缓存记录**（对照 Go
 * {@code types.MessageSuggestionSet}，internal/types/message_suggestion.go L71-94）。
 *
 * <h2>GORM 隐式行为 → Java 的等效清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子 BeforeCreate</b>（Go L98-106）：ID 为空时才生成 UUID（**不是**无条件覆盖），
 *       并把 nil 的 {@code Questions} 置为空切片。注意与 Session/Message 的
 *       "无条件覆盖"不同，这里保留调用方传入的 ID。</li>
 *   <li><b>无软删除列</b>：本表没有 {@code DeletedAt}，仓储的 Delete 是**硬删**。</li>
 *   <li><b>唯一索引</b>：{@code (tenant_id, assistant_message_id, placement, config_hash, locale)}
 *       是 UNIQUE（迁移里名为 idx_message_suggestion_sets_cache_key）——
 *       {@code AcquireGeneration} 的 ON CONFLICT DO NOTHING 就靠它。</li>
 * </ol>
 */
@TableName(value = "message_suggestion_sets", autoResultMap = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageSuggestionSet {

    public static final String PLACEMENT_AFTER_ANSWER = "after_answer";

    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_SUPPRESSED = "suppressed";
    public static final String STATUS_FAILED = "failed";

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long tenantId;

    private String sessionId = "";

    private String assistantMessageId = "";

    private String agentId = "";

    /** **不进 JSON**（Go 的 {@code json:"-"}）。 */
    @TableField("agent_tenant_id")
    @JsonIgnore
    private long agentTenantId;

    private String placement = "";

    private String configHash = "";

    private String locale = "";

    private String status = "";

    private boolean allowRegenerate;

    private String suppressionReason = "";

    /** **无 omitempty**：恒输出（nil 时 Go 输出 {@code []}，见 BeforeCreate）。 */
    @TableField(value = "questions", typeHandler = SuggestionItemListTypeHandler.class)
    private List<SuggestionItem> questions;

    private String modelId = "";

    private int promptTokens;

    private int completionTokens;

    private long latencyMs;

    private String errorCode = "";

    /** 生成租约。**不进 JSON**（Go 的 {@code json:"-"}）。 */
    @JsonIgnore
    private OffsetDateTime leaseUntil;

    private OffsetDateTime generatedAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public MessageSuggestionSet() {
    }

    /** 对照 Go 的 BeforeCreate：ID **为空时**才生成（保留调用方传入的值）。 */
    public void normalizeForInsert() {
        if (id == null || id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (questions == null) {
            questions = new java.util.ArrayList<>();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long v) {
        this.tenantId = v;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = v == null ? "" : v;
    }

    public String getAssistantMessageId() {
        return assistantMessageId;
    }

    public void setAssistantMessageId(String v) {
        this.assistantMessageId = v == null ? "" : v;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String v) {
        this.agentId = v == null ? "" : v;
    }

    public long getAgentTenantId() {
        return agentTenantId;
    }

    public void setAgentTenantId(long v) {
        this.agentTenantId = v;
    }

    public String getPlacement() {
        return placement;
    }

    public void setPlacement(String v) {
        this.placement = v == null ? "" : v;
    }

    public String getConfigHash() {
        return configHash;
    }

    public void setConfigHash(String v) {
        this.configHash = v == null ? "" : v;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String v) {
        this.locale = v == null ? "" : v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        this.status = v == null ? "" : v;
    }

    public boolean isAllowRegenerate() {
        return allowRegenerate;
    }

    public void setAllowRegenerate(boolean v) {
        this.allowRegenerate = v;
    }

    public String getSuppressionReason() {
        return suppressionReason;
    }

    public void setSuppressionReason(String v) {
        this.suppressionReason = v;
    }

    public List<SuggestionItem> getQuestions() {
        return questions;
    }

    public void setQuestions(List<SuggestionItem> v) {
        this.questions = v;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String v) {
        this.modelId = v;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int v) {
        this.promptTokens = v;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int v) {
        this.completionTokens = v;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long v) {
        this.latencyMs = v;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String v) {
        this.errorCode = v;
    }

    public OffsetDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(OffsetDateTime v) {
        this.leaseUntil = v;
    }

    public OffsetDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(OffsetDateTime v) {
        this.generatedAt = v;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime v) {
        this.createdAt = v;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime v) {
        this.updatedAt = v;
    }
}
