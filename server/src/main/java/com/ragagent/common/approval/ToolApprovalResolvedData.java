package com.ragagent.common.approval;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 审批结果事件体（用户决定 / 超时 / 取消）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolApprovalResolvedData( String pendingId, boolean approved, String reason, boolean timedOut, boolean canceled) {

    public ToolApprovalResolvedData {
        if (reason != null && reason.isEmpty()) {
            reason = null;   // 空串归一为 null，配合 NON_NULL 不序列化该字段
        }
    }
}
