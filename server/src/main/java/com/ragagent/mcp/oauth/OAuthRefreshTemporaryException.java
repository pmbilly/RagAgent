package com.ragagent.mcp.oauth;

/**
 * 刷新因<b>临时</b>原因失败——token 必须保留（对照 Go
 * internal/mcp/oauth_lifecycle.go:53-61 的 {@code OAuthRefreshTemporaryError}）。
 *
 * <p>调用方必须把它当作<b>运维故障</b>上报/重试，而<b>不是</b>打开一个新的授权弹窗：
 * 用户并没有撤销授权，重弹只会让人误以为授权丢了。对照地，永久失败
 * （invalid_grant / invalid_client 之类）会删除 token 并抛
 * {@link OAuthReauthorizationRequiredException}。</p>
 */
public class OAuthRefreshTemporaryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Throwable cause;

    public OAuthRefreshTemporaryException(Throwable cause) {
        super("MCP OAuth token refresh temporarily failed: " + describe(cause), cause);
        this.cause = cause;
    }

    private static String describe(Throwable cause) {
        return cause == null ? "null" : String.valueOf(cause.getMessage());
    }

    /** 对照 Go {@code Unwrap()}：底层刷新错误可继续被上层识别。 */
    @Override
    public Throwable getCause() {
        return cause;
    }
}
