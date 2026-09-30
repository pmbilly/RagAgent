package com.ragagent.common.approval;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OAuth 授权等待的结果事件体（授权 / 超时 / 取消）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpOauthResolvedData( String pendingId, String serviceId, boolean authorized, String reason, boolean timedOut, boolean canceled) {

    public McpOauthResolvedData {
        if (reason != null && reason.isEmpty()) {
            reason = null;   // 空串归一为 null，配合 NON_NULL 不序列化该字段
        }
    }
}
