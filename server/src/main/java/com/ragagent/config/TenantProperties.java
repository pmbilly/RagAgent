package com.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对照 Go config.yaml 的 tenant 配置段（internal/config/config.go TenantConfig）。
 *
 * - enableRbac：指针语义（Go *bool），null → 默认 true（对照 IsRBACEnforced：
 *   "operator did not opt out" 即为强制）。env: WEKNORA_TENANT_ENABLE_RBAC
 * - enableCrossTenantAccess：默认 false。env: WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS
 *
 * 阶段 1 仅消费这两个键；MaxOwnedPerUser / SelfServiceCreationEnabled 随 tenants 模块翻译。
 */
@ConfigurationProperties(prefix = "weknora.tenant")
public record TenantProperties(
        Boolean enableRbac,
        boolean enableCrossTenantAccess) {

    /** 对照 TenantConfig.IsRBACEnforced：null 视为 true */
    public boolean isRbacEnforced() {
        return enableRbac == null || enableRbac;
    }
}
