package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 运行中用户消息注入报告。
 * emit 点：SteerIntake（{@code generateEventID("injected")}），见包注释 emit 表 #23。
 *
 * <p>用户在生成期间追加的消息被并入运行中的轮次：一行 user-role 消息已按该轮的
 * request ID 落库、文本已追加进 agent 的消息列表，下一次 LLM 调用即可见。
 * 前四字段恒输出，仅 {@code user_message_id} 空串省略。</p>
 */
@JsonPropertyOrder({"steer_id", "content", "message_id", "user_message_id"})
public class UserMessageInjectedData {

    /** 与排队的 steer 事件关联 */
    @JsonProperty("steer_id")
    private String steerId = "";

    /** 注入的文本（与发给模型的一致） */
    @JsonProperty("content")
    private String content = "";

    /** 该轮的持久 assistant 消息 ID */
    @JsonProperty("message_id")
    private String messageId = "";

    /** 空串省略 */
    @JsonProperty("user_message_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String userMessageId = "";

    public UserMessageInjectedData() {
    }

    public UserMessageInjectedData(String steerId, String content, String messageId,
                                   String userMessageId) {
        this.steerId = QueryData.orEmpty(steerId);
        this.content = QueryData.orEmpty(content);
        this.messageId = QueryData.orEmpty(messageId);
        this.userMessageId = QueryData.orEmpty(userMessageId);
    }

    public String getSteerId() {
        return steerId;
    }

    public void setSteerId(String v) {
        this.steerId = QueryData.orEmpty(v);
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = QueryData.orEmpty(v);
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String v) {
        this.messageId = QueryData.orEmpty(v);
    }

    public String getUserMessageId() {
        return userMessageId;
    }

    public void setUserMessageId(String v) {
        this.userMessageId = QueryData.orEmpty(v);
    }
}
