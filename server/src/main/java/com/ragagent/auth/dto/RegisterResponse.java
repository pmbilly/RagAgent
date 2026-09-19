package com.ragagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.auth.domain.User;

/**
 * 注册成功响应（对照 Go types/user.go RegisterResponse，201）。
 * 字段序 = Go struct 声明序：success, message, user（tenant 字段 handler 不填，
 * omitempty 省略，Java 侧不建模）。
 */
@JsonPropertyOrder({"success", "message", "user"})
public record RegisterResponse(boolean success, String message, User user) {
}
