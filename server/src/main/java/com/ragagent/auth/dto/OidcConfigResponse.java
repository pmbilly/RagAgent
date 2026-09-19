package com.ragagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * OIDC 配置响应（对照 Go types/user.go OIDCConfigResponse，200）。
 * 字段序 = Go struct 声明序：success, enabled, provider_display_name（omitempty）。
 */
@JsonPropertyOrder({"success", "enabled", "provider_display_name"})
public record OidcConfigResponse(
        @JsonProperty("success") boolean success,
        @JsonProperty("enabled") boolean enabled,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("provider_display_name") String providerDisplayName) {
}
