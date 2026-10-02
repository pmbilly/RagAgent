package com.ragagent.auth.service;

import java.util.ArrayList;
import com.ragagent.config.AppEnvLookup;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * OIDC 配置（对照 Go internal/config/config.go 的 OIDCAuthConfig + env 覆盖段
 * L690-748 + 缺省值段 L736-748）。
 *
 * Go 配置链：config.yaml `oidc_auth` 段 → env `OIDC_AUTH_*` 覆盖 → 缺省值。
 * Java 仓尚无 config.yaml 加载器，故仅实现 env + 缺省值两层（dev 两侧 config.yaml
 * 均无 oidc_auth 段，行为等价；记 docs §9 deferral）。
 *
 * env 与 Go 完全同名：OIDC_AUTH_{ENABLE,ISSUER_URL,DISCOVERY_URL,
 * PROVIDER_DISPLAY_NAME,CLIENT_ID,CLIENT_SECRET,AUTHORIZATION_ENDPOINT,
 * TOKEN_ENDPOINT,USER_INFO_ENDPOINT,JWKS_URI,SCOPES} +
 * OIDC_USER_INFO_MAPPING_{USER_NAME,EMAIL}。
 * 缺省：ProviderDisplayName="OIDC"、Scopes=[openid,profile,email]、mapping name/email。
 */
@Component
public class OidcConfig {

    private final boolean enable;
    private final String issuerUrl;
    private final String discoveryUrl;
    private final String providerDisplayName;
    private final String clientId;
    private final String clientSecret;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String userInfoEndpoint;
    private final String jwksUri;
    private final List<String> scopes;
    private final String mappingUsername;
    private final String mappingEmail;

    public OidcConfig() {
        this.enable = "true".equalsIgnoreCase(envTrim("OIDC_AUTH_ENABLE"));
        this.issuerUrl = env("OIDC_AUTH_ISSUER_URL");
        this.discoveryUrl = env("OIDC_AUTH_DISCOVERY_URL");
        this.providerDisplayName = env("OIDC_AUTH_PROVIDER_DISPLAY_NAME");
        this.clientId = env("OIDC_AUTH_CLIENT_ID");
        this.clientSecret = env("OIDC_AUTH_CLIENT_SECRET");
        this.authorizationEndpoint = env("OIDC_AUTH_AUTHORIZATION_ENDPOINT");
        this.tokenEndpoint = env("OIDC_AUTH_TOKEN_ENDPOINT");
        this.userInfoEndpoint = env("OIDC_AUTH_USER_INFO_ENDPOINT");
        this.jwksUri = env("OIDC_AUTH_JWKS_URI");
        this.scopes = parseScopes(env("OIDC_AUTH_SCOPES"));
        this.mappingUsername = env("OIDC_USER_INFO_MAPPING_USER_NAME");
        this.mappingEmail = env("OIDC_USER_INFO_MAPPING_EMAIL");
    }

    /** 对照 env 读取：TrimSpace 后为空 = 未设置（value != "" 才覆盖），值为 trim 后原文 */
    private static String env(String name) {
        String v = AppEnvLookup.get(name);
        return v == null ? "" : v.trim();
    }

    private static String envTrim(String name) {
        // 与 env() 同实现同来源（B6 批 10）：两处各自持一份裸读，容易只改一处
        return env(name);
    }

    /** 对照 strings.Fields(strings.ReplaceAll(value, ",", " ")) */
    private static List<String> parseScopes(String raw) {
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) {
            return out;
        }
        for (String field : raw.replace(',', ' ').split("\\s+")) {
            if (!field.isEmpty()) {
                out.add(field);
            }
        }
        return out;
    }

    public boolean isEnable() {
        return enable;
    }

    public String getIssuerUrl() {
        return issuerUrl;
    }

    public String getDiscoveryUrl() {
        return discoveryUrl;
    }

    /** 对照缺省段：空 → "OIDC" */
    public String getProviderDisplayName() {
        return providerDisplayName.isEmpty() ? "OIDC" : providerDisplayName;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public String getUserInfoEndpoint() {
        return userInfoEndpoint;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    /** 对照缺省段：空 → [openid, profile, email] */
    public List<String> getScopes() {
        return scopes.isEmpty() ? List.of("openid", "profile", "email") : scopes;
    }

    /** 对照缺省段：空 → "name" */
    public String getMappingUsername() {
        return mappingUsername.isEmpty() ? "name" : mappingUsername;
    }

    /** 对照缺省段：空 → "email" */
    public String getMappingEmail() {
        return mappingEmail.isEmpty() ? "email" : mappingEmail;
    }
}
