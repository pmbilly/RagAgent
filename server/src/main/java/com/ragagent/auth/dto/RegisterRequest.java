package com.ragagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 注册请求体（对照 Go types/user.go RegisterRequest）。
 * TenantProvisioning 是服务端控制的注册上下文，不从 JSON 读（Go json:"-"）。
 *
 * gin binding 校验（Java 在 AuthController 手动复刻）：
 * username required,min=2,max=50；email required,email；password required,min=6。
 */
public record RegisterRequest(
        @NotBlank(message = "username: 不能为空") String username,
        @NotBlank(message = "email: 不能为空") String email,
        @NotBlank(message = "password: 不能为空") String password) {
}
