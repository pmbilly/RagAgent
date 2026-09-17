package com.ragagent.auth.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.auth.domain.User;

/**
 * 登录/切换空间的 HTTP 响应体（对照 Go handler/dto/auth.go AuthLoginResponse）。
 *
 * 字段序：success, message, user, active_tenant, memberships, token, refresh_token。
 * memberships 恒输出（Go 无 omitempty；失败时为 null，成功时为数组）。
 */
@JsonPropertyOrder({"success", "message", "user", "active_tenant", "memberships", "token", "refresh_token"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthLoginResponse(
        @JsonProperty("success") boolean success,
        @JsonProperty("message") String message,
        @JsonProperty("user") User user,
        @JsonProperty("active_tenant") TenantResponse activeTenant,
        /** 恒输出：失败 null（"memberships":null），成功为非空/空数组 */
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @JsonProperty("memberships") List<Membership> memberships,
        @JsonProperty("token") String token,
        @JsonProperty("refresh_token") String refreshToken) {
}
