package com.ragagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * OIDC 授权地址响应（对照 Go types/user.go OIDCAuthURLResponse，200）。
 * 字段序 = Go struct 声明序：success, provider_display_name?, authorization_url?, state?
 * （后三者 omitempty）。Go 侧 Nonce 标 json:"-"（只进 HttpOnly cookie），不建模。
 */
@JsonPropertyOrder({"success", "provider_display_name", "authorization_url", "state"})
public record OidcAuthUrlResponse(
        @JsonProperty("success") boolean success,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("provider_display_name") String providerDisplayName,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("authorization_url") String authorizationUrl,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("state") String state) {
}
