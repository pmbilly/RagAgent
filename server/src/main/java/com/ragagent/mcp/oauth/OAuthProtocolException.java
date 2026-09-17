package com.ragagent.mcp.oauth;

/**
 * OAuth 协议层失败（对照 Go 里 {@code fmt.Errorf("%s: %w", context, oauthErr)} 与
 * {@code fmt.Errorf("%s with status %d: %s", context, statusCode, body)} 两种错误形态）。
 *
 * <p><b>为什么带 {@link OAuthError}</b>：刷新失败的永久/临时判定要先看
 * {@code error_code}（invalid_grant / invalid_client…），拿不到结构化错误才退回到
 * 消息文本里的 "status 400"/"status 401" 匹配。这与 Go 的
 * {@code errors.As(err, &oauthErr)} 后回退到 `strings.Contains` 完全同构。</p>
 *
 * <p><b>消息文案逐字对照 Go</b>——兜底分支靠 {@code "status 400"} 这类子串判定，
 * 改文案会改变永久失败的判定结果。</p>
 */
public class OAuthProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient OAuthError oauthError;

    private OAuthProtocolException(String message, OAuthError oauthError, Throwable cause) {
        super(message, cause);
        this.oauthError = oauthError;
    }

    /** 对照 {@code extractOAuthError} 的结构化分支：{@code "<context>: <OAuth error: ...>"}。 */
    public static OAuthProtocolException ofOAuthError(String context, OAuthError error) {
        return new OAuthProtocolException(context + ": " + error.toMessage(), error, null);
    }

    /** 对照 {@code extractOAuthError} 的兜底分支：{@code "<context> with status <n>: <body>"}。 */
    public static OAuthProtocolException ofRawStatus(String context, int statusCode, String body) {
        return new OAuthProtocolException(
                context + " with status " + statusCode + ": " + (body == null ? "" : body),
                null, null);
    }

    /** 其它协议层失败（元数据发现失败 / 空 token 等），文案由调用方给。 */
    public static OAuthProtocolException of(String message) {
        return new OAuthProtocolException(message, null, null);
    }

    public static OAuthProtocolException of(String message, Throwable cause) {
        return new OAuthProtocolException(message, null, cause);
    }

    /** 结构化 OAuth 错误；非结构化失败时为 {@code null}。 */
    public OAuthError oauthError() {
        return oauthError;
    }
}
