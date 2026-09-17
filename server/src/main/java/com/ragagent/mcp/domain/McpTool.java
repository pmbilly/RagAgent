package com.ragagent.mcp.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MCP 服务暴露的工具（对照 Go types.MCPTool）。
 *
 * 注意 {@code inputSchema} 是**驼峰**——这是 MCP 协议规范的字段名，别按项目惯例改成 snake_case。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpTool {

    private String name = "";
    private String description = "";
    /** 工具参数的 JSON Schema；协议字段名为 inputSchema（驼峰） */
    @JsonProperty("inputSchema")
    private Object inputSchema;
    /**
     * 为 true 时 agent 执行会暂停，等用户在 UI 批准（issue #1173）。
     * 键名是 {@code require_approval}（对照 Go 的 json tag）——本对象既走
     * GET /{id}/tools 响应，也存进 mcp_metadata.tools 的 jsonb。
     */
    @JsonProperty("require_approval")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean requireApproval;

    public McpTool() {
    }

    public McpTool(String name, String description, Object inputSchema) {
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema;
    }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v == null ? "" : v; }
    public Object getInputSchema() { return inputSchema; }
    public void setInputSchema(Object v) { inputSchema = v; }
    public boolean isRequireApproval() { return requireApproval; }
    public void setRequireApproval(boolean v) { requireApproval = v; }
}
