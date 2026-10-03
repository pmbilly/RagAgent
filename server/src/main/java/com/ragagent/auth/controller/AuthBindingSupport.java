package com.ragagent.auth.controller;

import java.util.List;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.dto.AuthLoginResponse;
import com.ragagent.auth.dto.TenantResponse;
import com.ragagent.auth.dto.Membership;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.tenant.TenantRole;

/**
 * AuthController 的绑定支持簇：登录响应组装、请求体宽松读（未知字段忽略）、
 * gin 风格 binding 文案（供保留手绑的 refresh / switch-tenant 端点使用）、AppError 工厂。
 */
final class AuthBindingSupport {

    private final AuthController service;

    AuthBindingSupport(AuthController service) {
        this.service = service;
    }

    AuthLoginResponse buildAuthLoginResponse(User user, Tenant activeTenant,
                                             List<Membership> memberships,
                                             String token, String refreshToken) {
        TenantResponse tenantResp = null;
        if (activeTenant != null) {
            String role = service.membershipRoleForTenant(memberships, activeTenant.getId());
            tenantResp = TenantResponse.from(activeTenant,
                    TenantRole.fromString(role).hasPermission(TenantRole.ADMIN));
        }
        return new AuthLoginResponse(user, tenantResp, memberships, token, refreshToken);
    }


    <T> T parseBody(String rawBody, Class<T> type, String message) {
        if (rawBody == null || rawBody.isBlank()) {
            throw invalidParams(message, "No content to map due to end-of-input");
        }
        try {
            // Jackson 默认忽略未知字段（与既定绑定语义一致）
            return AuthController.MAPPER.readValue(rawBody, type);
        } catch (Exception e) {
            throw invalidParams(message, e.getMessage());
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
