package com.ragagent.mcp.dto;

import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.context.TenantContext;

/**
 * 调用者可见性投影（角色提取与集成秘密可见性两个判定）。
 *
 * <p>只实现 MCP 模块用到的这两个判定；Owner+ 的租户 API key 判定属其它模块。</p>
 */
public final class RoleVisibility {

    private RoleVisibility() {
    }

    /** 未附加角色时 fail-closed 为 Viewer */
    public static TenantRole roleFromContext() {
        return TenantRole.fromString(TenantContext.currentRole());
    }

    /**
     * 集成秘密可见性：Admin+ 的租户成员，
     * 或拥有全量权限 / {@code manage_tenant_settings} 能力的 API key。
     *
     * <p>⚠️ Java 目前未实现 API key 主体，
     * 故 API key 分支暂缺——与 {@code ModelController.canViewIntegrationSecrets}
     * 保持完全相同的判定，待 API key 主体落地后统一收口。</p>
     */
    public static boolean canViewIntegrationSecrets() {
        return roleFromContext().hasPermission(TenantRole.ADMIN);
    }
}
