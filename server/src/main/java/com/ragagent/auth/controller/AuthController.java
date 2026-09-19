package com.ragagent.auth.controller;

import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantInvitation;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.dto.AuthLoginResponse;
import com.ragagent.auth.dto.ChangePasswordRequest;
import com.ragagent.auth.dto.InvitationLookupRequest;
import com.ragagent.auth.dto.InvitationLookupResponse;
import com.ragagent.auth.dto.LoginRequest;
import com.ragagent.auth.dto.Membership;
import com.ragagent.auth.dto.RegisterByInviteRequest;
import com.ragagent.auth.dto.RegisterRequest;
import com.ragagent.auth.dto.RegisterResponse;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.dto.UpdatePreferencesRequest;
import com.ragagent.auth.dto.UserInfo;
import com.ragagent.auth.service.LoginResult;
import com.ragagent.auth.service.PasswordPolicy;
import com.ragagent.auth.service.TenantInvitationService;
import com.ragagent.auth.service.TenantRbacException;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.auth.service.ValidatedToken;
import com.ragagent.auth.service.TokenValidationException;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.config.TenantProperties;
import com.ragagent.system.service.SystemSettingRegistry;
import com.ragagent.system.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/auth.go + auth_register_by_invite.go 的 AuthHandler。
 *
 * 已落地端点（阶段 1 + 波 2 扫尾批 1）：
 * - POST /login             阶段 1
 * - POST /register          对照 auth.go L170：invite_only 门 → binding → 消毒 →
 *                           空值 → 密码策略 → Register（建四表）→ 201
 * - POST /auto-setup        对照 L895：非 lite 恒 403（lite 路径亦已翻译）
 * - POST /register-by-invite /invitations/lookup   对照 auth_register_by_invite.go
 * - GET  /config            对照 L821：注册模式 + 复杂密码开关（无鉴权，供前端）
 * - GET  /validate          对照 L984（其 400 分支在部署态被 AuthFilter 短路，仍按 Go 翻）
 * - GET  /me                对照 L613：嵌套 gin.H 每层字母序
 * - PUT  /me/preferences    对照 L700：PATCH 语义合并
 * - POST /change-password   对照 L746：错误分派到三种 details 令牌
 *
 * map 响应（gin.H 对照）：Go 按 encoding/json 字母序输出 → LinkedHashMap 按字母序构造。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /**
     * go-playground/validator v10 的 email 正则（validator 包 regexes.go emailRegexString），
     * 逐字符移植以保证 gin binding:"email" 的判定一致。
     */
    private static final Pattern GIN_EMAIL = Pattern.compile(
            "^(?:[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-zA-Z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]"
                    + "|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")"
                    + "@(?:(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+"
                    + "[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?"
                    + "|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}"
                    + "(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])"
                    + "|[a-zA-Z0-9-]*[a-zA-Z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]"
                    + "|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Go types.Membership 的角色常量（register-by-invite/auto-setup 的 memberships 组装用）。 */

    private final UserService userService;
    private final TenantService tenantService;
    private final TenantInvitationService invitationService;
    private final SystemSettingService settingService;
    private final TenantProperties tenantProperties;

    /** 对照 handler.Edition（构建期注入，默认 "standard"）。 */
    @Value("${weknora.system.edition:standard}")
    private String edition;

    /** 对照 cfg.Auth.RegistrationMode（Go config.yaml auth.registration_mode；缺省 self_serve）。 */
    @Value("${weknora.auth.registration-mode:}")
    private String configuredRegistrationMode;

    public AuthController(UserService userService,
                          TenantService tenantService,
                          TenantInvitationService invitationService,
                          SystemSettingService settingService,
                          TenantProperties tenantProperties) {
        this.userService = userService;
        this.tenantService = tenantService;
        this.invitationService = invitationService;
        this.settingService = settingService;
        this.tenantProperties = tenantProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthLoginResponse> login(@RequestBody(required = false) String rawBody) {
        log.info("Start user login");

        LoginRequest req = parseBody(rawBody, LoginRequest.class, "Invalid login parameters");
        List<String> bindingErrors = validateLoginBinding(req);
        if (!bindingErrors.isEmpty()) {
            throw invalidParams("Invalid login parameters", String.join("\n", bindingErrors));
        }
        if (isBlank(req.email()) || isBlank(req.password())) {
            // 对照 Go handler 的显式空值检查（binding required 之后的兜底）
            throw new BizException(AppError.validation("Email and password are required"));
        }

        LoginResult result = userService.login(req);
        if (!result.success()) {
            log.warn("Login failed: {}", result.message());
        } else {
            log.info("User logged in successfully, email: {}", result.user().getEmail());
        }
        AuthLoginResponse body = toResponse(result);
        return ResponseEntity.status(result.success() ? 200 : 401).body(body);
    }

    // ── POST /register（对照 auth.go L170-243） ────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody(required = false) String rawBody) {
        // 1) invite_only 门（DB system_settings > cfg > self_serve）
        if ("invite_only".equals(resolveRegistrationMode())) {
            throw new BizException(AppError.forbidden("Registration is invite-only"));
        }
        // 2) binding（对照 types.RegisterRequest 的校验标签）
        RegisterRequest req = parseBody(rawBody, RegisterRequest.class, "Invalid registration parameters");
        List<String> bindingErrors = validateRegisterBinding(req);
        if (!bindingErrors.isEmpty()) {
            throw invalidParams("Invalid registration parameters", String.join("\n", bindingErrors));
        }
        // 3) 消毒（密码刻意不消毒：SanitizeForLog 会改写控制字符，导致注册成功却登录不上）
        String username = UserService.sanitizeForLog(req.username());
        String email = UserService.sanitizeForLog(req.email());
        // 4) 必填检查（消毒后）
        if (username.isEmpty() || email.isEmpty() || isBlank(req.password())) {
            throw new BizException(AppError.validation("Username, email and password are required"));
        }
        // 5) 密码策略（运行时可调：DB system_settings 优先）
        String policyError = PasswordPolicy.validate(req.password(), userService.complexPasswordEnabled());
        if (policyError != null) {
            throw new BizException(AppError.validation(policyError));
        }
        // 6) 注册（租户供应模式服务端决定，不从请求读）
        User user;
        try {
            user = userService.register(username, email, req.password(), resolveDefaultTenantMode());
        } catch (UserService.RegistrationException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        return ResponseEntity.status(201)
                .body(new RegisterResponse(true, "Registration successful", user));
    }

    // ── POST /auto-setup（对照 auth.go L895-962） ──────────────────────────

    @PostMapping("/auto-setup")
    public ResponseEntity<AuthLoginResponse> autoSetup() {
        if (!"lite".equals(edition)) {
            throw new BizException(AppError.forbidden("auto-setup is only available in lite edition"));
        }
        final String defaultEmail = "admin@weknora.local";
        User user = userService.getUserByEmail(defaultEmail);
        if (user == null) {
            byte[] randomBytes = new byte[24];
            new SecureRandom().nextBytes(randomBytes);
            String randomPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            String randomUsername = "user_" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(java.util.Arrays.copyOf(randomBytes, 6));
            try {
                userService.register(randomUsername, defaultEmail, randomPassword, "");
            } catch (UserService.RegistrationException e) {
                throw new BizException(AppError.internal("auto-setup failed").withDetails(e.getMessage()));
            }
            user = userService.getUserByEmail(defaultEmail);
            if (user == null) {
                throw new BizException(
                        AppError.internal("auto-setup failed: user not found after registration"));
            }
        }
        String[] tokens = generateTokensOr500(user, "auto-setup failed");
        Tenant tenant = user.getTenantId() != null && user.getTenantId() > 0
                ? tenantService.getTenantById(user.getTenantId()) : null;
        List<Membership> memberships = List.of(new Membership(
                user.getTenantId() == null ? 0L : user.getTenantId(),
                tenantNameOrEmpty(tenant), TenantRole.OWNER.value()));
        return ResponseEntity.ok(buildAuthLoginResponse(true, "Auto-setup successful",
                user, tenant, memberships, tokens[0], tokens[1]));
    }

    // ── POST /register-by-invite + /invitations/lookup ─────────────────────
    // （对照 auth_register_by_invite.go；均不受 invite_only 门控——token 即授权）

    @PostMapping("/invitations/lookup")
    public ResponseEntity<Map<String, Object>> lookupInvitation(
            @RequestBody(required = false) String rawBody) {
        InvitationLookupRequest req = parseBody(rawBody, InvitationLookupRequest.class, "token is required");
        List<String> bindingErrors = new ArrayList<>();
        if (isBlank(req.token())) {
            bindingErrors.add(bindingError("invitationLookupRequest", "Token", "required"));
        }
        if (!bindingErrors.isEmpty()) {
            throw invalidParams("token is required", String.join("\n", bindingErrors));
        }
        String token = UserService.goTrimSpace(req.token());
        if (token.isEmpty()) {
            throw new BizException(AppError.validation("token is required"));
        }
        TenantInvitation inv = lookupInvitationOr410(token);

        Tenant tenant = null;
        try {
            tenant = tenantService.getTenantById(inv.getTenantId());
        } catch (RuntimeException e) {
            log.warn("invitations/lookup: tenant {} lookup failed: {}", inv.getTenantId(), e.toString());
        }
        InvitationLookupResponse resp = new InvitationLookupResponse(
                inv.getTenantId(),
                tenant != null ? tenant.getName() : null,
                inv.getRole(),
                // 对照 .UTC().Format("2006-01-02T15:04:05Z07:00")：UTC、无小数秒、Z 结尾
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        .format(inv.getExpiresAt().withOffsetSameInstant(ZoneOffset.UTC)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", resp);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/register-by-invite")
    public ResponseEntity<AuthLoginResponse> registerByInvite(
            @RequestBody(required = false) String rawBody) {
        RegisterByInviteRequest req = parseBody(rawBody, RegisterByInviteRequest.class,
                "Invalid registration parameters");
        List<String> bindingErrors = validateRegisterByInviteBinding(req);
        if (!bindingErrors.isEmpty()) {
            throw invalidParams("Invalid registration parameters", String.join("\n", bindingErrors));
        }
        String token = UserService.goTrimSpace(req.token());
        String email = UserService.goTrimSpace(req.email()).toLowerCase(Locale.ROOT);
        String username = UserService.goTrimSpace(req.username());
        if (token.isEmpty() || email.isEmpty() || username.isEmpty() || isBlank(req.password())) {
            throw new BizException(
                    AppError.validation("token, email, username and password are required"));
        }

        TenantInvitation inv = lookupInvitationOr410(token);

        // 已有账号 → 409（分享链接流程是「新建账号并加入」，不是「老号加入」）
        if (userService.getUserByEmail(email) != null) {
            throw new BizException(AppError.conflict(
                    "this email already has an account; please log in to join the workspace"));
        }
        String policyError = PasswordPolicy.validate(req.password(), userService.complexPasswordEnabled());
        if (policyError != null) {
            throw new BizException(AppError.validation(policyError));
        }

        User user;
        try {
            user = userService.register(username, email, req.password(),
                    UserService.PROVISIONING_TENANTLESS);
        } catch (UserService.RegistrationException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }

        // 受邀空间成为初始/主空间
        user.setTenantId(inv.getTenantId());
        user.setUpdatedAt(java.time.OffsetDateTime.now(ZoneOffset.UTC)); // Go Save 自动刷 updated_at
        try {
            userService.updateUser(user);
        } catch (RuntimeException e) {
            try {
                userService.deleteUser(user.getId());
            } catch (RuntimeException ignored) {
                // 尽力清理半建账号（对照 Go `_ =`）
            }
            throw new BizException(AppError.internal("failed to finalise invited account")
                    .withDetails(e.getMessage()));
        }

        try {
            invitationService.acceptByToken(token, user.getId());
        } catch (RuntimeException e) {
            // 竞态：lookup 与 accept 之间链接被撤。保留新号但还原成 tenantless；
            // 修复也失败则删掉半个身份。
            log.error("register-by-invite: accept failed for user {}: {}", user.getId(), e.toString());
            try {
                userService.restoreTenantless(user);
            } catch (RuntimeException rollbackErr) {
                log.error("register-by-invite: failed to restore tenantless user {}: {}",
                        user.getId(), rollbackErr.toString());
                try {
                    userService.deleteUser(user.getId());
                } catch (RuntimeException ignored) {
                    // 尽力
                }
            }
            throw new BizException(new AppError(1003,
                    "invitation link is no longer valid; please log in to your new account", null, 410));
        }

        String[] tokens = generateTokensOr500(user, "token generation failed");
        Tenant tenant = null;
        try {
            tenant = tenantService.getTenantById(inv.getTenantId());
        } catch (RuntimeException ignored) {
            // tenantNameOrEmpty 容忍 null（对照 Go `tenant, _ :=`）
        }
        List<Membership> memberships = List.of(
                new Membership(inv.getTenantId(), tenantNameOrEmpty(tenant), inv.getRole()));
        return ResponseEntity.status(201).body(buildAuthLoginResponse(true, "Registration successful",
                user, tenant, memberships, tokens[0], tokens[1]));
    }

    // ── GET /config（对照 auth.go L821-836，无鉴权公共读） ─────────────────

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getAuthConfig() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("complex_password_enabled", userService.complexPasswordEnabled());
        body.put("registration_mode", resolveRegistrationMode());
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ── GET /validate（对照 auth.go L984-1024） ────────────────────────────
    // 注意：部署态下无/坏 Authorization 头都先被 AuthFilter 以 401 纯文本拒绝，
    // 这里的 400/401 分支是对 Go handler 的忠实翻译（过滤器之后不可达）。

    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || authHeader.isEmpty()) {
            throw new BizException(AppError.validation("Authorization header is required"));
        }
        String[] tokenParts = authHeader.split(" ", -1); // 对照 strings.Split（全部空格都切）
        if (tokenParts.length != 2 || !"Bearer".equals(tokenParts[0])) {
            throw new BizException(AppError.validation("Invalid Authorization header format"));
        }
        ValidatedToken vt;
        try {
            vt = userService.validateToken(tokenParts[1]);
        } catch (TokenValidationException e) {
            throw new BizException(AppError.unauthorized("Token validation failed")
                    .withDetails(e.getMessage()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Token is valid");
        body.put("success", true);
        body.put("user", UserInfo.from(vt.user(), vt.user().isCanAccessAllTenants()));
        return ResponseEntity.ok(body);
    }

    // ── GET /me（对照 auth.go L613-669） ───────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        User user = currentUserOr401();
        // 取**活动**空间（AuthFilter 按 X-Tenant-ID/JWT claim 解析的），不是用户的主空间
        Long ctxTenant = TenantContext.currentTenantId();
        long activeTenantId = ctxTenant == null ? 0 : ctxTenant;
        if (activeTenantId == 0) {
            activeTenantId = user.getTenantId() == null ? 0 : user.getTenantId();
        }
        Tenant tenant = null;
        if (activeTenantId > 0) {
            try {
                tenant = tenantService.getTenantById(activeTenantId);
            } catch (RuntimeException e) {
                // 对照 Go：租户信息取不到不让请求失败
                log.warn("Failed to get tenant info for user {}, tenant ID {}: {}",
                        user.getEmail(), activeTenantId, e.toString());
            }
        }
        UserInfo userInfo = UserInfo.from(user,
                user.isCanAccessAllTenants() && tenantProperties.enableCrossTenantAccess());
        List<Membership> memberships = userService.buildLoginMemberships(user, tenant);
        boolean canCreateTenant = user.isCanAccessAllTenants()
                || resolveTenantSelfServiceCreationEnabled();
        boolean autoAcceptInvitation = settingService.getBool("tenant.auto_accept_invitation",
                "WEKNORA_TENANT_AUTO_ACCEPT_INVITATION", false);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("auto_accept_invitation", autoAcceptInvitation);
        capabilities.put("can_create_tenant", canCreateTenant);
        Map<String, Object> preferenceDefaults = new LinkedHashMap<>();
        preferenceDefaults.put("browser_search_instructions",
                PasswordPolicy.DEFAULT_BROWSER_SEARCH_INSTRUCTIONS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capabilities", capabilities);
        data.put("memberships", memberships);
        data.put("preference_defaults", preferenceDefaults);
        data.put("tenant", tenant == null ? null : TenantResponse.from(tenant, contextRoleHasAdmin()));
        data.put("tenant_required", tenant == null);
        data.put("user", userInfo);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ── PUT /me/preferences（对照 auth.go L700-733） ───────────────────────

    @PutMapping("/me/preferences")
    public ResponseEntity<Map<String, Object>> updateMyPreferences(
            @RequestBody(required = false) String rawBody) {
        User user = currentUserOr401();
        UpdatePreferencesRequest req = parseBody(rawBody, UpdatePreferencesRequest.class,
                "Invalid preferences request");
        // binding：browser_search_instructions omitempty,max=4000（rune 计）
        if (req.browserSearchInstructions() != null
                && req.browserSearchInstructions()
                        .codePointCount(0, req.browserSearchInstructions().length())
                        > PasswordPolicy.MAX_BROWSER_SEARCH_INSTRUCTIONS_LENGTH) {
            throw invalidParams("Invalid preferences request",
                    bindingError("updateMyPreferencesRequest", "BrowserSearchInstructions", "max"));
        }
        UserPreferences patch = new UserPreferences();
        patch.setBrowserSearchInstructions(req.browserSearchInstructions());
        patch.setLastActiveTenantId(req.lastActiveTenantId());
        UserPreferences prefs;
        try {
            prefs = userService.updateUserPreferences(user.getId(), patch);
        } catch (UserService.PreferencesException e) {
            throw new BizException(AppError.badRequest("Failed to update preferences")
                    .withDetails(e.getMessage()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", prefs);
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ── POST /change-password（对照 auth.go L746-806） ─────────────────────

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody(required = false) String rawBody) {
        ChangePasswordRequest req = parseBody(rawBody, ChangePasswordRequest.class,
                "Invalid password change request");
        List<String> bindingErrors = new ArrayList<>();
        // Go 侧是匿名 struct：验证错误 Key 无 struct 名前缀（golden reg-chpw-binding 锁定）
        if (isBlank(req.oldPassword())) {
            bindingErrors.add(bindingError("", "OldPassword", "required"));
        }
        if (isBlank(req.newPassword())) {
            bindingErrors.add(bindingError("", "NewPassword", "required"));
        }
        if (!bindingErrors.isEmpty()) {
            throw invalidParams("Invalid password change request", String.join("\n", bindingErrors));
        }
        User user = currentUserOr401();
        try {
            userService.changePassword(user.getId(), req.oldPassword(), req.newPassword());
        } catch (UserService.ChangePasswordException e) {
            switch (e.kind()) {
                case POLICY -> throw new BizException(AppError.validation("Password policy violation")
                        .withDetails(PasswordPolicy.DETAIL_PASSWORD_POLICY));
                case INVALID_OLD -> throw new BizException(
                        AppError.badRequest("Current password is incorrect")
                                .withDetails(PasswordPolicy.DETAIL_INVALID_OLD_PASSWORD));
                case SAME_AS_OLD -> throw new BizException(
                        AppError.validation("New password must differ from current password")
                                .withDetails(PasswordPolicy.DETAIL_SAME_PASSWORD));
                default -> throw new BizException(AppError.badRequest("Password change failed")
                        .withDetails(e.getMessage()));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Password changed successfully");
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    // ── 策略解析（对照 auth.go L85-157 + tenant_policy.go） ────────────────

    /** 对照 resolveRegistrationMode：DB system_settings > cfg > self_serve */
    private String resolveRegistrationMode() {
        String def = configuredRegistrationMode == null || configuredRegistrationMode.isBlank()
                ? SystemSettingRegistry.AUTH_REGISTRATION_MODE_DEFAULT
                : configuredRegistrationMode.trim();
        return settingService.getString("auth.registration_mode", "", def);
    }

    /** 对照 resolveDefaultTenantMode：DB > ENV > cfg（cfg 兜底 create_personal） */
    private String resolveDefaultTenantMode() {
        String mode = settingService.getString("auth.default_tenant_mode",
                "WEKNORA_AUTH_DEFAULT_TENANT_MODE", "create_personal");
        return "tenantless".equals(mode)
                ? UserService.PROVISIONING_TENANTLESS : UserService.PROVISIONING_CREATE_PERSONAL;
    }

    /** 对照 resolveTenantSelfServiceCreationEnabled（tenant_policy.go）：DB > ENV > cfg(默认 true) */
    private boolean resolveTenantSelfServiceCreationEnabled() {
        return settingService.getBool("tenant.self_service_creation_enabled",
                "WEKNORA_TENANT_SELF_SERVICE_CREATION_ENABLED",
                tenantProperties.isSelfServiceCreationEnabled());
    }

    // ── 共享辅助 ──────────────────────────────────────────────────────────

    private User currentUserOr401() {
        User user = userService.getCurrentUser();
        if (user == null) {
            throw new BizException(AppError.unauthorized("Failed to get user information")
                    .withDetails("user not found in context"));
        }
        return user;
    }

    private TenantInvitation lookupInvitationOr410(String token) {
        try {
            return invitationService.lookupByToken(token);
        } catch (TenantRbacException e) {
            // 把「未知/过期/撤销」坍缩成 410，不泄露被盗 token 曾占用哪个槽位
            throw new BizException(new AppError(1003,
                    "invitation link is invalid or has been revoked", null, 410));
        }
    }

    private String[] generateTokensOr500(User user, String message) {
        try {
            return userService.generateTokens(user);
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(message).withDetails(e.getMessage()));
        }
    }

    /** 对照 tenantNameOrEmpty（auth.go L964-972） */
    private static String tenantNameOrEmpty(Tenant t) {
        return t == null || t.getName() == null ? "" : t.getName();
    }

    /** 对照 NewTenantResponse(ctx, tenant)：includeSecrets 由请求上下文的角色决定 */
    private boolean contextRoleHasAdmin() {
        String role = TenantContext.currentRole();
        return TenantRole.fromString(role == null ? "" : role).hasPermission(TenantRole.ADMIN);
    }

    /** 对照 dto.NewAuthLoginResponse：active_tenant 按 membership 角色决定秘密字段是否输出 */
    private AuthLoginResponse buildAuthLoginResponse(boolean success, String message, User user,
                                                     Tenant activeTenant, List<Membership> memberships,
                                                     String token, String refreshToken) {
        TenantResponse tenantResp = null;
        if (activeTenant != null) {
            String role = membershipRoleForTenant(memberships, activeTenant.getId());
            tenantResp = TenantResponse.from(activeTenant,
                    TenantRole.fromString(role).hasPermission(TenantRole.ADMIN));
        }
        return new AuthLoginResponse(success, message, user, tenantResp,
                memberships, token, refreshToken);
    }

    /** 对照 ShouldBindJSON：空 body → details "EOF"；非法 JSON → details=解析器消息（已知差异） */
    private <T> T parseBody(String rawBody, Class<T> type, String message) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidParams(message, "EOF");
        }
        try {
            // Go 的 json.Decoder 忽略未知字段：Jackson 默认同样忽略
            return MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw invalidParams(message, e.getMessage());
        }
    }

    /** 复刻 gin binding:"required,email"（Email）+ "required,min=6"（Password） */
    private List<String> validateLoginBinding(LoginRequest req) {
        List<String> errors = new ArrayList<>();
        String email = req.email();
        if (isBlank(email)) {
            errors.add(bindingError("LoginRequest", "Email", "required"));
        } else if (!GIN_EMAIL.matcher(email).matches()) {
            errors.add(bindingError("LoginRequest", "Email", "email"));
        }
        String password = req.password();
        if (isBlank(password)) {
            errors.add(bindingError("LoginRequest", "Password", "required"));
        } else if (password.length() < 6) {
            errors.add(bindingError("LoginRequest", "Password", "min"));
        }
        return errors;
    }

    /** 复刻 RegisterRequest 的 binding：username required,min=2,max=50；email required,email；password required,min=6 */
    private List<String> validateRegisterBinding(RegisterRequest req) {
        List<String> errors = new ArrayList<>();
        String username = req.username();
        if (isBlank(username)) {
            errors.add(bindingError("RegisterRequest", "Username", "required"));
        } else {
            // go-playground 的 min/max 对 string 按 rune 计；单字段遇首个失败即停
            int len = username.codePointCount(0, username.length());
            if (len < 2) {
                errors.add(bindingError("RegisterRequest", "Username", "min"));
            } else if (len > 50) {
                errors.add(bindingError("RegisterRequest", "Username", "max"));
            }
        }
        addEmailBinding(errors, "RegisterRequest", req.email());
        addPasswordBinding(errors, "RegisterRequest", req.password());
        return errors;
    }

    /** 复刻 registerByInviteRequest 的 binding：token required；email required,email；username required；password required,min=6 */
    private List<String> validateRegisterByInviteBinding(RegisterByInviteRequest req) {
        List<String> errors = new ArrayList<>();
        if (isBlank(req.token())) {
            errors.add(bindingError("registerByInviteRequest", "Token", "required"));
        }
        addEmailBinding(errors, "registerByInviteRequest", req.email());
        if (isBlank(req.username())) {
            errors.add(bindingError("registerByInviteRequest", "Username", "required"));
        }
        addPasswordBinding(errors, "registerByInviteRequest", req.password());
        return errors;
    }

    private void addEmailBinding(List<String> errors, String structName, String email) {
        if (isBlank(email)) {
            errors.add(bindingError(structName, "Email", "required"));
        } else if (!GIN_EMAIL.matcher(email).matches()) {
            errors.add(bindingError(structName, "Email", "email"));
        }
    }

    private void addPasswordBinding(List<String> errors, String structName, String password) {
        if (isBlank(password)) {
            errors.add(bindingError(structName, "Password", "required"));
        } else if (password.codePointCount(0, password.length()) < 6) {
            errors.add(bindingError(structName, "Password", "min"));
        }
    }

    /**
     * go-playground validator 的单条错误格式（gin err.Error() 的组成单元）。
     * structName 为空 = Go 匿名 struct（Key 无前缀，对照 reg-chpw-binding golden）。
     */
    private static String bindingError(String structName, String field, String tag) {
        String key = structName == null || structName.isEmpty() ? field : structName + "." + field;
        return "Key: '" + key + "' Error:Field validation for '" + field
                + "' failed on the '" + tag + "' tag";
    }

    /** 对照 handler：NewValidationError(message).WithDetails(err.Error()) */
    private static BizException invalidParams(String message, String details) {
        return new BizException(AppError.validation(message).withDetails(details));
    }

    /** 对照 dto.NewAuthLoginResponse（login 用） */
    private AuthLoginResponse toResponse(LoginResult r) {
        return buildAuthLoginResponse(r.success(), r.message(), r.user(), r.activeTenant(),
                r.memberships(), r.token(), r.refreshToken());
    }

    /** 对照 dto.membershipRoleForTenant */
    private static String membershipRoleForTenant(List<Membership> memberships, long tenantId) {
        if (memberships == null) {
            return "";
        }
        for (Membership m : memberships) {
            if (m != null && m.tenantId() == tenantId && TenantRole.fromString(m.role()).isValid()) {
                return m.role();
            }
        }
        return "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
