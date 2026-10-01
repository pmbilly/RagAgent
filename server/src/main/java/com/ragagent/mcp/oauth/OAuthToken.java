package com.ragagent.mcp.oauth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 授权服务器返回的 token（对照 mcp-go {@code client/transport.Token}，oauth.go:75-89）。
 *
 * <p>用可变类而非 record：授权服务器可以省略 {@code refresh_token}（表示"沿用旧值"），
 * 也可以只给 {@code expires_in}（需要就地折算成绝对 {@code expires_at}），
 * 这两处都是 Go 里对同一个结构体的原地改写。</p>
 *
 * <p><b>⚠️ 这里的下划线键名是"外来的"，永久冻结</b>（§14.9p M5 判定）：本类唯一的 Jackson
 * 出口是 {@code OAuthTokenOps} 解析<b>授权服务器</b>的 RFC 6749 §5.1 token 响应
 * （{@code access_token}/{@code token_type}/{@code refresh_token}/{@code expires_in}/{@code scope}）；
 * 我方落库走 {@code McpOAuthToken} 实体的列映射（表里没有 jsonb 列），不存在"我们的键名"。
 * 同理冻结的还有 {@code AuthServerMetadata}（RFC 8414）、{@code OAuthProtectedResource}
 * （RFC 9728）、{@code OAuthError}（RFC 6749 §5.2）。**别把它们的键名"改回 camelCase"。**</p>
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class OAuthToken {

    @JsonProperty("access_token")
    private String accessToken = "";
    @JsonProperty("token_type")
    private String tokenType = "";
    @JsonProperty("refresh_token")
    private String refreshToken = "";
    @JsonProperty("expires_in")
    private long expiresIn;
    @JsonProperty("scope")
    private String scope = "";
    /** 绝对过期时刻；{@code null} = 不过期（Go 的零值 time.Time）。 */
    @JsonProperty("expires_at")
    private OffsetDateTime expiresAt;

    public OAuthToken() {
    }

    public OAuthToken(String accessToken, String refreshToken, String tokenType,
                      OffsetDateTime expiresAt) {
        this.accessToken = accessToken == null ? "" : accessToken;
        this.refreshToken = refreshToken == null ? "" : refreshToken;
        this.tokenType = tokenType == null ? "" : tokenType;
        this.expiresAt = expiresAt;
    }

    /** 对照 mcp-go {@code Token.IsExpired}：无过期时刻即视为永不过期。 */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt.toInstant());
    }

    public String accessToken() {
        return accessToken;
    }

    public void setAccessToken(String v) {
        this.accessToken = v == null ? "" : v;
    }

    public String tokenType() {
        return tokenType;
    }

    public void setTokenType(String v) {
        this.tokenType = v == null ? "" : v;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String v) {
        this.refreshToken = v == null ? "" : v;
    }

    public long expiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long v) {
        this.expiresIn = v;
    }

    public String scope() {
        return scope;
    }

    public void setScope(String v) {
        this.scope = v == null ? "" : v;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime v) {
        this.expiresAt = v;
    }

    /** 对照 Go {@code time.Now().Add(expiresIn * time.Second)}（服务端时钟、UTC 落库）。 */
    public void applyExpiresIn(long seconds) {
        if (seconds > 0) {
            this.expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(seconds);
        }
    }

    /** 测试/日志可见的浅拷贝（对照 Go 测试里的 {@code cloneOAuthToken}）。 */
    public OAuthToken copy() {
        OAuthToken c = new OAuthToken(accessToken, refreshToken, tokenType, expiresAt);
        c.expiresIn = expiresIn;
        c.scope = scope;
        return c;
    }
}
