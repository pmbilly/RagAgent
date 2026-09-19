package com.ragagent.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 停止生成请求数据（对照 Go {@code event.StopData}，internal/event/event_data.go:247-251）。
 * continue-stream 的 stop watcher 检测到该事件即取消生成
 * （handler/session/stream.go:375、helpers.go:394——不在 24 个 emit 表内，属 handler 层）。
 * 实录锚点：{@code reason} 带 omitempty。
 */
@JsonPropertyOrder({"session_id", "message_id", "reason"})
public class StopData {

    @JsonProperty("session_id")
    private String sessionId = "";

    @JsonProperty("message_id")
    private String messageId = "";

    /** 停止原因（可选）；Go omitempty */
    @JsonProperty("reason")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String reason = "";

    public StopData() {
    }

    public StopData(String sessionId, String messageId, String reason) {
        this.sessionId = QueryData.orEmpty(sessionId);
        this.messageId = QueryData.orEmpty(messageId);
        this.reason = QueryData.orEmpty(reason);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = QueryData.orEmpty(v);
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String v) {
        this.messageId = QueryData.orEmpty(v);
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        this.reason = QueryData.orEmpty(v);
    }
}
