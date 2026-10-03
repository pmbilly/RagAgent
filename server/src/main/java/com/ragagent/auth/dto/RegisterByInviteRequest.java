package com.ragagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /auth/register-by-invite 请求体。
 * 校验：token required；email required,email；username required；password required,min=6。
 */
public record RegisterByInviteRequest(
        @NotBlank(message = "token: 不能为空") String token,
        @NotBlank(message = "email: 不能为空") String email,
        @NotBlank(message = "username: 不能为空") String username,
        @NotBlank(message = "password: 不能为空") String password) {
}
