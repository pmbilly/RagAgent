package com.ragagent.mcp.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;

/**
 * MCP 鉴权配置的响应形态。
 *
 * <p><b>本结构里刻意没有 api_key / token 字段</b>——不是运行时脱敏，而是"编译期就写不出来"。
 * 密钥是否存在由 {@link McpServiceResponse#credentials()} 的布尔值表达。
 * AuthType / Scopes / AuthServerMetadataURL 是非秘密的 OAuth 配置，可以安全回显。</p>
 */
public record McpAuthConfigResponse( String authType, String apiKeyHeader, Map<String, String> customHeaders, List<String> scopes, String authServerMetadataUrl) {

    /**
     * 由 {@link McpAuthConfig} 逐字段拷出响应。
     *
     * @param includeDetail false 时剥离 custom_headers
     */
    public static McpAuthConfigResponse from(McpAuthConfig c, boolean includeDetail) {
        if (c == null) {
            return null;
        }
        // Go omitempty：零值 MCPAuthNone（""）不输出
        McpAuthType authType = c.getAuthType() == null ? McpAuthType.NONE : c.getAuthType();
        return new McpAuthConfigResponse(
                emptyToNull(authType.value()),
                emptyToNull(c.getApiKeyHeader()),
                // Go omitempty 对 map：nil 与空 map 都省略
                includeDetail && c.getCustomHeaders() != null && !c.getCustomHeaders().isEmpty()
                        ? new LinkedHashMap<>(c.getCustomHeaders()) : null,
                c.getScopes() == null || c.getScopes().isEmpty() ? null : List.copyOf(c.getScopes()),
                emptyToNull(c.getAuthServerMetadataUrl()));
    }

    /** 空串按"没有值"处理（序列化时省略） */
    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
