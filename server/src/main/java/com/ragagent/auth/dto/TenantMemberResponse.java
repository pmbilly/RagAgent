package com.ragagent.auth.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET/POST /tenants/{id}/members 的成员投影
 * （对照 Go types/tenant_member.go {@code TenantMemberResponse}，字段序 = Go struct 声明序）。
 *
 * <p>Go omitempty 映射：avatar（空串省略）/ invited_by（nil 省略）→ NON_NULL，
 * 构造时把 Go 零值（"" / nil）归一为 null。joined_at 无 omitempty 恒输出。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantMemberResponse(
        @JsonProperty("user_id") String userId,
        @JsonProperty("email") String email,
        @JsonProperty("username") String username,
        @JsonProperty("avatar") @JsonInclude(JsonInclude.Include.NON_NULL) String avatar,
        @JsonProperty("role") String role,
        @JsonProperty("status") String status,
        @JsonProperty("invited_by") String invitedBy,
        @JsonProperty("joined_at") OffsetDateTime joinedAt) {

    public TenantMemberResponse {
        // Go omitempty 的空串语义：avatar="" 与成员行 invited_by=NULL 一样整体省略
        if (avatar != null && avatar.isEmpty()) {
            avatar = null;
        }
    }
}
