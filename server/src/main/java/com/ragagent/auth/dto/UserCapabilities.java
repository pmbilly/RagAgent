package com.ragagent.auth.dto;

/** GET /auth/me 的能力位：能否自助建空间、是否自动接受邀请。 */
public record UserCapabilities(boolean autoAcceptInvitation, boolean canCreateTenant) {
}
