package com.ragagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 登录响应的空间成员投影（对照 Go types/tenant_member.go Membership）。
 * 字段序：tenant_id, tenant_name, role。
 */
@JsonPropertyOrder({"tenant_id", "tenant_name", "role"})
public record Membership(
        @JsonProperty("tenant_id") Long tenantId,
        @JsonProperty("tenant_name") String tenantName,
        @JsonProperty("role") String role) {
}
