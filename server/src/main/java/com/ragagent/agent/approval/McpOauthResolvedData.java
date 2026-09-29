package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OAuth 授权等待的结果事件体（授权 / 超时 / 取消）
 * （对照 Go {@code event.MCPOAuthResolvedData}，internal/event/event_data.go:298-306）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpOauthResolvedData( String pendingId, String serviceId, boolean authorized, String reason, boolean timedOut, boolean canceled) {

    public McpOauthResolvedData {
        if (reason != null && reason.isEmpty()) {
            reason = null;   // Go: omitempty
        }
    }
}
