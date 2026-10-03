package com.ragagent.auth.dto;

/**
 * 登录响应的空间成员投影。
 * 字段序：tenant_id, tenant_name, role。
 */
public record Membership(
        Long tenantId,
        String tenantName,
        String role) {
}
