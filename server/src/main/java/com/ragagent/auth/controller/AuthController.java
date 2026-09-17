package com.ragagent.auth.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.auth.dto.AuthLoginResponse;
import com.ragagent.auth.dto.LoginRequest;
import com.ragagent.auth.dto.Membership;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.service.LoginResult;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/auth.go AuthHandler.Login（阶段 1：仅 /auth/login）。
 *
 * 错误路径（逐条对照）：
 * - 请求体为空/非法 JSON → 400 AppError(1010) "Invalid login parameters"，details=Go 原文
 *   （空 body 为 "EOF"；JSON 语法错误的 details 消息为已知差异，见约定 §8，golden 掩码比对）
 * - binding 校验失败（email required,email / password required,min=6）→ 同上 400，
 *   details 复刻 go-playground validator 的错误格式（Key: 'LoginRequest.Email' Error:...）
 * - service 层失败（success=false）→ 401 + AuthLoginResponse（memberships:null）
 * - 成功 → 200 + AuthLoginResponse
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

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthLoginResponse> login(@RequestBody(required = false) String rawBody) {
        log.info("Start user login");

        LoginRequest req = parseBody(rawBody);
        List<String> bindingErrors = validateBinding(req);
        if (!bindingErrors.isEmpty()) {
            throw invalidLoginParameters(String.join("\n", bindingErrors));
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

    /** 对照 ShouldBindJSON：空 body → details "EOF"；非法 JSON → details=解析器消息（已知差异） */
    private LoginRequest parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidLoginParameters("EOF");
        }
        try {
            // Go 的 json.Decoder 忽略未知字段：Jackson 默认同样忽略
            return MAPPER.readValue(rawBody, LoginRequest.class);
        } catch (Exception e) {
            throw invalidLoginParameters(e.getMessage());
        }
    }

    /**
     * 复刻 gin binding:"required,email"（Email）+ "required,min=6"（Password）。
     * go-playground validator 规则：字段按声明序校验；required 失败则该字段后续 tag 跳过。
     */
    private List<String> validateBinding(LoginRequest req) {
        List<String> errors = new ArrayList<>();
        String email = req.email();
        if (isBlank(email)) {
            errors.add(bindingError("Email", "required"));
        } else if (!GIN_EMAIL.matcher(email).matches()) {
            errors.add(bindingError("Email", "email"));
        }
        String password = req.password();
        if (isBlank(password)) {
            errors.add(bindingError("Password", "required"));
        } else if (password.length() < 6) {
            errors.add(bindingError("Password", "min"));
        }
        return errors;
    }

    /** go-playground validator 的单条错误格式（gin err.Error() 的组成单元） */
    private static String bindingError(String field, String tag) {
        return "Key: 'LoginRequest." + field + "' Error:Field validation for '" + field
                + "' failed on the '" + tag + "' tag";
    }

    /** 对照 handler：NewValidationError("Invalid login parameters").WithDetails(err.Error()) */
    private static BizException invalidLoginParameters(String details) {
        return new BizException(AppError.validation("Invalid login parameters").withDetails(details));
    }

    /** 对照 dto.NewAuthLoginResponse：active_tenant 按 membership 角色决定秘密字段是否输出 */
    private AuthLoginResponse toResponse(LoginResult r) {
        TenantResponse activeTenant = null;
        if (r.activeTenant() != null) {
            String role = membershipRoleForTenant(r.memberships(), r.activeTenant().getId());
            boolean includeSecrets = TenantRole.fromString(role).hasPermission(TenantRole.ADMIN);
            activeTenant = TenantResponse.from(r.activeTenant(), includeSecrets);
        }
        return new AuthLoginResponse(
                r.success(), r.message(), r.user(), activeTenant,
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
