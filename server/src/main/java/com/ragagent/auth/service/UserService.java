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
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.dto.LoginRequest;
import com.ragagent.auth.dto.Membership;
import com.ragagent.auth.mapper.AuthTokenMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.settings.SystemSettingGateway;
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
    private final SystemSettingGateway settingService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(UserMapper userMapper,
                       AuthTokenMapper authTokenMapper,
                       TenantService tenantService,
                       TenantMemberService memberService,
                       JwtService jwtService,
                       SystemSettingGateway settingService) {
        this.userMapper = userMapper;
        this.authTokenMapper = authTokenMapper;
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.jwtService = jwtService;
        this.settingService = settingService;
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

    /**
     * 对照 GetUserByTenantID（repository/user.go L102-111）：租户内 created_at
     * 最早的用户（软删除过滤）；找不到返回 null。API Key 认证的
     * {@code attachAPIKeyAuthContext} 用它取租户首位用户身份，查不到时
     * 走 Go 的合成用户兜底 {@code system-<tenantId>}。
     */
    public User getUserByTenantIdFirst(long tenantId) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .isNull(User::getDeletedAt)
                .orderByAsc(User::getCreatedAt)
                .last("LIMIT 1"));
    }

    /** 对照 GetUserByID：软删除过滤；找不到返回 null */
    public User getUserById(String id) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, id)
                .isNull(User::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
     * 对照 GetUsersByIDs：批量按 id 查（map 形态，供成员/邀请列表 hydrate）。
     * GORM Find 的软删除过滤 → isNull(deleted_at)。
     */
    public java.util.Map<String, User> getUsersByIds(java.util.Collection<String> ids) {
        java.util.Map<String, User> out = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (User u : userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getId, ids)
                .isNull(User::getDeletedAt))) {
            out.put(u.getId(), u);
        }
        return out;
    }

    /** 对照 UpdateUser：整行写回。调用方负责设置 updatedAt（Go Save 自动刷）。 */
    public void updateUser(User user) {
        userMapper.updateById(user);
    }

    /**
     * 对照 UpdateUser 的 tenantID==0 分支（repository/user.go L114-126）：
     * Omit(tenant_id) Save + UpdateColumn(NULL)——⚠️ 直接写 0 会炸 FK fk_users_tenant。
     * register-by-invite 的 accept 失败修复路径专用。
     */
    public void restoreTenantless(User user) {
        user.setTenantId(null);
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getTenantId, null)
                .set(User::getUpdatedAt, user.getUpdatedAt()));
    }

    /** 对照 DeleteUser（register-by-invite 修复失败时的半建号清理）。 */
    public void deleteUser(String id) {
        userMapper.deleteById(id);
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
            // Go UpdateUser：tenant_id==0 时 Omit("tenant_id").Save + UpdateColumn(NULL)。
            // ⚠️ 两个坑：tenant_id=0 写进 UPDATE 会炸 FK fk_users_tenant；preferences 是
            // jsonb 列，两参 set 带 typeHandler 字段会 MyBatisSystemException（§9 三参规则）。
            // 只写 tenant_id=NULL——preferences.last_active_tenant_id 的存量偏差无害
            // （pref==home 与 home 走同一条解析路径，净行为一致；已记录为已知偏差）。
            userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                    .eq(User::getId, user.getId())
                    .set(User::getTenantId, null));
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

    // ── 注册（对照 Register，user.go L132-225） ────────────────────────────

    /** 对照 types.TenantProvisioningMode。 */
    public static final String PROVISIONING_CREATE_PERSONAL = "create_personal";
    public static final String PROVISIONING_TENANTLESS = "tenantless";

    /** 注册失败：message = Go error 原文（handler 包成 400 BadRequest）。 */
    public static final class RegistrationException extends RuntimeException {
        public RegistrationException(String message) {
            super(message);
        }
    }

    /**
     * 对照 Register。创建顺序：租户（含默认存储后端）→ 用户 → Owner 成员行；
     * 任一步失败按 Go 顺序回滚（删用户/删租户），错误消息逐字符对照。
     */
    public User register(String username, String email, String password, String provisioning) {
        if (username.isEmpty() || email.isEmpty() || password.isEmpty()) {
            throw new RegistrationException("username, email and password are required");
        }
        if (getUserByEmail(email) != null) {
            throw new RegistrationException("user with this email already exists");
        }
        if (getUserByUsername(username) != null) {
            throw new RegistrationException("user with this username already exists");
        }
        String hashedPassword = passwordEncoder.encode(password);

        String mode = provisioning;
        if (mode == null || mode.isEmpty()) {
            mode = PROVISIONING_CREATE_PERSONAL;
        }
        if (!PROVISIONING_CREATE_PERSONAL.equals(mode) && !PROVISIONING_TENANTLESS.equals(mode)) {
            throw new RegistrationException("invalid tenant provisioning mode \"" + mode + "\"");
        }

        Tenant createdTenant = null;
        if (PROVISIONING_CREATE_PERSONAL.equals(mode)) {
            Tenant tenant = new Tenant();
            // 对照：租户名再过一遍 SanitizeForLog（req.Username 已在 handler 消毒，幂等）
            tenant.setName(sanitizeForLog(username) + "'s Workspace");
            tenant.setDescription("Default workspace");
            tenant.setStatus("active");
            try {
                createdTenant = tenantService.createTenant(tenant);
            } catch (RuntimeException e) {
                log.error("Failed to create workspace: {}", e.toString());
                throw new RegistrationException("failed to create workspace");
            }
        }

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(hashedPassword);
        // 对照 CreateUser（repository/user.go L33-43）：tenant_id=0 → Omit → SQL NULL
        // （写 0 会违反 fk_users_tenant）；读回时由领域层归一为 0
        user.setTenantId(createdTenant != null ? createdTenant.getId() : null);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {
            if (createdTenant == null) {
                // tenantless：getter 会把 null 归一成 0，必须省略 tenant_id 列
                // （对照 Go Omit → SQL NULL；写 0 在真 PG 违反 fk_users_tenant）
                userMapper.insertTenantless(user);
            } else {
                userMapper.insert(user);
            }
        } catch (RuntimeException e) {
            log.error("Failed to create user: {}", e.toString());
            if (createdTenant != null) {
                try {
                    tenantService.deleteTenant(createdTenant.getId());
                } catch (RuntimeException rollbackErr) {
                    log.error("Failed to roll back tenant {} after user creation failure: {}",
                            createdTenant.getId(), rollbackErr.toString());
                }
            }
            throw new RegistrationException("failed to create user");
        }

        // 对照 EnsureOwner 引导：失败则删用户+删租户并报错
        if (createdTenant != null) {
            try {
                memberService.addMember(user.getId(), createdTenant.getId(), TenantRole.OWNER.value(), null);
            } catch (RuntimeException e) {
                log.error("Failed to create owner membership for user {} tenant {}: {}",
                        user.getId(), createdTenant.getId(), e.toString());
                try {
                    userMapper.deleteById(user.getId());
                } catch (RuntimeException ignored) {
                    // 尽力回滚，对照 Go `_ =`
                }
                try {
                    tenantService.deleteTenant(createdTenant.getId());
                } catch (RuntimeException ignored) {
                    // 同上
                }
                throw new RegistrationException("failed to finalise workspace ownership");
            }
        }
        return user;
    }

    /** 对照 GetUserByUsername：软删除过滤；找不到返回 null */
    public User getUserByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .isNull(User::getDeletedAt)
                .orderByAsc(User::getId)
                .last("LIMIT 1"));
    }

    /** 对照 GetCurrentUser（user.go L1451）：从请求上下文取用户（AuthFilter 已鉴权）。 */
    public User getCurrentUser() {
        String userId = TenantContext.currentUserId();
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        return getUserById(userId);
    }

    // ── 偏好（对照 UpdateUserPreferences，user.go L619-662） ───────────────

    /**
     * 对照 UpdateUserPreferences：PATCH 语义合并（null = 未携带，保持原值）。
     * last_active_tenant_id=0 → 清偏好。
     */
    public UserPreferences updateUserPreferences(String userId, UserPreferences patch) {
        User user = getUserById(userId);
        if (user == null) {
            throw new PreferencesException("record not found");
        }
        UserPreferences merged = user.getPreferences() != null ? user.getPreferences() : new UserPreferences();
        if (patch.getLastActiveTenantId() != null) {
            // 0 = 「忘掉我的偏好」哨兵；其余正值直接存（成员关系下次登录时校验）
            merged.setLastActiveTenantId(
                    patch.getLastActiveTenantId() == 0 ? null : patch.getLastActiveTenantId());
        }
        user.setPreferences(merged);
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(user);
        return merged;
    }

    /** 偏好更新失败：message = Go error 原文（handler 包成 400 BadRequest "Failed to update preferences"）。 */
    public static final class PreferencesException extends RuntimeException {
        public PreferencesException(String message) {
            super(message);
        }
    }

    // ── 修改密码（对照 ChangePassword，user.go L674-714） ──────────────────

    /** change-password 的失败分类（对照 handler/auth.go L777-798 的分派）。 */
    public enum ChangePasswordFailure {NONE, INVALID_OLD, SAME_AS_OLD, POLICY, OTHER}

    public static final class ChangePasswordException extends RuntimeException {
        private final ChangePasswordFailure kind;

        public ChangePasswordException(ChangePasswordFailure kind, String message) {
            super(message);
            this.kind = kind;
        }

        public ChangePasswordFailure kind() {
            return kind;
        }
    }

    /**
     * 对照 ChangePassword：先验旧密码（错误凭据不被策略错误掩盖）→ 新旧相同 →
     * 策略 → 落库 → 吊销全部会话。成功改密会清掉 OIDC 自动 provisioning 的
     * oidc_only_login 标记。
     */
    public void changePassword(String userId, String oldPassword, String newPassword) {
        User user = getUserById(userId);
        if (user == null) {
            throw new ChangePasswordException(ChangePasswordFailure.OTHER, "record not found");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ChangePasswordException(ChangePasswordFailure.INVALID_OLD, "invalid old password");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ChangePasswordException(ChangePasswordFailure.SAME_AS_OLD,
                    "new password must differ from current password");
        }
        String policyError = PasswordPolicy.validate(newPassword, complexPasswordEnabled());
        if (policyError != null) {
            throw new ChangePasswordException(ChangePasswordFailure.POLICY, policyError);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (user.getPreferences() != null
                && Boolean.TRUE.equals(user.getPreferences().getOidcOnlyLogin())) {
            user.getPreferences().setOidcOnlyLogin(false);
        }
        try {
            userMapper.updateById(user);
        } catch (RuntimeException e) {
            throw new ChangePasswordException(ChangePasswordFailure.OTHER, e.getMessage());
        }
        // 吊销全部会话：被偷的 token 不能活过密码轮换
        revokeTokensByUserId(userId);
    }

    /** 对照 RevokeTokensByUserID（repository/user.go L344-347）：is_revoked=true（GORM 同时刷 updated_at）。 */
    public void revokeTokensByUserId(String userId) {
        authTokenMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AuthToken>()
                .eq(AuthToken::getUserId, userId)
                .set(AuthToken::isIsRevoked, true)
                .set(AuthToken::getUpdatedAt, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    // ── W5a：logout / refresh / switch-tenant（对照 user.go L1127-1470） ────

    /**
     * 对照 Logout（L1429-1438）：从（可过期的）JWT 里解出 user_id，吊销该用户
     * 全部会话。access/refresh 两种 token 都收——客户端不必先 refresh 再登出。
     *
     * @throws LogoutException message = Go error 原文（handler 包成 500 "Logout failed"）
     */
    public void logout(String tokenString) {
        String userId = userIdFromSignedToken(tokenString);
        revokeTokensByUserId(userId);
    }

    /** 对照 userIDFromSignedToken（L1318-1339）：WithoutClaimsValidation → 过期可解。 */
    private String userIdFromSignedToken(String tokenString) {
        Claims claims;
        try {
            claims = jwtService.parseSignedAllowExpired(tokenString);
        } catch (TokenValidationException e) {
            throw new LogoutException("invalid token");
        }
        Object raw = claims.get("user_id");
        if (!(raw instanceof String userId) || goTrimSpace(userId).isEmpty()) {
            throw new LogoutException("invalid user ID in token");
        }
        return userId;
    }

    /** logout 的失败通道（对照 handler 的 NewInternalServerError("Logout failed").WithDetails(err)）。 */
    public static final class LogoutException extends RuntimeException {
        public LogoutException(String message) {
            super(message);
        }
    }

    /**
     * 对照 RefreshToken（L1372-1427）：校验 refresh JWT → 查 auth_tokens 撤销状态 →
     * 吊销旧 refresh → GenerateTokens（按 last-active 偏好解析目标空间）。
     *
     * @return {accessToken, newRefreshToken}
     * @throws RefreshTokenException message = Go error 原文（handler 包成 401 "Token refresh failed"）
     */
    public String[] refreshToken(String refreshTokenString) {
        Claims claims;
        try {
            claims = jwtService.parseSigned(refreshTokenString);
        } catch (TokenValidationException e) {
            // Go：jwt.Parse 默认校验 claims（含 exp）→ 失败即 "invalid refresh token"
            throw new RefreshTokenException("invalid refresh token");
        }
        if (!JwtService.isRefreshTokenClaims(claims)) {
            throw new RefreshTokenException("not a refresh token");
        }
        Object rawUserId = claims.get("user_id");
        if (!(rawUserId instanceof String userId)) {
            throw new RefreshTokenException("invalid user ID in token");
        }

        // 撤销状态检查（Go：record 缺失 / is_revoked → 同一文案）
        AuthToken record = authTokenMapper.selectOne(new LambdaQueryWrapper<AuthToken>()
                .eq(AuthToken::getToken, refreshTokenString)
                .last("LIMIT 1"));
        if (record == null || record.isIsRevoked()) {
            throw new RefreshTokenException("refresh token is revoked");
        }
        if (!TOKEN_TYPE_REFRESH.equals(record.getTokenType())) {
            throw new RefreshTokenException("not a refresh token");
        }

        User user = getUserById(userId);
        if (user == null) {
            // Go：GetUserByID 的 gorm 原文透传
            throw new RefreshTokenException("record not found");
        }

        // 吊销旧 refresh token（Go：Save 整行，GORM 自动刷 updated_at）
        record.setIsRevoked(true);
        record.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        authTokenMapper.updateById(record);

        // 对照 GenerateTokens（L861-866）：按 last-active 偏好解析空间
        return generateTokens(user);
    }

    /** refresh 的失败通道（对照 handler 的 NewUnauthorizedError("Token refresh failed")）。 */
    public static final class RefreshTokenException extends RuntimeException {
        public RefreshTokenException(String message) {
            super(message);
        }
    }

    /**
     * 对照 SwitchTenant（L1127-1211）：校验成员关系（跨空间超管豁免）→ 记
     * last-active 偏好（先于签发，失败中止）→ 签发新令牌对 → 尽力吊销旧 refresh。
     *
     * @throws SwitchTenantException message = Go error 原文（handler 包成
     *         403 "workspace switch failed"）
     */
    public LoginResult switchTenant(User user, long targetTenantId, String currentRefreshToken) {
        if (user == null) {
            throw new SwitchTenantException("user is required");
        }
        if (targetTenantId == 0) {
            throw new SwitchTenantException("target workspace ID is required");
        }

        // 校验成员关系，除非跨空间超管切出自己的 home
        if (!user.isCanAccessAllTenants() || targetTenantId == user.getTenantId()) {
            TenantMember member = memberService.getMembership(user.getId(), targetTenantId);
            if (member == null || !TenantMemberService.STATUS_ACTIVE.equals(member.getStatus())) {
                throw new SwitchTenantException("tenant membership not found");
            }
        }

        Tenant tenant = tenantService.getTenantById(targetTenantId);
        if (tenant == null) {
            // Go：GetTenantByID 的 "record not found" 被包进 "load target workspace: %w"
            throw new SwitchTenantException("load target workspace: record not found");
        }

        // 先落偏好再签发（Go 注释：200 响应必须同时是持久的落地偏好更新）
        try {
            UserPreferences patch = new UserPreferences();
            patch.setLastActiveTenantId(targetTenantId);
            UserPreferences merged = updateUserPreferences(user.getId(), patch);
            user.setPreferences(merged);
        } catch (RuntimeException e) {
            throw new SwitchTenantException("record last-active-tenant preference: " + e.getMessage());
        }

        String[] tokens;
        try {
            tokens = generateTokensForTenant(user, targetTenantId);
        } catch (RuntimeException e) {
            throw new SwitchTenantException("generate tokens: " + e.getMessage());
        }

        // 尽力吊销旧 refresh（失败只记日志，不拖垮切换）
        if (currentRefreshToken != null && !currentRefreshToken.isBlank()) {
            try {
                AuthToken record = authTokenMapper.selectOne(new LambdaQueryWrapper<AuthToken>()
                        .eq(AuthToken::getToken, currentRefreshToken)
                        .last("LIMIT 1"));
                if (record != null) {
                    record.setIsRevoked(true);
                    record.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                    authTokenMapper.updateById(record);
                }
            } catch (RuntimeException e) {
                log.warn("Failed to revoke previous refresh token during tenant switch: {}",
                        e.getMessage());
            }
        }

        List<Membership> memberships = buildLoginMemberships(user, tenant);
        return new LoginResult(true, "Workspace switched", user, tenant, memberships,
                tokens[0], tokens[1]);
    }

    /** switch-tenant 的失败通道（对照 handler 的 NewForbiddenError("workspace switch failed")）。 */
    public static final class SwitchTenantException extends RuntimeException {
        public SwitchTenantException(String message) {
            super(message);
        }
    }

    // ── Token 签发（对外） ─────────────────────────────────────────────────

    /** 对照 GenerateTokens（user.go L861-866）：按登录空间解析结果签发。 */
    public String[] generateTokens(User user) {
        return generateTokensForTenant(user, resolveLoginTenantId(user));
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    /** 对照 ResolveComplexPasswordEnabled（password_policy.go）：DB > ENV > false。 */
    public boolean complexPasswordEnabled() {
        return settingService.getBool(
                "auth.complex_password_enabled", "WEKNORA_AUTH_COMPLEX_PASSWORD_ENABLED", false);
    }

    /** 对照 secutils.SanitizeForLog：\n \r \t → 空格，其余控制字符（<32）剔除。 */
    public static String sanitizeForLog(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String s = input.replace("\n", " ").replace("\r", " ").replace("\t", " ");
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp >= 32) {
                b.appendCodePoint(cp);
            }
        });
        return b.toString();
    }

    /**
     * 对照 Go strings.TrimSpace（unicode.IsSpace）：Java trim/strip 都不含 U+00A0，
     * 这里按 unicode.IsSpace 的字符集显式处理（与 §9 的 `\s` 差异同族）。
     */
    public static String goTrimSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isGoSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isGoSpace(char c) {
        // Go unicode.IsSpace：'\t' '\n' '\v' '\f' '\r' ' ' U+0085 U+00A0 + Unicode 空白
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085' || c == '\u00A0';
    }
}
