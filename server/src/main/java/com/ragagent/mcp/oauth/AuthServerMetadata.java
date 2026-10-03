package com.ragagent.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 8414 授权服务器元数据。
 *
 * <p>只保留 WeKnora 流程真正消费或校验的字段：
 * <ul>
 *   <li>{@code authorization_endpoint} / {@code token_endpoint} / {@code registration_endpoint}
 *       —— 三个流程端点；</li>
 *   <li>{@code issuer} 与其余 URL 型字段 —— 参与 {@code validateAuthServerMetadataURLs}
 *       的 scheme 校验（防恶意授权服务器塞 {@code javascript:} / {@code file:} 之类被反射进浏览器）。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AuthServerMetadata {

    @JsonProperty("issuer")
    private String issuer = "";
    @JsonProperty("authorization_endpoint")
    private String authorizationEndpoint = "";
    @JsonProperty("token_endpoint")
    private String tokenEndpoint = "";
    @JsonProperty("registration_endpoint")
    private String registrationEndpoint = "";
    @JsonProperty("jwks_uri")
    private String jwksUri = "";
    @JsonProperty("service_documentation")
    private String serviceDocumentation = "";
    @JsonProperty("op_policy_uri")
    private String opPolicyUri = "";
    @JsonProperty("op_tos_uri")
    private String opTosUri = "";
    @JsonProperty("revocation_endpoint")
    private String revocationEndpoint = "";
    @JsonProperty("introspection_endpoint")
    private String introspectionEndpoint = "";

    public AuthServerMetadata() {
    }

    public AuthServerMetadata(String issuer, String authorizationEndpoint, String tokenEndpoint,
                              String registrationEndpoint) {
        this.issuer = nz(issuer);
        this.authorizationEndpoint = nz(authorizationEndpoint);
        this.tokenEndpoint = nz(tokenEndpoint);
        this.registrationEndpoint = nz(registrationEndpoint);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    public String issuer() {
        return nz(issuer);
    }

    public String authorizationEndpoint() {
        return nz(authorizationEndpoint);
    }

    public String tokenEndpoint() {
        return nz(tokenEndpoint);
    }

    public String registrationEndpoint() {
        return nz(registrationEndpoint);
    }

    public String jwksUri() {
        return nz(jwksUri);
    }

    public String serviceDocumentation() {
        return nz(serviceDocumentation);
    }

    public String opPolicyUri() {
        return nz(opPolicyUri);
    }

    public String opTosUri() {
        return nz(opTosUri);
    }

    public String revocationEndpoint() {
        return nz(revocationEndpoint);
    }

    public String introspectionEndpoint() {
        return nz(introspectionEndpoint);
    }

    public void setIssuer(String v) {
        this.issuer = v;
    }

    public void setAuthorizationEndpoint(String v) {
        this.authorizationEndpoint = v;
    }

    public void setTokenEndpoint(String v) {
        this.tokenEndpoint = v;
    }

    public void setRegistrationEndpoint(String v) {
        this.registrationEndpoint = v;
    }
}
