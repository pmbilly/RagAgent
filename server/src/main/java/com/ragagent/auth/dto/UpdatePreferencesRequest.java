package com.ragagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PUT /auth/me/preferences 的请求体（对照 Go handler/auth.go updateMyPreferencesRequest）。
 * 字段为可空（Go 指针）：null = 请求未携带该键，保持原值（PATCH 语义）。
 *
 */
public record UpdatePreferencesRequest(
        @JsonProperty("last_active_tenant_id") Long lastActiveTenantId) {
}
