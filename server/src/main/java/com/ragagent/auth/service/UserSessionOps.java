package com.ragagent.auth.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.auth.domain.AuthToken;
import com.ragagent.auth.dto.Membership;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.service.UserService.RefreshTokenException;
import com.ragagent.auth.service.UserService.SwitchTenantException;
import com.ragagent.auth.service.UserService.LogoutException;
import io.jsonwebtoken.Claims;

/**
 * 用户会话令牌操作（logout / refresh / switch-tenant，
 * 自 {@code UserService} 外提）。
 *
 * <p>三个失败通道异常（LogoutException 等）是控制器捕获的公共 API，留在
 * {@link UserService}；本类经 {@code user} 回引用访问其协作面（令牌签发、偏好
 * 落库、membership 组装）与 mapper。</p>
 */
final class UserSessionOps {

    private final UserService svc;

    UserSessionOps(UserService svc) {
        this.svc = svc;
    }


    /**
     * 从（可过期的）JWT 里解出 user_id，吊销该用户
     * 全部会话。access/refresh 两种 token 都收——客户端不必先 refresh 再登出。
     *
     * @throws LogoutException message = 服务端错误原文（controller 包成 500 "Logout failed"）
     */
    public void logout(String tokenString) {
        String userId = userIdFromSignedToken(tokenString);
        svc.revokeTokensByUserId(userId);
    }

    /** 不做 claims 校验的解析：过期令牌也能解出 user_id。 */
    private String userIdFromSignedToken(String tokenString) {
        Claims claims;
        try {
            claims = svc.jwtService.parseSignedAllowExpired(tokenString);
        } catch (TokenValidationException e) {
            throw new UserService.LogoutException("invalid token");
        }
        Object raw = claims.get("user_id");
        if (!(raw instanceof String userId) || UserService.goTrimSpace(userId).isEmpty()) {
            throw new UserService.LogoutException("invalid user ID in token");
        }
        return userId;
    }


    /**
     * 校验 refresh JWT → 查 auth_tokens 撤销状态 →
     * 吊销旧 refresh → GenerateTokens（按 last-active 偏好解析目标空间）。
     *
     * @return {accessToken, newRefreshToken}
     * @throws RefreshTokenException message = 服务端错误原文（controller 包成 401 "Token refresh failed"）
     */
    public String[] refreshToken(String refreshTokenString) {
        Claims claims;
        try {
            claims = svc.jwtService.parseSigned(refreshTokenString);
        } catch (TokenValidationException e) {
            // claims 校验（含 exp 过期）失败 → "invalid refresh token"
            throw new UserService.RefreshTokenException("invalid refresh token");
        }
        if (!JwtService.isRefreshTokenClaims(claims)) {
            throw new UserService.RefreshTokenException("not a refresh token");
        }
        Object rawUserId = claims.get("user_id");
        if (!(rawUserId instanceof String userId)) {
            throw new UserService.RefreshTokenException("invalid user ID in token");
        }

        // 撤销状态检查（记录缺失 / 已吊销 → 同一文案）
        AuthToken record = svc.authTokenMapper.selectOne(new LambdaQueryWrapper<AuthToken>()
                .eq(AuthToken::getToken, refreshTokenString)
                .last("LIMIT 1"));
        if (record == null || record.isIsRevoked()) {
            throw new UserService.RefreshTokenException("refresh token is revoked");
        }
        if (!UserService.TOKEN_TYPE_REFRESH.equals(record.getTokenType())) {
            throw new UserService.RefreshTokenException("not a refresh token");
        }

        User u = svc.getUserById(userId);
        if (u == null) {
            // 用户不存在 → 透传 "record not found"
            throw new UserService.RefreshTokenException("record not found");
        }

        // 吊销旧 refresh token（整行写回，updated_at 一并刷新）
        record.setIsRevoked(true);
        record.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        svc.authTokenMapper.updateById(record);

        // 按 last-active 偏好解析空间后签发
        return svc.generateTokens(u);
    }


    /**
     * 校验成员关系（跨空间超管豁免）→ 记
     * last-active 偏好（先于签发，失败中止）→ 签发新令牌对 → 尽力吊销旧 refresh。
     *
     * @throws SwitchTenantException message = 服务端错误原文（controller 包成
     *         403 "workspace switch failed"）
     */
    public LoginResult switchTenant(User user, long targetTenantId, String currentRefreshToken) {
        if (user == null) {
            throw new UserService.SwitchTenantException("user is required");
        }
        if (targetTenantId == 0) {
            throw new UserService.SwitchTenantException("target workspace ID is required");
        }

        // 校验成员关系，除非跨空间超管切出自己的 home
        if (!user.isCanAccessAllTenants() || targetTenantId == user.getTenantId()) {
            TenantMember member = svc.memberService.getMembership(user.getId(), targetTenantId);
            if (member == null || !TenantMemberService.STATUS_ACTIVE.equals(member.getStatus())) {
                throw new UserService.SwitchTenantException("tenant membership not found");
            }
        }

        Tenant tenant = svc.tenantService.getTenantById(targetTenantId);
        if (tenant == null) {
            // "record not found" 包进 "load target workspace: " 前缀后抛出
            throw new UserService.SwitchTenantException("load target workspace: record not found");
        }

        // 先落偏好再签发（200 响应必须同时是持久的落地偏好更新）
        try {
            UserPreferences patch = new UserPreferences();
            patch.setLastActiveTenantId(targetTenantId);
            UserPreferences merged = svc.updateUserPreferences(user.getId(), patch);
            user.setPreferences(merged);
        } catch (RuntimeException e) {
            throw new UserService.SwitchTenantException("record last-active-tenant preference: " + e.getMessage());
        }

        String[] tokens;
        try {
            tokens = svc.generateTokensForTenant(user, targetTenantId);
        } catch (RuntimeException e) {
            throw new UserService.SwitchTenantException("generate tokens: " + e.getMessage());
        }

        // 尽力吊销旧 refresh（失败只记日志，不拖垮切换）
        if (currentRefreshToken != null && !currentRefreshToken.isBlank()) {
            try {
                AuthToken record = svc.authTokenMapper.selectOne(new LambdaQueryWrapper<AuthToken>()
                        .eq(AuthToken::getToken, currentRefreshToken)
                        .last("LIMIT 1"));
                if (record != null) {
                    record.setIsRevoked(true);
                    record.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    svc.authTokenMapper.updateById(record);
                }
            } catch (RuntimeException e) {
                UserService.log.warn("Failed to revoke previous refresh token during tenant switch: {}",
                        e.getMessage());
            }
        }

        List<Membership> memberships = svc.buildLoginMemberships(user, tenant);
        return new LoginResult(true, "Workspace switched", user, tenant, memberships,
                tokens[0], tokens[1]);
    }
}
