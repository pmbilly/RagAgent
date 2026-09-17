package com.ragagent.mcp.protocol;

/**
 * 传输层 401 信号（对照 mcp-go {@code client/transport.AuthorizationRequiredError}，
 * streamable_http.go:459-471）。
 *
 * <p>Field 语义逐条对照 Go：</p>
 * <ul>
 *   <li>{@code ResourceMetadataURL} ← RFC 9728 §5.1 从 {@code WWW-Authenticate} 响应头里
 *       解析出的 {@code resource_metadata} 参数值。可能为空串（裸 401）。</li>
 *   <li>Go 的 {@code Error()} 恒返回哨兵文案 {@code "authorization required"}，
 *       实际的 URL 只在字段里——Java 同样让 {@link #getMessage()} 保持哨兵文案。</li>
 * </ul>
 *
 * <p>客户端层拿到它后会经 {@link McpAuthHeaders#asOAuthRequired} 判定：<b>只有带非空
 * metadata URL 的才升级为</b> {@link McpOAuthRequiredException}。</p>
 */
public class McpAuthorizationRequiredException extends McpException {

    private static final long serialVersionUID = 1L;

    private final String resourceMetadataUrl;

    public McpAuthorizationRequiredException(String resourceMetadataUrl) {
        super(McpErrorCode.AUTHORIZATION_REQUIRED, McpErrorCode.AUTHORIZATION_REQUIRED.goMessage());
        this.resourceMetadataUrl = resourceMetadataUrl == null ? "" : resourceMetadataUrl;
    }

    /** RFC 9728 protected-resource metadata URL；裸 401 时为空串。 */
    public String resourceMetadataUrl() {
        return resourceMetadataUrl;
    }
}
