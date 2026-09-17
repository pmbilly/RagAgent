package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 危险 MCP 工具即将执行时的“请求批准”事件体
 * （对照 Go {@code event.ToolApprovalRequiredData}，internal/event/event_data.go:253-270）。
 *
 * <p>字段序 = Go struct 声明序；带 omitempty 的字段用 {@code NON_NULL} 表达
 * （缺失与空值对消费方等价，与 Go 逐字一致的是**字段名**这一线上契约）。</p>
 *
 * <p>字段名与类型说明见字段注释；{@code args} 是解析后的 JSON 对象，
 * {@code argsJson} 是原始 JSON 串（Go 同时给两者：前者给 UI 渲染表单，后者给回填）。</p>
 */
@JsonPropertyOrder({
        "pending_id", "tenant_id", "session_id", "assistant_message_id", "service_id", "service_name",
        "mcp_tool_name", "registered_tool_name", "description", "args", "args_json",
        "timeout_seconds", "requested_at", "tool_call_id", "request_id"
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolApprovalRequiredData(
        @JsonProperty("pending_id") String pendingId,
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("assistant_message_id") String assistantMessageId,
        @JsonProperty("service_id") String serviceId,
        @JsonProperty("service_name") String serviceName,
        @JsonProperty("mcp_tool_name") String mcpToolName,
        @JsonProperty("registered_tool_name") String registeredToolName,
        @JsonProperty("description") String description,
        @JsonProperty("args") Object args,
        @JsonProperty("args_json") String argsJson,
        @JsonProperty("timeout_seconds") int timeoutSeconds,
        @JsonProperty("requested_at") long requestedAtUnix,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("request_id") String requestId) {

    public ToolApprovalRequiredData {
        if (argsJson != null && argsJson.isEmpty()) {
            argsJson = null;   // Go: omitempty
        }
        if (requestId != null && requestId.isEmpty()) {
            requestId = null;  // Go: omitempty
        }
    }
}
