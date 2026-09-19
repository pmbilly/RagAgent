package com.ragagent.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 危险 MCP 工具即将执行时的"请求批准"事件体
 * （对照 Go {@code event.ToolApprovalRequiredData}，internal/event/event_data.go:254-270）。
 * emit 点：agent/approval/gate.go:381（{@code <pendingID>-approval-required}），见包注释 emit 表 #10。
 *
 * <p>实录锚点（minimal 形态）：{@code args}/{@code args_json}/{@code request_id} 带
 * omitempty；其余恒输出——零值时
 * {@code {"pending_id":"","tenant_id":0,"session_id":"","assistant_message_id":"","service_id":"",
 * "service_name":"","mcp_tool_name":"","registered_tool_name":"","description":"",
 * "timeout_seconds":0,"requested_at":0,"tool_call_id":""}}。</p>
 *
 * <p>{@code args} 是解析后的 JSON 对象（给 UI 渲染表单），{@code args_json} 是原始
 * JSON 串（给回填）——Go 同时给两者。</p>
 */
@JsonPropertyOrder({"pending_id", "tenant_id", "session_id", "assistant_message_id",
        "service_id", "service_name", "mcp_tool_name", "registered_tool_name", "description",
        "args", "args_json", "timeout_seconds", "requested_at", "tool_call_id", "request_id"})
public class ToolApprovalRequiredData {

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

    @JsonProperty("registered_tool_name")
    private String registeredToolName = "";

    @JsonProperty("description")
    private String description = "";

    /** Go omitempty */
    @JsonProperty("args")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Object args;

    /** Go omitempty */
    @JsonProperty("args_json")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String argsJson = "";

    @JsonProperty("timeout_seconds")
    private int timeoutSeconds;

    /** Go {@code RequestedAtUnix int64 `json:"requested_at"`}；无 omitempty：0 恒输出 */
    @JsonProperty("requested_at")
    private long requestedAtUnix;

    @JsonProperty("tool_call_id")
    private String toolCallId = "";

    /** Go omitempty */
    @JsonProperty("request_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String requestId = "";

    public ToolApprovalRequiredData() {
    }

    public ToolApprovalRequiredData(String pendingId, long tenantId, String sessionId,
                                    String assistantMessageId, String serviceId, String serviceName,
                                    String mcpToolName, String registeredToolName, String description,
                                    Object args, String argsJson, int timeoutSeconds,
                                    long requestedAtUnix, String toolCallId, String requestId) {
        this.pendingId = QueryData.orEmpty(pendingId);
        this.tenantId = tenantId;
        this.sessionId = QueryData.orEmpty(sessionId);
        this.assistantMessageId = QueryData.orEmpty(assistantMessageId);
        this.serviceId = QueryData.orEmpty(serviceId);
        this.serviceName = QueryData.orEmpty(serviceName);
        this.mcpToolName = QueryData.orEmpty(mcpToolName);
        this.registeredToolName = QueryData.orEmpty(registeredToolName);
        this.description = QueryData.orEmpty(description);
        this.args = args;
        this.argsJson = QueryData.orEmpty(argsJson);
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

    public String getRegisteredToolName() {
        return registeredToolName;
    }

    public void setRegisteredToolName(String v) {
        this.registeredToolName = QueryData.orEmpty(v);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        this.description = QueryData.orEmpty(v);
    }

    public Object getArgs() {
        return args;
    }

    public void setArgs(Object v) {
        this.args = v;
    }

    public String getArgsJson() {
        return argsJson;
    }

    public void setArgsJson(String v) {
        this.argsJson = QueryData.orEmpty(v);
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
