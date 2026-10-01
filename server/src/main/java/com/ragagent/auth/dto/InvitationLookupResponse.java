package com.ragagent.auth.dto;

/**
 * POST /auth/invitations/lookup 的响应投影
 * （对照 Go handler/auth_register_by_invite.go invitationLookupResponse）。
 *
 * 刻意收窄：只够注册页渲染「X 邀请你加入 Y」，不暴露邀请人审计字段。
 * tenant_name 为 omitempty（取不到租户名时省略）；expires_at 是
 * Go `.UTC().Format("2006-01-02T15:04:05Z07:00")` 的预格式化字符串（不含小数秒）。
 */
public record InvitationLookupResponse(
        long tenantId,
        String tenantName,
        String role,
        String expiresAt) {
}
