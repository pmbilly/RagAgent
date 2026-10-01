package com.ragagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体（对照 Go types/user.go LoginRequest）。
 *
 * 校验规则（gin binding）：email required,email；password required,min=6。
 * Java 侧校验在 AuthController 手动执行并复刻 gin 的验证错误消息格式
 * （见 AuthController.validateLoginRequest），不使用 @Valid 默认消息。
 */
public record LoginRequest(
        @NotBlank(message = "email: 不能为空") String email,
        @NotBlank(message = "password: 不能为空") String password) {
}
