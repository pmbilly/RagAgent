package com.ragagent.auth.dto;

import java.time.OffsetDateTime;

/**
 * 邀请投影（字段序 = JSON 键序；hydrate 逻辑在 controller，与本 DTO 无关）。
 *
 * <p>空值省略全表：tenant_name / invitee_email / invitee_name / invited_by /
 * inviter_email / inviter_name / message / responded_at / invite_url /
 * is_share_link（false 省略）/ accepted_count（0 省略）。
 * id / tenant_id / invitee_user_id / role / status / expires_at / created_at 恒输出。</p>
 */
public record TenantInvitationResponse(
        long id,
        long tenantId,
        String tenantName,
        String inviteeUserId,
        String inviteeEmail,
        String inviteeName,
        String invitedBy,
        String inviterEmail,
        String inviterName,
        String role,
        String status,
        String message,
        OffsetDateTime expiresAt,
        OffsetDateTime respondedAt,
        OffsetDateTime createdAt,
        String inviteUrl,
        boolean isShareLink,
        int acceptedCount) {

    public TenantInvitationResponse {
        // 空串归一为 null：这些键空值时整个省略
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
