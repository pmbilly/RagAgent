package com.ragagent.mcp.oauth;

/**
 * 无法在不重新征得用户同意的情况下恢复出可用的 access token（对照 Go
 * internal/mcp/oauth_lifecycle.go:39-48 的 {@code OAuthReauthorizationRequiredError}）。
 *
 * <p>调用方（前端）据此打开一次新的授权弹窗；而临时性刷新失败走
 * {@link OAuthRefreshTemporaryException}，那是运维故障，<b>不该</b>弹窗。</p>
 */
public class OAuthReauthorizationRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public OAuthReauthorizationRequiredException(String reason) {
        super(message(reason));
        this.reason = reason == null ? "" : reason;
    }

    /** 对照 Go {@code Error()}：无 reason 时只有前缀文案。 */
    private static String message(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "MCP OAuth authorization required";
        }
        return "MCP OAuth authorization required: " + reason;
    }

    public String reason() {
        return reason;
    }
}
