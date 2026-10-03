package com.ragagent.mcp.oauth;

import java.time.OffsetDateTime;


/**
 * 授权状态。
 *
 * <p><b>为什么要分"现在可用"与"过期但可刷新"两态</b>：一行陈旧的
 * 数据库记录（加密列里还留着 token 字符串）绝不能被当作"已经授权成功"——过期行不算已授权。</p>
 *
 * <p><b>JSON 形态</b>：键名＝record 组件名（camelCase），四个键**恒输出**——
 * {@code expiresAt} 为 null 表示"不过期"，不再是"键消失"式的省略。</p>
 */
public record OAuthAuthorizationStatus(
        boolean authorized,
        String state,
        boolean refreshAvailable,
        OffsetDateTime expiresAt) {

    public static final String STATE_AUTHORIZED = "authorized";
    public static final String STATE_REFRESHABLE = "refreshable";
    public static final String STATE_REAUTH_NEEDED = "reauth_required";

    /**
     * 由 token 行推导授权状态。
     *
     * <p>逐条语义：
     * <ol>
     *   <li>无 token 或 access token 为空 → {@code reauth_required}，不算授权；</li>
     *   <li>{@code RefreshAvailable} 只取决于 refresh token 是否为空（与是否过期无关）；</li>
     *   <li><b>expires_at 为 null 表示"不过期"</b>，直接算 authorized——
     *       输出侧 {@code expiresAt} 写 null。</li>
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
