package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * “该 MCP 服务需要用户 OAuth 授权”事件体
 * （对照 Go {@code event.MCPOAuthRequiredData}，internal/event/event_data.go:281-296）。
 *
 * <p>与 {@link ToolApprovalRequiredData} 的区别：没有 args/description——
 * 它由 MCP 传输层返回的 “authorization required” 错误**被动触发**，
 * 而不是查策略表得来的。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpOauthRequiredData( String pendingId, long tenantId, String sessionId, String assistantMessageId, String serviceId, String serviceName, String mcpToolName, int timeoutSeconds, long requestedAtUnix, String toolCallId, String requestId) {

    public McpOauthRequiredData {
        if (requestId != null && requestId.isEmpty()) {
            requestId = null;   // Go: omitempty
        }
    }
}
