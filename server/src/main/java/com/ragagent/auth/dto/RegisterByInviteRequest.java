package com.ragagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /auth/register-by-invite 请求体
 * （对照 Go handler/auth_register_by_invite.go registerByInviteRequest）。
 * gin binding：token required；email required,email；username required；password required,min=6。
 */
public record RegisterByInviteRequest(
        @JsonProperty("token") String token,
        @JsonProperty("email") String email,
        @JsonProperty("username") String username,
        @JsonProperty("password") String password) {
}
