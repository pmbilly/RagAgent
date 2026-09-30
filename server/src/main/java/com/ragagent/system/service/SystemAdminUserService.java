package com.ragagent.system.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.service.AuditLogService;
import com.ragagent.auth.domain.AuthToken;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.mapper.AuthTokenMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.auth.service.PasswordPolicy;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.PlainErrorException;
import com.ragagent.common.security.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 系统管理员 P0 用户管理（对照 Go user.go 的 Register/AdminCreateUser/AdminResetPassword/
 * ListSystemAdmins + user repository 的 RevokeSystemAdmin，经 SystemHandler 暴露）。
 *
 * <p>异常语义逐分支对照 Go 的 handler（消息 = Go 原文）：</p>
 * <ul>
 *   <li>promote：user_id/email 双空 → 400 "Either user_id or email is required"；
 *       查无 → 404 "User not found"；已是管理员 → 幂等 200（审计 idempotent=true）。</li>
 *   <li>revoke：自撤 → 400 "Cannot revoke your own system admin privileges"；
 *       最后一名 → 400 "Cannot revoke the last remaining system administrator"；
 *       查无 → 404；非管理员 → 幂等 200（changed=false）。</li>
 *   <li>create：身份重复且完全一致 → 200 幂等返回既有行；仅部分冲突 → 409。</li>
 * </ul>
 *
 * <p><b>已知差异（Lite）</b>：Go 的 RevokeSystemAdmin 用 SELECT ... FOR UPDATE 事务
 * 防并发撤掉最后一名管理员；Java 顺序读-判-写（SystemAdmin 面无并发竞争窗）。
 * 密码生成用 SecureRandom + base64url（与 Go 的 OIDC 风格生成等价，输出长度可能不同——
 * 生成密码只出现一次且契约测试掩码）。</p>
 */
@Service
public class SystemAdminUserService {

    private static final Logger log = LoggerFactory.getLogger(SystemAdminUserService.class);

    /** Go ErrPasswordPolicy / ErrComplexPasswordPolicy 的原文（400 响应体）；实现已收拢到 PasswordPolicy。 */
    public static final String ERR_PASSWORD_POLICY = PasswordPolicy.ERR_PASSWORD_POLICY;
    public static final String ERR_COMPLEX_PASSWORD_POLICY = PasswordPolicy.ERR_COMPLEX_PASSWORD_POLICY;

    /** Go binding 的 email 正则（go-playground validator 同族的宽松域名校验）。 */
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile(
            "^[a-zA-Z0-9!#$%&'*+/=?^_`{|}~.\\-]+@[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?"
                    + "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$");

    static final String PASSWORD_SPECIAL_CHARS = "!@#$%^&*()_+-=[]{}|;:,.<>?";

    private final UserMapper userMapper;
    private final AuthTokenMapper authTokenMapper;
    private final TenantService tenantService;
    private final TenantMemberService memberService;
    private final AuditLogService auditService;
    private final SystemSettingService settingService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SystemAdminUserService(UserMapper userMapper,
                                  AuthTokenMapper authTokenMapper,
                                  TenantService tenantService,
                                  TenantMemberService memberService,
                                  AuditLogService auditService,
                                  SystemSettingService settingService) {
        this.userMapper = userMapper;
        this.authTokenMapper = authTokenMapper;
        this.tenantService = tenantService;
        this.memberService = memberService;
        this.auditService = auditService;
        this.settingService = settingService;
    }

    // ── 密码策略（对照 password_policy.go ValidatePasswordPolicy） ─────────

    public boolean complexPasswordEnabled() {
        return settingService.getBool(
                "auth.complex_password_enabled", "WEKNORA_AUTH_COMPLEX_PASSWORD_ENABLED", false);
    }

    /** @return null = 通过；否则 = 错误消息（Go 原文）。实现委托 PasswordPolicy.validate。 */
    public String validatePasswordPolicy(String password, boolean complexEnabled) {
        return PasswordPolicy.validate(password, complexEnabled);
    }

    /** 对照 generatePolicyCompliantPassword：生成直到过策略（复杂 → 16 位复杂密码）。 */
    public String generatePolicyCompliantPassword(boolean complexEnabled) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        while (true) {
            String password;
            if (complexEnabled) {
                StringBuilder sb = new StringBuilder();
                String all = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                        + PASSWORD_SPECIAL_CHARS;
                for (int i = 0; i < 16; i++) {
                    sb.append(all.charAt(random.nextInt(all.length())));
                }
                password = sb.toString();
            } else {
                byte[] bytes = new byte[24];
                random.nextBytes(bytes);
                password = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }
            if (validatePasswordPolicy(password, complexEnabled) == null) {
                return password;
            }
        }
    }

    // ── promote / revoke / list ───────────────────────────────────────────

    public User getUserById(String id) {
        return id == null || id.isEmpty() ? null
                : userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getId, id).isNull(User::getDeletedAt).last("LIMIT 1"));
    }

    public User getUserByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email).isNull(User::getDeletedAt).last("LIMIT 1"));
    }

    /** 对照 PromoteUserToSystemAdmin 的真实写分支（幂等分支由 controller 处理）。 */
    public User promote(User user) {
        user.setIsSystemAdmin(true);
        user.setUpdatedAt(OffsetDateTime.now());
        userMapper.updateById(user);
        return user;
    }

    /**
     * 对照 RevokeSystemAdmin（repo 事务的顺序化等价）：
     * @return user 行；self/last-admin/not-found 走 PlainErrorException（纯字符串错误体）。
     */
    public User revoke(String userId, String callerId) {
        if (userId.equals(callerId)) {
            throw PlainErrorException.badRequest(
                    "Cannot revoke your own system admin privileges");
        }
        User user = getUserById(userId);
        if (user == null) {
            throw PlainErrorException.notFound("User not found");
        }
        if (!user.isIsSystemAdmin()) {
            return user; // idempotent 分支（handler 落 changed=false 审计）
        }
        Long total = countSystemAdmins();
        if (total == null || total <= 1) {
            throw PlainErrorException.badRequest(
                    "Cannot revoke the last remaining system administrator");
        }
        user.setIsSystemAdmin(false);
        userMapper.updateById(user);
        return user;
    }

    /** 对照 ListSystemAdmins：count + created_at DESC, id ASC 分页。 */
    public record AdminPage(List<User> users, long total) {
    }

    public AdminPage listSystemAdmins(int offset, int limit) {
        Long total = countSystemAdmins();
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::isIsSystemAdmin, true)
                .isNull(User::getDeletedAt)
                .orderByDesc(User::getCreatedAt)
                .orderByAsc(User::getId)
                .last("LIMIT " + limit + " OFFSET " + Math.max(offset, 0)));
        return new AdminPage(users, total == null ? 0 : total);
    }

    private Long countSystemAdmins() {
        return userMapper.selectCount(new QueryWrapper<User>()
                .eq("is_system_admin", true)
                .isNull("deleted_at"));
    }

    // ── reset-password ────────────────────────────────────────────────────

    /** 对照 AdminResetPassword：换 hash + 吊销全部会话。 */
    public void adminResetPassword(User user, String newPassword) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(OffsetDateTime.now());
        userMapper.updateById(user);
        revokeTokensByUserId(user.getId());
    }

    private void revokeTokensByUserId(String userId) {
        try {
            authTokenMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AuthToken>()
                    .eq("user_id", userId)
                    .set("is_revoked", true));
        } catch (RuntimeException e) {
            log.warn("Failed to revoke tokens for user {}: {}", userId, e.toString());
        }
    }

    // ── create-user（对照 AdminCreateUser + Register） ─────────────────────

    public static final String ERR_USER_EMAIL_EXISTS = "user with this email already exists";
    public static final String ERR_USER_USERNAME_EXISTS = "user with this username already exists";
    public static final String ERR_USER_IDENTITY_CONFLICT =
            "email and username refer to conflicting existing identities";

    /** create 的结果三态（controller 折叠成 201/200/409）。 */
    public sealed interface CreateResult {
        record Created(User user, String generatedPassword) implements CreateResult {
        }

        record Idempotent(User user) implements CreateResult {
        }
    }

    /** 注册后的空间预配模式（Go types.TenantProvisioningMode）。 */
    public String resolveDefaultTenantMode() {
        String def = "create_personal";
        String mode = settingService.getString(
                "auth.default_tenant_mode", "WEKNORA_AUTH_DEFAULT_TENANT_MODE", def);
        return "tenantless".equals(mode) ? "tenantless" : "create_personal";
    }

    public CreateResult adminCreateUser(String rawUsername, String rawEmail,
                                        String password, boolean passwordPresent,
                                        String provisioning) {
        String username = LogSanitizer.sanitize(rawUsername == null ? "" : rawUsername.trim());
        String email = LogSanitizer.sanitize(rawEmail == null ? "" : rawEmail.trim());
        String effectivePassword = password;
        boolean generated = false;
        if (!passwordPresent) {
            effectivePassword = generatePolicyCompliantPassword(complexPasswordEnabled());
            generated = true;
        }
        String policyError = validatePasswordPolicy(effectivePassword, complexPasswordEnabled());
        if (policyError != null) {
            throw PlainErrorException.badRequest(policyError);
        }

        User existing;
        try {
            existing = register(username, email, effectivePassword, provisioning);
        } catch (DuplicateIdentityException dup) {
            User row = ERR_USER_EMAIL_EXISTS.equals(dup.message)
                    ? getUserByEmail(email)
                    : getByUsername(username);
            if (row != null && username.equals(row.getUsername()) && email.equals(row.getEmail())) {
                return new CreateResult.Idempotent(row);
            }
            throw new PlainErrorException(409, ERR_USER_IDENTITY_CONFLICT);
        }
        return generated ? new CreateResult.Created(existing, effectivePassword)
                : new CreateResult.Created(existing, "");
    }

    /** 部分身份冲突（email 与 username 各自命中不同行）的载体。 */
    public static final class DuplicateIdentityException extends RuntimeException {
        public final String message;

        public DuplicateIdentityException(String message) {
            super(message);
            this.message = message;
        }
    }

    public User getByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).isNull(User::getDeletedAt).last("LIMIT 1"));
    }

    /** 对照 Register（create_personal：建空间 + Owner 成员；失败回滚）。 */
    private User register(String username, String email, String hashedPassword, String provisioning) {
        if (getUserByEmail(email) != null) {
            throw new DuplicateIdentityException(ERR_USER_EMAIL_EXISTS);
        }
        if (getByUsername(username) != null) {
            throw new DuplicateIdentityException(ERR_USER_USERNAME_EXISTS);
        }
        Tenant createdTenant = null;
        if ("create_personal".equals(provisioning)) {
            createdTenant = new Tenant();
            createdTenant.setName(LogSanitizer.sanitize(username) + "'s Workspace");
            createdTenant.setDescription("Default workspace");
            createdTenant.setStatus("active");
            tenantService.createTenant(createdTenant);
        }
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(hashedPassword));
        user.setIsActive(true);
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        if (createdTenant != null) {
            user.setTenantId(createdTenant.getId());
        }
        try {
            userMapper.insert(user);
        } catch (RuntimeException e) {
            if (createdTenant != null) {
                try {
                    tenantService.deleteTenant(createdTenant.getId());
                } catch (RuntimeException rollbackErr) {
                    log.warn("Failed to roll back tenant {} after user creation failure: {}",
                            createdTenant.getId(), rollbackErr.toString());
                }
            }
            throw new IllegalStateException("failed to create user");
        }
        if (createdTenant != null) {
            try {
                memberService.addMember(user.getId(), createdTenant.getId(), "owner", "");
            } catch (RuntimeException e) {
                log.warn("Failed to create owner membership for user {} tenant {}: {}",
                        user.getId(), createdTenant.getId(), e.toString());
                userMapper.deleteById(user.getId());
                tenantService.deleteTenant(createdTenant.getId());
                throw new IllegalStateException("failed to finalise workspace ownership");
            }
        }
        return user;
    }

    // ── 审计（对照 SystemHandler.emitAdminAudit / emitAPIKeyAudit） ────────

    /** 对照 systemAuditActorRole：平台 API-Key 主体 → "platform_api_key"。 */
    public static String systemAuditActorRole() {
        var scope = com.ragagent.auth.apikey.domain.APIKeyScopeContext.current();
        if (scope != null && scope.isPlatform()) {
            return "platform_api_key";
        }
        return "system_admin";
    }

    /** 对照 emitAdminAudit：tenant_id=0 + target user。details 可空 → {}。 */
    public void emitAdminAudit(String action, User target, Map<String, Object> details) {
        if (auditService == null) {
            return;
        }
        var detailsNode = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                details == null ? Map.of() : details);
        AuditLog entry = new AuditLog();
        entry.setTenantId(0L);
        entry.setActorUserId(TenantContext.currentUserId() == null ? "" : TenantContext.currentUserId());
        entry.setActorRole(systemAuditActorRole());
        entry.setAction(action);
        entry.setTargetType("user");
        entry.setOutcome(AuditOutcome.SUCCESS);
        entry.setDetails(detailsNode);
        if (target != null) {
            entry.setTargetId(target.getId());
            entry.setTargetUserId(target.getId());
        }
        try {
            auditService.logBestEffort(entry);
        } catch (RuntimeException e) {
            log.warn("system admin audit write failed (ignored): {}", e.toString());
        }
    }

    /** go-playground 的 email 校验同族正则（宽松域名，允许无 TLD）。 */
    private static final java.util.regex.Pattern EMAIL_PATTERN = java.util.regex.Pattern.compile(
            "^[a-zA-Z0-9!#$%&'*+/=?^_`{|}~.\\-]+@[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?"
                    + "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$");

    /** binding 的 required,email 等价（fail → 通用 400 文案）。 */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }
}
