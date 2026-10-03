package com.ragagent.mcp.oauth;

import java.time.Duration;
import java.util.List;

/**
 * OAuth handler 的配置。
 *
 * <p>只保留 WeKnora 实际设置的字段：{@code AuthServerMetadataURL} 来自
 * {@code service.AuthConfig.AuthServerMetadataURL}（可空 → 走自动发现），
 * {@code TokenStore} 是按 principal 隔离的 {@link DbTokenStore}，
 * {@code HTTPClient} 换成 Java 侧的 SSRF 安全出站（{@link OAuthHttp}）。</p>
 */
public final class OAuthConfig {

    private String clientId = "";
    private String clientSecret = "";
    private String clientUri = "";
    private String redirectUri = "";
    private List<String> scopes = List.of();
    private OAuthTokenStore tokenStore;
    private String authServerMetadataUrl = "";
    /** RFC 9728 的 protected-resource metadata URL；WeKnora 不预置，恒为空（走 well-known 推导）。 */
    private String protectedResourceMetadataUrl = "";
    private boolean pkceEnabled = true;
    /** 出站 HTTP 超时（OAuth 流程统一 30s）。 */
    private Duration httpTimeout = Duration.ofSeconds(30);

    public String clientId() {
        return clientId;
    }

    public OAuthConfig clientId(String v) {
        this.clientId = nz(v);
        return this;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public OAuthConfig clientSecret(String v) {
        this.clientSecret = nz(v);
        return this;
    }

    public String clientUri() {
        return clientUri;
    }

    public OAuthConfig clientUri(String v) {
        this.clientUri = nz(v);
        return this;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public OAuthConfig redirectUri(String v) {
        this.redirectUri = nz(v);
        return this;
    }

    public List<String> scopes() {
        return scopes;
    }

    public OAuthConfig scopes(List<String> v) {
        this.scopes = v == null ? List.of() : List.copyOf(v);
        return this;
    }

    public OAuthTokenStore tokenStore() {
        return tokenStore;
    }

    public OAuthConfig tokenStore(OAuthTokenStore v) {
        this.tokenStore = v;
        return this;
    }

    public String authServerMetadataUrl() {
        return authServerMetadataUrl;
    }

    public OAuthConfig authServerMetadataUrl(String v) {
        this.authServerMetadataUrl = nz(v);
        return this;
    }

    public String protectedResourceMetadataUrl() {
        return protectedResourceMetadataUrl;
    }

    public void setProtectedResourceMetadataUrl(String v) {
        this.protectedResourceMetadataUrl = nz(v);
    }

    public boolean pkceEnabled() {
        return pkceEnabled;
    }

    public OAuthConfig pkceEnabled(boolean v) {
        this.pkceEnabled = v;
        return this;
    }

    public Duration httpTimeout() {
        return httpTimeout;
    }

    public OAuthConfig httpTimeout(Duration v) {
        this.httpTimeout = v == null ? Duration.ofSeconds(30) : v;
        return this;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
