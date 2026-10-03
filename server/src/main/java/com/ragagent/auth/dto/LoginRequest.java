package com.ragagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体。
 *
 * 校验规则：email required,email；password required,min=6。
 * 校验在 AuthController 手动执行并沿用既定的验证错误消息格式
 * （见 AuthController.validateLoginRequest），不使用 @Valid 默认消息。
 */
public record LoginRequest(
        @NotBlank(message = "email: 不能为空") String email,
        @NotBlank(message = "password: 不能为空") String password) {
}
