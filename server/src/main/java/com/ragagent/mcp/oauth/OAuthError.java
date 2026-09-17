package com.ragagent.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 标准 OAuth 2.0 错误响应（对照 mcp-go {@code client/transport.OAuthError}，oauth.go:477-489）。
 *
 * <p><b>为什么单独建模</b>：刷新失败的<b>永久/临时</b>判定完全取决于
 * {@link #errorCode()}（{@code invalid_grant} / {@code invalid_client} …），
 * 见 {@code OAuthRuntime#permanentRefreshFailure}。而 GitHub 一类授权服务器会
 * <b>用 HTTP 200 携带 error 字段</b>返回错误，所以不仅要看状态码，还要看 body 里有没有它。</p>
 *
 * <p>{@code Error()} 文案逐字对照 Go：{@code "OAuth error: <code> - <desc>"} /
 * {@code "OAuth error: <code>"}。该文案会被 {@code permanentRefreshFailure} 的
 * 兜底字符串匹配（"status 400" / "status 401"）看到，不能改。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthError(
        @JsonProperty("error") String errorCode,
        @JsonProperty("error_description") String errorDescription,
        @JsonProperty("error_uri") String errorUri) {

    public OAuthError {
        errorCode = errorCode == null ? "" : errorCode;
        errorDescription = errorDescription == null ? "" : errorDescription;
        errorUri = errorUri == null ? "" : errorUri;
    }

    /** 对照 Go {@code Error()}。 */
    public String toMessage() {
        if (!errorDescription.isEmpty()) {
            return "OAuth error: " + errorCode + " - " + errorDescription;
        }
        return "OAuth error: " + errorCode;
    }

    public boolean isPresent() {
        return !errorCode.isEmpty();
    }
}
