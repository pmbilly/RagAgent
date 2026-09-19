package com.ragagent.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /auth/invitations/lookup 请求体
 * （对照 Go handler/auth_register_by_invite.go invitationLookupRequest）。
 * token 走 body 而非 path：避免明文 token 落访问日志/浏览器历史/tracing。
 */
public record InvitationLookupRequest(@JsonProperty("token") String token) {
}
