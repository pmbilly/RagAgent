package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * OAuth 授权等待的结果事件体（授权 / 超时 / 取消）
 * （对照 Go {@code event.MCPOAuthResolvedData}，internal/event/event_data.go:298-306）。
 */
@JsonPropertyOrder({"pending_id", "service_id", "authorized", "reason", "timed_out", "canceled"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpOauthResolvedData(
        @JsonProperty("pending_id") String pendingId,
        @JsonProperty("service_id") String serviceId,
        @JsonProperty("authorized") boolean authorized,
        @JsonProperty("reason") String reason,
        @JsonProperty("timed_out") boolean timedOut,
        @JsonProperty("canceled") boolean canceled) {

    public McpOauthResolvedData {
        if (reason != null && reason.isEmpty()) {
            reason = null;   // Go: omitempty
        }
    }
}
