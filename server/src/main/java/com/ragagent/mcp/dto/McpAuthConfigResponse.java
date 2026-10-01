package com.ragagent.mcp.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;

/**
 * MCP 鉴权配置的响应形态（对照 Go dto.MCPAuthConfigResponse，
 * internal/handler/dto/mcp.go:59-69）。
 *
 * <p><b>本结构里刻意没有 api_key / token 字段</b>——不是运行时脱敏，而是"编译期就写不出来"。
 * 密钥是否存在由 {@link McpServiceResponse#credentials()} 的布尔值表达。
 * AuthType / Scopes / AuthServerMetadataURL 是非秘密的 OAuth 配置，可以安全回显。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpAuthConfigResponse( String authType, String apiKeyHeader, Map<String, String> customHeaders, List<String> scopes, String authServerMetadataUrl) {

    /**
     * 对照 Go 侧把 {@code *types.MCPAuthConfig} 逐字段拷进响应的构造逻辑。
     *
     * @param includeDetail false 时剥离 custom_headers（对照 Go
     *                      {@code if !includeDetail { auth.CustomHeaders = nil }}）
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

    /** 对照 Go 的 omitempty：空串即"没有值" */
    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
