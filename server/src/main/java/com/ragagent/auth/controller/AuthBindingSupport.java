package com.ragagent.auth.controller;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.AuthLoginResponse;
import com.ragagent.auth.dto.LoginRequest;
import com.ragagent.auth.dto.RegisterByInviteRequest;
import com.ragagent.auth.dto.RegisterRequest;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.dto.Membership;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.tenant.TenantRole;

/**
 * AuthController 的绑定校验簇：gin binding 文案逐字对齐（email 正则/min/max/required）、
 * 请求体解析（parseBody）、AuthLoginResponse 组装、invalidParams 工厂。
 */
final class AuthBindingSupport {

    private final AuthController service;

    AuthBindingSupport(AuthController service) {
        this.service = service;
    }

    AuthLoginResponse buildAuthLoginResponse(boolean success, String message, User user,
                                                     Tenant activeTenant, List<Membership> memberships,
                                                     String token, String refreshToken) {
        TenantResponse tenantResp = null;
        if (activeTenant != null) {
            String role = service.membershipRoleForTenant(memberships, activeTenant.getId());
            tenantResp = TenantResponse.from(activeTenant,
                    TenantRole.fromString(role).hasPermission(TenantRole.ADMIN));
        }
        return new AuthLoginResponse(success, message, user, tenantResp,
                memberships, token, refreshToken);
    }


    <T> T parseBody(String rawBody, Class<T> type, String message) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidParams(message, "EOF");
        }
        try {
            // Go 的 json.Decoder 忽略未知字段：Jackson 默认同样忽略
            return AuthController.MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw invalidParams(message, e.getMessage());
        }
    }


    List<String> validateLoginBinding(LoginRequest req) {
        List<String> errors = new ArrayList<>();
        String email = req.email();
        if (service.isBlank(email)) {
            errors.add(bindingError("LoginRequest", "Email", "required"));
        } else if (!AuthController.GIN_EMAIL.matcher(email).matches()) {
            errors.add(bindingError("LoginRequest", "Email", "email"));
        }
        String password = req.password();
        if (service.isBlank(password)) {
            errors.add(bindingError("LoginRequest", "Password", "required"));
        } else if (password.length() < 6) {
            errors.add(bindingError("LoginRequest", "Password", "min"));
        }
        return errors;
    }


    List<String> validateRegisterBinding(RegisterRequest req) {
        List<String> errors = new ArrayList<>();
        String username = req.username();
        if (service.isBlank(username)) {
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


    List<String> validateRegisterByInviteBinding(RegisterByInviteRequest req) {
        List<String> errors = new ArrayList<>();
        if (service.isBlank(req.token())) {
            errors.add(bindingError("registerByInviteRequest", "Token", "required"));
        }
        addEmailBinding(errors, "registerByInviteRequest", req.email());
        if (service.isBlank(req.username())) {
            errors.add(bindingError("registerByInviteRequest", "Username", "required"));
        }
        addPasswordBinding(errors, "registerByInviteRequest", req.password());
        return errors;
    }


    void addEmailBinding(List<String> errors, String structName, String email) {
        if (service.isBlank(email)) {
            errors.add(bindingError(structName, "Email", "required"));
        } else if (!AuthController.GIN_EMAIL.matcher(email).matches()) {
            errors.add(bindingError(structName, "Email", "email"));
        }
    }


    void addPasswordBinding(List<String> errors, String structName, String password) {
        if (service.isBlank(password)) {
            errors.add(bindingError(structName, "Password", "required"));
        } else if (password.codePointCount(0, password.length()) < 6) {
            errors.add(bindingError(structName, "Password", "min"));
        }
    }


    static String bindingError(String structName, String field, String tag) {
        String key = structName == null || structName.isEmpty() ? field : structName + "." + field;
        return "Key: '" + key + "' Error:Field validation for '" + field
                + "' failed on the '" + tag + "' tag";
    }


    static BizException invalidParams(String message, String details) {
        return new BizException(AppError.validation(message).withDetails(details));
    }


}
