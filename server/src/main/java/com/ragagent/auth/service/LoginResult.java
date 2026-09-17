package com.ragagent.auth.service;

import java.util.List;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.Membership;

/**
 * 登录结果（对照 Go types.LoginResponse，service 层形态）。
 * 失败时 user/tenant/tokens 为 null、memberships 为 null
 * （Go 失败路径返回 &LoginResponse{Success, Message}，nil slice 序列化为 "memberships":null）。
 */
public record LoginResult(
        boolean success,
        String message,
        User user,
        Tenant activeTenant,
        List<Membership> memberships,
        String token,
        String refreshToken) {

    public static LoginResult failure(String message) {
        return new LoginResult(false, message, null, null, null, null, null);
    }

    public static LoginResult success(User user, Tenant activeTenant, List<Membership> memberships,
                                      String token, String refreshToken) {
        return new LoginResult(true, "Login successful", user, activeTenant, memberships, token, refreshToken);
    }
}
