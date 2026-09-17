package com.ragagent.auth.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.auth.domain.AuthToken;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.LoginRequest;
import com.ragagent.auth.dto.Membership;
import com.ragagent.auth.mapper.AuthTokenMapper;
import com.ragagent.auth.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 对照 Go internal/application/service/user.go 的 UserService（阶段 1 子集）。
 *
 * 已翻译方法及其 Go 对照：
 * - login                 ← Login（L228）：失败不抛异常，编码在 LoginResult.success/message
 * - validateToken         ← ValidateToken（L1221）：JWT 校验 + DB 撤销检查
 * - buildLoginMemberships ← buildMembershipsForUser（L328）
 * - resolveLoginTenantID / homeOrFirstMembershipTenant / resolveFirstMembershipTenant
 *   / clearStaleHomeTenant / clearLastActiveTenantPreference ← L884-L1043
 * - generateTokensForTenant ← generateTokensForTenant（L1049）：签发 + 落 auth_tokens（错误忽略，同 Go `_ =`）
 *
 * bcrypt：Go bcrypt.DefaultCost=10 ↔ BCryptPasswordEncoder 默认 cost=10。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String TOKEN_TYPE_ACCESS = "access_token";
    private static final String TOKEN_TYPE_REFRESH = "refresh_token";

    private final UserMapper userMapper;
    private final AuthTokenMapper authTokenMapper;
    private final TenantService tenantService;
    private final TenantMemberService memberService;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserMapper userMapper,
                       AuthTokenMapper authTokenMapper,
                       TenantService tenantService,
                       TenantMemberService memberService,
                       JwtService jwtService) {
        this.userMapper = userMapper;
        this.authTokenMapper = authTokenMapper;
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.jwtService = jwtService;
    }

    // ── 登录 ─────────────────────────────────────────────────────────────

    /**
     * 对照 Go Login（L228-307）。失败路径返回 success=false 的 LoginResult
     * （Go 同样不返回 error），由 controller 决定 401。
     */
    public LoginResult login(LoginRequest req) {
        User user = getUserByEmail(req.email());
        if (user == null) {
            log.warn("User not found for email");
            return LoginResult.failure("Invalid email or password");
        }
        if (!user.isIsActive()) {
            log.warn("User account is disabled");
            return LoginResult.failure("Account is disabled");
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            log.warn("Password verification failed");
            return LoginResult.failure("Invalid email or password");
        }

        long resolvedTenantId = resolveLoginTenantId(user);
        String accessToken;
        String refreshToken;
        try {
            String[] tokens = generateTokensForTenant(user, resolvedTenantId);
            accessToken = tokens[0];
            refreshToken = tokens[1];
        } catch (RuntimeException e) {
            log.error("Failed to generate tokens: {}", e.toString());
            return LoginResult.failure("Login failed");
        }

        // resolvedTenantID == 0 是合法的 tenantless 身份，不算失败
        Tenant tenant = null;
        if (resolvedTenantId > 0) {
            tenant = tenantService.getTenantById(resolvedTenantId);
            if (tenant == null) {
                log.warn("Failed to get tenant info");
            }
        }

        List<Membership> memberships = buildLoginMemberships(user, tenant);
        return LoginResult.success(user, tenant, memberships, accessToken, refreshToken);
    }

    // ── Token 校验（AuthFilter 通道 2 入口） ──────────────────────────────

    /**
     * 对照 Go ValidateToken（L1221-1270）。
     *
     * @return 校验通过的用户与 JWT tenant_id claim
     * @throws TokenValidationException 任一校验失败（消息 = Go error 原文）
     */
    public ValidatedToken validateToken(String tokenString) {
        Claims claims = jwtService.parseSigned(tokenString);

        Object userIdClaim = claims.get("user_id");
        if (!(userIdClaim instanceof String userId)) {
            throw new TokenValidationException("invalid user ID in token");
        }
        if (JwtService.isRefreshTokenClaims(claims)) {
            throw new TokenValidationException("refresh token cannot be used as access token");
        }
        if (JwtService.isSandboxTerminalTicketClaims(claims)) {
            throw new TokenValidationException("terminal ticket cannot be used as access token");
        }

        AuthToken tokenRecord = authTokenMapper.selectOne(new LambdaQueryWrapper<AuthToken>()
                .eq(AuthToken::getToken, tokenString)
                .last("LIMIT 1"));
        if (tokenRecord == null || tokenRecord.isIsRevoked()) {
            throw new TokenValidationException("token is revoked");
        }
        if (TOKEN_TYPE_REFRESH.equals(tokenRecord.getTokenType())) {
            throw new TokenValidationException("refresh token cannot be used as access token");
        }

        User user = getUserById(userId);
        if (user == null) {
            // 对照 Go：GetUserByID err 原样上抛（GORM record not found）
            throw new TokenValidationException("record not found");
        }

        long homeTenantId = user.getTenantId() == null ? 0 : user.getTenantId();
        long activeTenantId = JwtService.tenantIdFromClaims(claims, homeTenantId);
        return new ValidatedToken(user, activeTenantId);
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    /** 对照 GetUserByEmail：软删除过滤；找不到返回 null */
    public User getUserByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)
                .isNull(User::getDeletedAt)
                .orderByAsc(User::getId)
                .last("LIMIT 1"));
    }

    /** 对照 GetUserByID：软删除过滤；找不到返回 null */
    public User getUserById(String id) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, id)
                .isNull(User::getDeletedAt)
                .last("LIMIT 1"));
    }

    // ── memberships 组装 ──────────────────────────────────────────────────

    /**
     * 对照 buildMembershipsForUser（L328-396）。
     * 错误不传播：membership 查不到 → 空数组（Go 返回 []types.Membership{}）。
     * 仅 status=active 且 tenant 行存在（名字非空白）的行进入响应。
     */
    public List<Membership> buildLoginMemberships(User user, Tenant activeTenant) {
        if (user == null) {
            return new ArrayList<>();
        }
        List<TenantMember> rows = memberService.listByUser(user.getId());
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> needsLookup = new ArrayList<>();
        for (TenantMember m : rows) {
            if (m == null || !TenantMemberService.STATUS_ACTIVE.equals(m.getStatus())) {
                continue;
            }
            if (activeTenant != null && m.getTenantId().equals(activeTenant.getId())) {
                continue;
            }
            needsLookup.add(m.getTenantId());
        }
        java.util.Map<Long, Tenant> tenantById = tenantService.getTenantsByIds(needsLookup);

        List<Membership> out = new ArrayList<>(rows.size());
        for (TenantMember m : rows) {
            if (m == null || !TenantMemberService.STATUS_ACTIVE.equals(m.getStatus())) {
                continue;
            }
            String name = "";
            if (activeTenant != null && m.getTenantId().equals(activeTenant.getId())) {
                name = activeTenant.getName();
            } else {
                Tenant t = tenantById.get(m.getTenantId());
                if (t != null) {
                    name = t.getName();
                }
            }
            // tenant 行已删除/名字空白 → 丢弃该 membership（对照 Go strings.TrimSpace(name) == ""）
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            out.add(new Membership(m.getTenantId(), name, m.getRole()));
        }
        return out;
    }

    // ── 登录空间解析（preference → home → 首个 membership） ────────────────

    /** 对照 resolveLoginTenantID（L884-927） */
    long resolveLoginTenantId(User user) {
        if (user == null) {
            return 0;
        }
        Long pref = user.getPreferences() != null
                ? user.getPreferences().getLastActiveTenantId() : null;
        long home = user.getTenantId() == null ? 0 : user.getTenantId();
        if (pref == null || pref == 0 || pref == home) {
            return homeOrFirstMembershipTenant(user);
        }
        long preferred = pref;

        if (tenantService.getTenantById(preferred) == null) {
            log.warn("resolveLoginTenantID: preferred tenant {} not loadable for user {}, "
                    + "clearing preference and falling back to home", preferred, user.getId());
            clearLastActiveTenantPreference(user);
            return homeOrFirstMembershipTenant(user);
        }

        if (!user.isCanAccessAllTenants()) {
            TenantMember member = memberService.getMembership(user.getId(), preferred);
            if (member == null || !TenantMemberService.STATUS_ACTIVE.equals(member.getStatus())) {
                log.warn("resolveLoginTenantID: user {} no longer has active membership in tenant {}, "
                        + "clearing preference and falling back to home", user.getId(), preferred);
                clearLastActiveTenantPreference(user);
                return homeOrFirstMembershipTenant(user);
            }
        }
        return preferred;
    }

    /** 对照 homeOrFirstMembershipTenant（L944-964） */
    private long homeOrFirstMembershipTenant(User user) {
        if (user == null) {
            return 0;
        }
        long home = user.getTenantId() == null ? 0 : user.getTenantId();
        if (home == 0) {
            return resolveFirstMembershipTenant(user);
        }
        if (user.isCanAccessAllTenants()) {
            return home;
        }
        TenantMember member = memberService.getMembership(user.getId(), home);
        if (member != null && TenantMemberService.STATUS_ACTIVE.equals(member.getStatus())) {
            return home;
        }
        log.warn("homeOrFirstMembershipTenant: user {} home tenant {} has no active membership, "
                + "clearing stale home and re-resolving", user.getId(), home);
        clearStaleHomeTenant(user);
        return resolveFirstMembershipTenant(user);
    }

    /** 对照 clearStaleHomeTenant（L970-987）：零值 users.tenant_id 并落库（失败仅记日志） */
    private void clearStaleHomeTenant(User user) {
        long staleHome = user.getTenantId() == null ? 0 : user.getTenantId();
        user.setTenantId(0L);
        if (user.getPreferences() != null
                && staleHome != 0
                && Long.valueOf(staleHome).equals(user.getPreferences().getLastActiveTenantId())) {
            user.getPreferences().setLastActiveTenantId(null);
        }
        try {
            userMapper.updateById(user);
        } catch (RuntimeException e) {
            log.warn("clearStaleHomeTenant: failed to persist cleared home for user {} (was tenant {}): {}",
                    user.getId(), staleHome, e.toString());
        }
    }

    /**
     * 对照 resolveFirstMembershipTenant（L995-1027）：tenantless 身份采用最早 active membership，
     * 并尽力持久化为 home（持久化失败不阻塞，仍返回该 membership 的 tenant）。
     */
    private long resolveFirstMembershipTenant(User user) {
        if (user == null) {
            return 0;
        }
        List<TenantMember> members = memberService.listByUser(user.getId());
        for (TenantMember member : members) {
            if (member == null
                    || member.getTenantId() == null || member.getTenantId() == 0
                    || !TenantMemberService.STATUS_ACTIVE.equals(member.getStatus())) {
                continue;
            }
            if (tenantService.getTenantById(member.getTenantId()) == null) {
                log.warn("resolveLoginTenantID: tenant {} for tenantless user {} is unavailable",
                        member.getTenantId(), user.getId());
                continue;
            }
            long tid = member.getTenantId();
            user.setTenantId(tid);
            try {
                userMapper.updateById(user);
            } catch (RuntimeException e) {
                log.warn("resolveLoginTenantID: failed to persist tenant {} for tenantless user {}: {}",
                        tid, user.getId(), e.toString());
                user.setTenantId(0L);
            }
            return tid;
        }
        return 0;
    }

    /** 对照 clearLastActiveTenantPreference（L1033-1043）：清偏好并落库（失败仅记日志） */
    private void clearLastActiveTenantPreference(User user) {
        if (user.getPreferences() != null) {
            user.getPreferences().setLastActiveTenantId(null);
        }
        try {
            userMapper.updateById(user);
        } catch (RuntimeException e) {
            log.warn("clearLastActiveTenantPreference: failed to persist cleared preference for user {}: {}",
                    user.getId(), e.toString());
        }
    }

    // ── Token 签发 ────────────────────────────────────────────────────────

    /**
     * 对照 generateTokensForTenant（L1049-1109）：
     * 签发 access(24h)/refresh(7d) 并各插一条 auth_tokens（插入错误忽略，同 Go `_ =`）。
     */
    String[] generateTokensForTenant(User user, long activeTenantId) {
        String accessToken = jwtService.generateAccessToken(user, activeTenantId);
        String refreshToken = jwtService.generateRefreshToken(user);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertToken(user.getId(), accessToken, TOKEN_TYPE_ACCESS, now.plusHours(24));
        insertToken(user.getId(), refreshToken, TOKEN_TYPE_REFRESH, now.plusDays(7));
        return new String[]{accessToken, refreshToken};
    }

    private void insertToken(String userId, String tokenValue, String tokenType, OffsetDateTime expiresAt) {
        try {
            AuthToken record = new AuthToken();
            record.setId(UUID.randomUUID().toString());
            record.setUserId(userId);
            record.setToken(tokenValue);
            record.setTokenType(tokenType);
            record.setExpiresAt(expiresAt);
            authTokenMapper.insert(record);
        } catch (RuntimeException e) {
            // 对照 Go `_ = s.tokenRepo.CreateToken(...)`：落库失败不使登录失败
            log.warn("Failed to persist {} (user={}): {}", tokenType, userId, e.toString());
        }
    }
}
