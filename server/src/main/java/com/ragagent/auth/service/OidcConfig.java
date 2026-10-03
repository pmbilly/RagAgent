package com.ragagent.auth.service;

import java.util.ArrayList;
import com.ragagent.common.deployment.AppEnvLookup;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * OIDC 配置（env + 缺省值两层）。
 *
 * env：OIDC_AUTH_{ENABLE,ISSUER_URL,DISCOVERY_URL,
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

    /** env 读取：trim 后为空 = 未设置，值为 trim 后原文 */
    private static String env(String name) {
        String v = AppEnvLookup.get(name);
        return v == null ? "" : v.trim();
    }

    private static String envTrim(String name) {
        // 与 env() 同实现同来源（两处各自持一份裸读，容易只改一处）
        return env(name);
    }

    /** scopes 解析：逗号或空白分隔 */
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
