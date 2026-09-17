package com.ragagent.mcp.protocol;

/**
 * 目标 MCP 服务要求 OAuth 授权（对照 Go internal/mcp/client.go 的 {@code OAuthRequiredError}，client.go:124-136）。
 *
 * <p>触发条件很窄：服务端在 connect/initialize 握手里回了 <b>401</b>，且
 * {@code WWW-Authenticate} 头按 RFC 9728 广告了 protected-resource metadata URL，
 * <b>而该服务并未配置为 OAuth 策略</b>。调用方据此引导用户把鉴权策略切到 OAuth，
 * 而不是抛一个泛泛的 "401"。</p>
 *
 * <p>裸 401（没有 metadata URL）<b>不算</b> OAuth required——那只是普通的鉴权失败
 * （比如 API key 错了），不能被误引到 OAuth（见 {@link McpAuthHeaders#asOAuthRequired}）。
 * 因此本异常构造成功即意味着 {@link #metadataUrl()} 非空。</p>
 */
public class McpOAuthRequiredException extends McpException {

    private static final long serialVersionUID = 1L;

    private final String metadataUrl;

    public McpOAuthRequiredException(String metadataUrl, Throwable cause) {
        // 对照 Go：OAuthRequiredError 的 code 沿用被包裹错误的哨兵语义（Go 的 Unwrap 让
        // errors.Is(err, 原哨兵) 仍然成立），文案则是 "the MCP server requires OAuth authorization: %v"。
        super(codeOf(cause), "the MCP server requires OAuth authorization: " + describe(cause), cause);
        this.metadataUrl = metadataUrl;
    }

    private static McpErrorCode codeOf(Throwable cause) {
        // asOAuthRequired 只会包裹 McpAuthorizationRequiredException，故这里恒为
        // AUTHORIZATION_REQUIRED；兜底分支只为防御性编程。
        return cause instanceof McpException me ? me.code() : McpErrorCode.AUTHORIZATION_REQUIRED;
    }

    /** 服务端经 WWW-Authenticate 广告的 RFC 9728 protected-resource metadata URL（构造期非空）。 */
    public String metadataUrl() {
        return metadataUrl;
    }

    /** 对照 Go {@code fmt.Sprintf("...: %v", e.Err)}——Go 用 %v 打印被包裹的 error。 */
    private static String describe(Throwable cause) {
        return cause == null ? "null" : String.valueOf(cause.getMessage());
    }
}
