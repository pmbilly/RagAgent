package com.ragagent.mcp.dto;

import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;

/**
 * 调用者可见性投影（对照 Go internal/handler/dto/role.go 的
 * {@code CanViewIntegrationSecrets} / {@code RoleFromContext}）。
 *
 * <p>只翻译 MCP 模块用到的这两个判定；Go 里同文件的
 * {@code RoleCanViewTenantAPIKey / CanViewTenantAPIKey}（Owner+）属其它模块。</p>
 */
public final class RoleVisibility {

    private RoleVisibility() {
    }

    /** 对照 Go {@code RoleFromContext}：未附加角色时 fail-closed 为 Viewer */
    public static TenantRole roleFromContext() {
        return TenantRole.fromString(TenantContext.currentRole());
    }

    /**
     * 对照 Go {@code CanViewIntegrationSecrets}：Admin+ 的租户成员，
     * 或拥有全量权限 / {@code manage_tenant_settings} 能力的 API key。
     *
     * <p>⚠️ 阶段性差异：Java 阶段 1 未实现 API key 主体（见约定 §9），
     * 故 API key 分支暂缺——与 {@code ModelController.canViewIntegrationSecrets}
     * 保持完全相同的判定，待 APIKeyGate 翻译后统一收口。</p>
     */
    public static boolean canViewIntegrationSecrets() {
        return roleFromContext().hasPermission(TenantRole.ADMIN);
    }
}
