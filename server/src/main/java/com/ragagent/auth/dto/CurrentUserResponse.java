package com.ragagent.auth.dto;

import java.util.List;
import java.util.Map;

/** GET /auth/me：当前用户视图（活动空间、能力位、成员关系与偏好默认值）。 */
public record CurrentUserResponse(
        UserCapabilities capabilities,
        List<Membership> memberships,
        Map<String, Object> preferenceDefaults,
        TenantResponse tenant,
        boolean tenantRequired,
        UserInfo user) {
}
