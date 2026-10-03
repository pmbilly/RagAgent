package com.ragagent.auth.dto;

/**
 * POST /auth/invitations/lookup 的响应投影。
 *
 * 刻意收窄：只够注册页渲染「X 邀请你加入 Y」，不暴露邀请人审计字段。
 * tenant_name 为空时省略；expires_at 是 UTC 预格式化字符串
 * （"yyyy-MM-dd'T'HH:mm:ss'Z'"，不含小数秒）。
 */
public record InvitationLookupResponse(
        long tenantId,
        String tenantName,
        String role,
        String expiresAt) {
}
