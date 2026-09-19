package com.ragagent.auth.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 邀请投影（对照 Go types/tenant_invitation.go {@code TenantInvitationResponse}，
 * 字段序 = Go struct 声明序；hydrate 逻辑在 controller，与本 DTO 无关）。
 *
 * <p>omitempty 全表：tenant_name / invitee_email / invitee_name / invited_by /
 * inviter_email / inviter_name / message / responded_at / invite_url /
 * is_share_link（false 省略）/ accepted_count（0 省略）。
 * id / tenant_id / invitee_user_id / role / status / expires_at / created_at 恒输出。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantInvitationResponse(
        @JsonProperty("id") long id,
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("tenant_name") String tenantName,
        @JsonProperty("invitee_user_id") String inviteeUserId,
        @JsonProperty("invitee_email") String inviteeEmail,
        @JsonProperty("invitee_name") String inviteeName,
        @JsonProperty("invited_by") String invitedBy,
        @JsonProperty("inviter_email") String inviterEmail,
        @JsonProperty("inviter_name") String inviterName,
        @JsonProperty("role") String role,
        @JsonProperty("status") String status,
        @JsonProperty("message") String message,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("responded_at") OffsetDateTime respondedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("invite_url") String inviteUrl,
        @JsonProperty("is_share_link") @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean isShareLink,
        @JsonProperty("accepted_count") @JsonInclude(JsonInclude.Include.NON_DEFAULT) int acceptedCount) {

    public TenantInvitationResponse {
        // Go omitempty 的空串语义
        tenantName = emptyToNull(tenantName);
        inviteeEmail = emptyToNull(inviteeEmail);
        inviteeName = emptyToNull(inviteeName);
        invitedBy = emptyToNull(invitedBy);
        inviterEmail = emptyToNull(inviterEmail);
        inviterName = emptyToNull(inviterName);
        message = emptyToNull(message);
        inviteUrl = emptyToNull(inviteUrl);
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
