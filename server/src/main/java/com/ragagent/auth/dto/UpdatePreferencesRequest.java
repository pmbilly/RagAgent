package com.ragagent.auth.dto;

/**
 * PUT /auth/me/preferences 的请求体。
 * 字段为可空：null = 请求未携带该键，保持原值（PATCH 语义）。
 *
 */
public record UpdatePreferencesRequest(
        Long lastActiveTenantId) {
}
