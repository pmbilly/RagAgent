package com.ragagent.session.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 追问建议的产品分析事件（对照 Go {@code types.MessageSuggestionEvent}，
 * internal/types/message_suggestion.go L110-119）。
 *
 * <p>与安全审计日志**分开存**：这里只引用 question ID，不复制建议文案。</p>
 */
@TableName("message_suggestion_events")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageSuggestionEvent {

    public static final String EVENT_IMPRESSION = "impression";
    public static final String EVENT_CLICK = "click";
    public static final String EVENT_DISMISS = "dismiss";
    public static final String EVENT_REGENERATE = "regenerate";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String sessionId = "";

    private String suggestionSetId = "";

    private String questionId;

    private String eventType = "";

    /** 行为主体。**不进 JSON**（Go 的 {@code json:"-"}）。 */
    @TableField("actor_id")
    @JsonIgnore
    private String actorId = "";

    private OffsetDateTime createdAt;

    public MessageSuggestionEvent() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
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

    public String getSuggestionSetId() {
        return suggestionSetId;
    }

    public void setSuggestionSetId(String v) {
        this.suggestionSetId = v == null ? "" : v;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String v) {
        this.questionId = v;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String v) {
        this.eventType = v == null ? "" : v;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String v) {
        this.actorId = v == null ? "" : v;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime v) {
        this.createdAt = v;
    }
}
