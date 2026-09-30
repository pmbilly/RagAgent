package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 会话内 MCP OAuth 授权提示事件体（对照 Go {@code event.MCPOAuthRequiredData}，
 * internal/event/event_data.go:284-296）。
 * emit 点：agent/approval/gate.go:479（{@code <pendingID>-mcp-oauth-required}）与
 * agent/tools/mcp_oauth.go:201（{@code mcp-oauth-notice-<serviceID>}，仅提示形态、
 * {@code timeout_seconds=0}），见包注释 emit 表 #12/#23。
 *
 * <p>对话中调用了带 OAuth 的 MCP 服务但当前用户尚未授权时发出；UI 弹"授权"卡片，
 * agent 暂停等待。实录锚点：仅 {@code request_id} 带 omitempty，其余恒输出
 * （noticeOnly 形态的 {@code timeout_seconds:0} 也输出）。</p>
 */
@JsonPropertyOrder({"pending_id", "tenant_id", "session_id", "assistant_message_id",
        "service_id", "service_name", "mcp_tool_name", "timeout_seconds", "requested_at",
        "tool_call_id", "request_id"})
public class MCPOAuthRequiredData {

    @JsonProperty("pending_id")
    private String pendingId = "";

    /** Go uint64；无 omitempty：0 恒输出 */
    @JsonProperty("tenant_id")
    private long tenantId;

    @JsonProperty("session_id")
    private String sessionId = "";

    @JsonProperty("assistant_message_id")
    private String assistantMessageId = "";

    @JsonProperty("service_id")
    private String serviceId = "";

    @JsonProperty("service_name")
    private String serviceName = "";

    @JsonProperty("mcp_tool_name")
    private String mcpToolName = "";

    /** 无 omitempty：0（仅提示形态）也输出 */
    @JsonProperty("timeout_seconds")
    private int timeoutSeconds;

    /** Go {@code RequestedAtUnix int64 `json:"requested_at"`}；无 omitempty */
    @JsonProperty("requested_at")
    private long requestedAtUnix;

    @JsonProperty("tool_call_id")
    private String toolCallId = "";

    /** Go omitempty */
    @JsonProperty("request_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String requestId = "";

    public MCPOAuthRequiredData() {
    }

    public MCPOAuthRequiredData(String pendingId, long tenantId, String sessionId,
                                String assistantMessageId, String serviceId, String serviceName,
                                String mcpToolName, int timeoutSeconds, long requestedAtUnix,
                                String toolCallId, String requestId) {
        this.pendingId = QueryData.orEmpty(pendingId);
        this.tenantId = tenantId;
        this.sessionId = QueryData.orEmpty(sessionId);
        this.assistantMessageId = QueryData.orEmpty(assistantMessageId);
        this.serviceId = QueryData.orEmpty(serviceId);
        this.serviceName = QueryData.orEmpty(serviceName);
        this.mcpToolName = QueryData.orEmpty(mcpToolName);
        this.timeoutSeconds = timeoutSeconds;
        this.requestedAtUnix = requestedAtUnix;
        this.toolCallId = QueryData.orEmpty(toolCallId);
        this.requestId = QueryData.orEmpty(requestId);
    }

    public String getPendingId() {
        return pendingId;
    }

    public void setPendingId(String v) {
        this.pendingId = QueryData.orEmpty(v);
    }

    public long getTenantId() {
        return tenantId;
    }

    public void setTenantId(long v) {
        this.tenantId = v;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        this.sessionId = QueryData.orEmpty(v);
    }

    public String getAssistantMessageId() {
        return assistantMessageId;
    }

    public void setAssistantMessageId(String v) {
        this.assistantMessageId = QueryData.orEmpty(v);
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String v) {
        this.serviceId = QueryData.orEmpty(v);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String v) {
        this.serviceName = QueryData.orEmpty(v);
    }

    public String getMcpToolName() {
        return mcpToolName;
    }

    public void setMcpToolName(String v) {
        this.mcpToolName = QueryData.orEmpty(v);
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int v) {
        this.timeoutSeconds = v;
    }

    public long getRequestedAtUnix() {
        return requestedAtUnix;
    }

    public void setRequestedAtUnix(long v) {
        this.requestedAtUnix = v;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String v) {
        this.toolCallId = QueryData.orEmpty(v);
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String v) {
        this.requestId = QueryData.orEmpty(v);
    }
}
