package com.ragagent.mcp.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;

/**
 * 创建 MCP 服务的请求体（对照 Go handler CreateMCPService 里
 * {@code c.ShouldBindJSON(&service)} 直接把 JSON 绑到 types.MCPService）。
 *
 * <p>JSON 键名逐字对照 {@code types.MCPService} 的 json tag。用独立 record 而不是
 * 直接绑实体：实体上带有 {@code deleted_at} 等持久化字段，请求体不应能设置它们。</p>
 *
 * <p>⚠️ <b>保真说明</b>：Go 直接绑实体，因此 {@code id} 与 {@code is_builtin}
 * 也是可绑定的——客户端传 {@code id} 就能钉死主键，传 {@code is_builtin: true}
 * 就能建出一条对所有工作空间可见的行（Go 侧 BeforeCreate 只在 id 为空时生成 UUID）。
 * 这里照抄该行为（保真优先），但已在报告中标记为需要产品决策的既有风险点。</p>
 */
public record McpServiceCreateRequest(
        @JsonProperty("usage_instructions") String usageInstructions,
        @JsonProperty("id") String id,
        @JsonProperty("tenant_id") Long tenantId,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("transport_type") String transportType,
        @JsonProperty("url") String url,
        @JsonProperty("headers") Map<String, String> headers,
        @JsonProperty("auth_config") McpAuthConfig authConfig,
        @JsonProperty("advanced_config") McpAdvancedConfig advancedConfig,
        @JsonProperty("stdio_config") McpStdioConfig stdioConfig,
        @JsonProperty("env_vars") Map<String, String> envVars,
        @JsonProperty("is_builtin") Boolean isBuiltin) {

    /** 对照 Go 的 struct 绑定结果（tenantId 由 handler 覆盖）。 */
    public McpService toService() {
        McpService s = new McpService();
        if (id != null && !id.isEmpty()) {
            s.setId(id);
        }
        // usage_instructions 列是 NOT NULL DEFAULT ''；Go 的零值就是 ""
        s.setUsageInstructions(usageInstructions == null ? "" : usageInstructions);
        if (name != null) {
            s.setName(name);
        }
        if (description != null) {
            s.setDescription(description);
        }
        if (enabled != null) {
            s.setEnabled(enabled);
        }
        // Go 的 string 零值是 ""；transport_type 无 omitempty，缺省即空串
        s.setTransportType(transportType == null ? "" : transportType);
        s.setUrl(url);
        s.setHeaders(headers);
        s.setAuthConfig(authConfig);
        s.setAdvancedConfig(advancedConfig);
        s.setStdioConfig(stdioConfig);
        s.setEnvVars(envVars);
        if (isBuiltin != null) {
            s.setIsBuiltin(isBuiltin);
        }
        return s;
    }
}
