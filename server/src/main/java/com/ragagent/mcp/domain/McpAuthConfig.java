package com.ragagent.mcp.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * MCP 服务鉴权配置（对照 Go types.MCPAuthConfig）。
 *
 * **密钥处理契约**：
 * - 秘密字段（apiKey / token）持久化在本结构里，但**绝不**经主资源响应返回——
 *   响应走 dto.McpServiceResponse，它在**构造期**就没有这些字段（编译期保证，
 *   不靠 handler 记得脱敏）。凭据变更走专用的 /credentials 子资源。
 * - 落库加密由 {@link com.ragagent.mcp.domain.McpAuthConfigTypeHandler} 负责
 *   （对照 Go 的 driver.Valuer / sql.Scanner）。
 *
 * OAuth 说明：OAuth 策略**不在本结构存秘密**。按用户的 access/refresh token 存
 * mcp_oauth_tokens，动态注册的客户端存 mcp_oauth_clients。这里只有
 * scopes / authServerMetadataUrl 这类非秘密配置。
 *
 * <p>⚠️ <b>键名必须是蛇形</b>（对照 Go 的 json tag）。这些注解不是装饰：
 * 本结构既走 HTTP 请求/响应，也经 {@link McpAuthConfigTypeHandler} 落到 auth_config
 * jsonb 列——键名一旦写成 Java 字段名（apiKey/customHeaders），
 * 既接不住前端按契约发来的 {@code api_key}/{@code custom_headers}，
 * 也读不出 Go 写的行（只有 {@code token} / {@code scopes} 这类单词字段侥幸对上）。</p>
 */
public class McpAuthConfig {

    /** 鉴权策略；空串视为无鉴权（历史行兼容） */
    private McpAuthType authType;
    private String apiKey;
    /**
     * AuthType=api_key 时承载 APIKey 的头名，空则默认 "X-API-Key"。
     * 这是非秘密的结构配置（秘密是 APIKey 本身），故不加密、可安全回显——
     * 让期望密钥在别的头里的服务（如把裸 token 放进 Authorization）无需退化成明文自定义头。
     */
    private String apiKeyHeader;
    private String token;
    private Map<String, String> customHeaders;
    /** OAuth 授权时请求的 scope，可选 */
    private List<String> scopes;
    /**
     * 可选：钉死 OAuth 授权服务器 metadata URL。为空时从 MCP URL 自动发现
     * （RFC 9728 / RFC 8414）。
     */
    private String authServerMetadataUrl;

    /**
     * 是否使用 OAuth 策略（对照 Go IsOAuth）。
     *
     * @JsonIgnore 是**必须的**：Go 的方法是方法、不参与 JSON；Java 的 isXxx 会被 Jackson
     * 当属性序列化成 "oauth":true，落库后再回读就抛 UnrecognizedPropertyException，
     * 导致整个 auth_config 列不可用（阶段 3 的 isAborted / isMultimodalEnabled 踩过同一个坑）。
     */
    @JsonIgnore
    public boolean isOAuth() {
        return authType == McpAuthType.OAUTH;
    }

    public McpAuthType getAuthType() { return authType; }
    public void setAuthType(McpAuthType v) { authType = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public String getApiKeyHeader() { return apiKeyHeader; }
    public void setApiKeyHeader(String v) { apiKeyHeader = v; }
    public String getToken() { return token; }
    public void setToken(String v) { token = v; }
    public Map<String, String> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(Map<String, String> v) { customHeaders = v; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> v) { scopes = v; }
    public String getAuthServerMetadataUrl() { return authServerMetadataUrl; }
    public void setAuthServerMetadataUrl(String v) { authServerMetadataUrl = v; }
}
