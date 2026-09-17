package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * “该 MCP 服务需要用户 OAuth 授权”事件体
 * （对照 Go {@code event.MCPOAuthRequiredData}，internal/event/event_data.go:281-296）。
 *
 * <p>与 {@link ToolApprovalRequiredData} 的区别：没有 args/description——
 * 它由 MCP 传输层返回的 “authorization required” 错误**被动触发**，
 * 而不是查策略表得来的。</p>
 */
@JsonPropertyOrder({
        "pending_id", "tenant_id", "session_id", "assistant_message_id", "service_id", "service_name",
        "mcp_tool_name", "timeout_seconds", "requested_at", "tool_call_id", "request_id"
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpOauthRequiredData(
        @JsonProperty("pending_id") String pendingId,
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("assistant_message_id") String assistantMessageId,
        @JsonProperty("service_id") String serviceId,
        @JsonProperty("service_name") String serviceName,
        @JsonProperty("mcp_tool_name") String mcpToolName,
        @JsonProperty("timeout_seconds") int timeoutSeconds,
        @JsonProperty("requested_at") long requestedAtUnix,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("request_id") String requestId) {

    public McpOauthRequiredData {
        if (requestId != null && requestId.isEmpty()) {
            requestId = null;   // Go: omitempty
        }
    }
}
