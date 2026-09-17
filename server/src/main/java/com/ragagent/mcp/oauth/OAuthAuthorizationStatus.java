package com.ragagent.mcp.oauth;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 授权状态（对照 Go internal/mcp/oauth_lifecycle.go:30-35 的
 * {@code OAuthAuthorizationStatus}）。
 *
 * <p><b>为什么要分"现在可用"与"过期但可刷新"两态</b>（Go 注释原文精神）：一行陈旧的
 * 数据库记录（加密列里还留着 token 字符串）绝不能被当作"已经授权成功"——过期行不算已授权。</p>
 *
 * <p>JSON 形态与 Go 逐字段一致（{@code expires_at} 带 omitempty），因为前端按这些字段渲染。</p>
 */
public record OAuthAuthorizationStatus(
        @JsonProperty("authorized") boolean authorized,
        @JsonProperty("state") String state,
        @JsonProperty("refresh_available") boolean refreshAvailable,
        @JsonProperty("expires_at") @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime expiresAt) {

    /** 对照 Go {@code oauthStateAuthorized}。 */
    public static final String STATE_AUTHORIZED = "authorized";
    /** 对照 Go {@code oauthStateRefreshable}。 */
    public static final String STATE_REFRESHABLE = "refreshable";
    /** 对照 Go {@code oauthStateReauthNeeded}。 */
    public static final String STATE_REAUTH_NEEDED = "reauth_required";

    /**
     * 对照 Go {@code tokenStatus}（oauth_lifecycle.go:95-114）。
     *
     * <p>逐条语义：
     * <ol>
     *   <li>无 token 或 access token 为空 → {@code reauth_required}，不算授权；</li>
     *   <li>{@code RefreshAvailable} 只取决于 refresh token 是否为空（与是否过期无关）；</li>
     *   <li><b>零值 expires_at 表示"不过期"</b>，直接算 authorized（对照 Go 的
     *       {@code ExpiresAt.IsZero()} 分支）——注意 @JsonInclude(NON_NULL) 与 Go 的
     *       {@code omitempty} 在此处语义一致：零值不输出。</li>
     *   <li>已过期但有 refresh token → {@code refreshable}；否则 {@code reauth_required}。</li>
     * </ol>
     */
    public static OAuthAuthorizationStatus of(OAuthToken row, OffsetDateTime now) {
        OAuthAuthorizationStatus status =
                new OAuthAuthorizationStatus(false, STATE_REAUTH_NEEDED, false, null);
        if (row == null || isBlank(row.accessToken())) {
            return status;
        }
        OffsetDateTime expiresAt = row.expiresAt();
        boolean hasExpiry = expiresAt != null;
        OAuthAuthorizationStatus base = new OAuthAuthorizationStatus(
                false, STATE_REAUTH_NEEDED, !isBlank(row.refreshToken()), hasExpiry ? expiresAt : null);
        if (!hasExpiry || expiresAt.isAfter(now)) {
            return new OAuthAuthorizationStatus(true, STATE_AUTHORIZED, base.refreshAvailable(),
                    base.expiresAt());
        }
        if (base.refreshAvailable()) {
            return new OAuthAuthorizationStatus(false, STATE_REFRESHABLE, true, base.expiresAt());
        }
        return base;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
