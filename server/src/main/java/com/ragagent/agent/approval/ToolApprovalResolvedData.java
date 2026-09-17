package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 审批结果事件体（用户决定 / 超时 / 取消）
 * （对照 Go {@code event.ToolApprovalResolvedData}，internal/event/event_data.go:272-279）。
 */
@JsonPropertyOrder({"pending_id", "approved", "reason", "timed_out", "canceled"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolApprovalResolvedData(
        @JsonProperty("pending_id") String pendingId,
        @JsonProperty("approved") boolean approved,
        @JsonProperty("reason") String reason,
        @JsonProperty("timed_out") boolean timedOut,
        @JsonProperty("canceled") boolean canceled) {

    public ToolApprovalResolvedData {
        if (reason != null && reason.isEmpty()) {
            reason = null;   // Go: omitempty
        }
    }
}
