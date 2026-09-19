package com.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对照 Go config.yaml 的 tenant 配置段（internal/config/config.go TenantConfig）。
 *
 * - enableRbac：指针语义（Go *bool），null → 默认 true（对照 IsRBACEnforced：
 *   "operator did not opt out" 即为强制）。env: WEKNORA_TENANT_ENABLE_RBAC
 * - enableCrossTenantAccess：默认 false。env: WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS
 * - selfServiceCreationEnabled：指针语义，null → 默认 true
 *   （对照 TenantConfig.IsSelfServiceCreationEnabled）。
 *   env: WEKNORA_TENANT_SELF_SERVICE_CREATION_ENABLED
 *
 * MaxOwnedPerUser 随 tenants 模块翻译。
 */
@ConfigurationProperties(prefix = "weknora.tenant")
public record TenantProperties(
        Boolean enableRbac,
        boolean enableCrossTenantAccess,
        Boolean selfServiceCreationEnabled) {

    /** 对照 TenantConfig.IsRBACEnforced：null 视为 true */
    public boolean isRbacEnforced() {
        return enableRbac == null || enableRbac;
    }

    /** 对照 TenantConfig.IsSelfServiceCreationEnabled：null 视为 true */
    public boolean isSelfServiceCreationEnabled() {
        return selfServiceCreationEnabled == null || selfServiceCreationEnabled;
    }
}
