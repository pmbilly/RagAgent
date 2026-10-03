package com.ragagent.auth.dto;

/** GET /auth/config：注册模式与密码策略开关（公共读，无鉴权）。 */
/**
 * GET /auth/config 的裸响应（§2.1：裸对象、camelCase）。
 *
 * <p>{@code edition} 是部署版本信号（{@code weknora.system.edition}，缺省 {@code standard}）：
 * 前端据此决定是否尝试 {@code /auth/auto-setup}——该端点在非 lite 部署恒 403，
 * 盲打只会在控制台留一条会掩盖真 403 的噪音。</p>
 */
public record AuthConfigResponse(boolean complexPasswordEnabled, String registrationMode, String edition) {
}
