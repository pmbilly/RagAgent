package com.ragagent.auth.dto;

import java.time.OffsetDateTime;

/**
 * GET/POST /tenants/{id}/members 的成员投影（字段序 = JSON 键序）。
 *
 * <p>空值省略映射：avatar（空串省略）/ invited_by（null 省略）→ NON_NULL，
 * 构造时把零值（"" / null）归一为 null。joined_at 恒输出。</p>
 */
public record TenantMemberResponse(
        String userId,
        String email,
        String username,
        String avatar,
        String role,
        String status,
        String invitedBy,
        OffsetDateTime joinedAt) {

    public TenantMemberResponse {
        // avatar="" 与成员行 invited_by=NULL 一样整体省略
        if (avatar != null && avatar.isEmpty()) {
            avatar = null;
        }
    }
}
