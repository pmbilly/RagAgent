package com.ragagent.auth.dto;

import java.util.List;

import com.ragagent.auth.domain.User;

/**
 * 登录 / 自动设置 / 切换空间的 HTTP 响应体。
 *
 * <p>键名 = Java 字段名（camelCase）；可空字段显式 null。
 * 登录失败不走本类型——统一抛 AppError 信封（401）。</p>
 */
public record AuthLoginResponse(
        User user,
        TenantResponse activeTenant,
        List<Membership> memberships,
        String token,
        String refreshToken) {
}
