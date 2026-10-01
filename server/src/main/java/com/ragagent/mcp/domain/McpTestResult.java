package com.ragagent.mcp.domain;

import java.util.List;


/**
 * 测试 MCP 服务连接的结果（对照 Go types.MCPTestResult）。
 * success 恒输出，其余 omitempty。
 */
public class McpTestResult {

    private boolean success;
        private String message;
        private String description;
    /**
     * 连接失败是**因为服务端要求 OAuth 授权**（RFC 9728）——即使该服务并未配置为 OAuth。
     * UI 用它引导用户把鉴权策略切到 OAuth。
     */
        private boolean oauthRequired;
        private List<McpTool> tools;
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
