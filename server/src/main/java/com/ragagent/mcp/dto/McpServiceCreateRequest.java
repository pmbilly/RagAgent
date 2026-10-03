package com.ragagent.mcp.dto;

import java.util.Map;

import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;

/**
 * 创建 MCP 服务的请求体。
 *
 * <p>JSON 键名与既有契约保持一致。用独立 record 而不是
 * 直接绑实体：实体上带有 {@code deleted_at} 等持久化字段，请求体不应能设置它们。</p>
 *
 * <p>⚠️ <b>既有风险说明</b>：{@code id} 与 {@code builtin} 同样可由请求体提供——
 * 客户端传 {@code id} 就能钉死主键，传 {@code builtin: true}
 * 就能建出一条对所有工作空间可见的行。该行为按既有契约保留，
 * 已标记为需要产品决策的风险点。</p>
 */
public record McpServiceCreateRequest( String usageInstructions, String id, Long tenantId, String name, String description, Boolean enabled, String transportType, String url, Map<String, String> headers, McpAuthConfig authConfig, McpAdvancedConfig advancedConfig, McpStdioConfig stdioConfig, Map<String, String> envVars, Boolean builtin) {

    /** 转成实体（tenantId 由 handler 覆盖）。 */
    public McpService toService() {
        McpService s = new McpService();
        if (id != null && !id.isEmpty()) {
            s.setId(id);
        }
        // usage_instructions 列是 NOT NULL DEFAULT ''；缺省按空串落库
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
        // transport_type 无省略语义，缺省即空串
        s.setTransportType(transportType == null ? "" : transportType);
        s.setUrl(url);
        s.setHeaders(headers);
        s.setAuthConfig(authConfig);
        s.setAdvancedConfig(advancedConfig);
        s.setStdioConfig(stdioConfig);
        s.setEnvVars(envVars);
        if (builtin != null) {
            s.setIsBuiltin(builtin);
        }
        return s;
    }
}
