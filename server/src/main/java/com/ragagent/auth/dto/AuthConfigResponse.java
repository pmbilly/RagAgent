package com.ragagent.auth.dto;

/** GET /auth/config：注册模式与密码策略开关（公共读，无鉴权）。 */
public record AuthConfigResponse(boolean complexPasswordEnabled, String registrationMode) {
}
