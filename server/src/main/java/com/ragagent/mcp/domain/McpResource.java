package com.ragagent.mcp.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP 服务暴露的资源（对照 Go types.MCPResource）。
 * uri/name 恒输出；description/mimeType 带 omitempty（注意 mimeType 是驼峰——协议字段名）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpResource {

    private String uri = "";
    private String name = "";
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String description;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String mimeType;

    public String getUri() { return uri; }
    public void setUri(String v) { uri = v == null ? "" : v; }
    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String v) { mimeType = v; }
}
