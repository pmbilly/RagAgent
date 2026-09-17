package com.ragagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 登录请求体（对照 Go types/user.go LoginRequest）。
 *
 * 校验规则（gin binding）：email required,email；password required,min=6。
 * Java 侧校验在 AuthController 手动执行并复刻 gin 的验证错误消息格式
 * （见 AuthController.validateLoginRequest），不使用 @Valid 默认消息。
 */
public record LoginRequest(
        @JsonProperty("email") String email,
        @JsonProperty("password") String password) {
}
