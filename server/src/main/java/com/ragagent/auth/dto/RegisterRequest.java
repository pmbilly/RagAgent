package com.ragagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册请求体。
 * TenantProvisioning 是服务端控制的注册上下文，不从 JSON 读（不出现在请求体）。
 *
 * 校验（在 AuthController 手动执行）：
 * username required,min=2,max=50；email required,email；password required,min=6。
 */
public record RegisterRequest(
        @NotBlank(message = "username: 不能为空") String username,
        @NotBlank(message = "email: 不能为空") String email,
        @NotBlank(message = "password: 不能为空") String password) {
}
