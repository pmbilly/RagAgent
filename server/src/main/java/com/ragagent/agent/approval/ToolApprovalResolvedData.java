package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 审批结果事件体（用户决定 / 超时 / 取消）
 * （对照 Go {@code event.ToolApprovalResolvedData}，internal/event/event_data.go:272-279）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolApprovalResolvedData( String pendingId, boolean approved, String reason, boolean timedOut, boolean canceled) {

    public ToolApprovalResolvedData {
        if (reason != null && reason.isEmpty()) {
            reason = null;   // Go: omitempty
        }
    }
}
