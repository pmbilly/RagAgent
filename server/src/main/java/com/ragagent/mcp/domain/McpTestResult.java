package com.ragagent.mcp.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 测试 MCP 服务连接的结果（对照 Go types.MCPTestResult）。
 * success 恒输出，其余 omitempty。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpTestResult {

    private boolean success;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String message;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String description;
    /**
     * 连接失败是**因为服务端要求 OAuth 授权**（RFC 9728）——即使该服务并未配置为 OAuth。
     * UI 用它引导用户把鉴权策略切到 OAuth。
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean oauthRequired;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<McpTool> tools;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<McpResource> resources;

    public McpTestResult() {
    }

    public static McpTestResult ok(String message) {
        McpTestResult r = new McpTestResult();
        r.success = true;
        r.message = message;
        return r;
    }

    public static McpTestResult fail(String message) {
        McpTestResult r = new McpTestResult();
        r.success = false;
        r.message = message;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { success = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public boolean isOauthRequired() { return oauthRequired; }
    public void setOauthRequired(boolean v) { oauthRequired = v; }
    public List<McpTool> getTools() { return tools; }
    public void setTools(List<McpTool> v) { tools = v; }
    public List<McpResource> getResources() { return resources; }
    public void setResources(List<McpResource> v) { resources = v; }
}
