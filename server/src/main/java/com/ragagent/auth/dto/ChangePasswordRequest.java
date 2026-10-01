package com.ragagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /auth/change-password 请求体（对照 Go handler/auth.go L751-754 的匿名 struct）。
 * gin binding：两个字段均 required；验证错误消息的 Key 不带 struct 名前缀
 * （Go 匿名 struct 的 namespace 为空，golden reg-chpw-binding 已锁定）。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "oldPassword: 不能为空") String oldPassword,
        @NotBlank(message = "newPassword: 不能为空") String newPassword) {
}
