package com.ragagent.mcp.domain;

import com.ragagent.common.context.TenantContext;

/**
 * 对照 Go internal/types/principal.go 的 Principal 辅助方法
 * （Normalize / Valid / StorageID / PrincipalFromContext / MCPOAuthPrincipalFromContext）。
 *
 * 复用 {@link TenantContext.Principal}（与 Go types.Principal{Type, ID} 同形）。
 * 注意 Go 的 Principal 与 UserID 是**两回事**：IM 用户、embed 访客不是 WeKnora 账号，
 * 不能隐含 RBAC 权限。MCP OAuth 的 token / 元数据快照按 principal 隔离，不是按 user 隔离。
 */
public final class McpPrincipal {

    /** 对照 Go PrincipalWebUser */
    public static final String WEB_USER = "web_user";
    /** 对照 Go PrincipalAPITenant */
    public static final String API_TENANT = "api_tenant";
    /** 对照 Go PrincipalAPIPlatform */
    public static final String API_PLATFORM = "api_platform";
    /** 对照 Go PrincipalAPIExternalUser */
    public static final String API_EXTERNAL_USER = "api_external_user";
    /** 对照 Go PrincipalIMUser */
    public static final String IM_USER = "im_user";
    /** 对照 Go PrincipalEmbedChannel */
    public static final String EMBED_CHANNEL = "embed_channel";
    /** 对照 Go PrincipalEmbedSession */
    public static final String EMBED_SESSION = "embed_session";
    /** 对照 Go PrincipalEmbedVisitor */
    public static final String EMBED_VISITOR = "embed_visitor";

    private McpPrincipal() {}

    /** 对照 Go Principal.Normalize：两端去空白 */
    public static TenantContext.Principal normalize(TenantContext.Principal p) {
        if (p == null) {
            return null;
        }
        return new TenantContext.Principal(trim(p.type()), trim(p.id()));
    }

    /** 对照 Go Principal.Valid：type 与 id 都非空 */
    public static boolean valid(TenantContext.Principal p) {
        TenantContext.Principal n = normalize(p);
        return n != null && !n.type().isEmpty() && !n.id().isEmpty();
    }

    /** 对照 Go Principal.StorageID：`type:id`；无效返回空串 */
    public static String storageId(TenantContext.Principal p) {
        TenantContext.Principal n = normalize(p);
        if (n == null || n.type().isEmpty() || n.id().isEmpty()) {
            return "";
        }
        return n.type() + ":" + n.id();
    }

    /**
     * 对照 Go PrincipalFromContext：上下文里有 principal 就用它，
     * 否则回落到 user_id 组成的 web_user principal；都没有返回 null。
     */
    public static TenantContext.Principal fromContext() {
        TenantContext.Principal p = normalize(TenantContext.currentPrincipal());
        if (p != null && !p.type().isEmpty() && !p.id().isEmpty()) {
            return p;
        }
        String uid = TenantContext.currentUserId();
        if (uid != null && !uid.trim().isEmpty()) {
            return new TenantContext.Principal(WEB_USER, uid.trim());
        }
        return null;
    }

    /** 对照 Go EmbedVisitorPrincipal */
    public static TenantContext.Principal embedVisitorPrincipal(long tenantId, String channelId, String visitorId) {
        return new TenantContext.Principal(EMBED_VISITOR,
                tenantId + ":" + trim(channelId) + ":" + trim(visitorId));
    }

    /**
     * 对照 Go MCPOAuthPrincipalFromContext：embed 聊天会话在有 X-Embed-Visitor 时
     * 映射成**按访客**的 principal，否则回落到聊天会话 principal 本身。
     *
     * 无效时返回 null（Go 返回零值 Principal，StorageID() 为空串）。
     */
    public static TenantContext.Principal oauthPrincipalFromContext() {
        TenantContext.Principal p = fromContext();
        if (p == null) {
            return null;
        }
        p = normalize(p);
        if (!EMBED_SESSION.equals(p.type())) {
            return p;
        }
        String visitorId = TenantContext.currentEmbedVisitorId();
        if (visitorId == null || visitorId.trim().isEmpty()) {
            return p;
        }
        // strings.SplitN(p.ID, ":", 3)
        String[] parts = p.id().split(":", 3);
        if (parts.length < 2) {
            return p;
        }
        String tenantPart = parts[0].trim();
        String channelPart = parts[1].trim();
        if (tenantPart.isEmpty() || channelPart.isEmpty()) {
            return p;
        }
        Long tenantId = parseUint64(tenantPart);
        if (tenantId == null || tenantId == 0) {
            return new TenantContext.Principal(EMBED_VISITOR,
                    tenantPart + ":" + channelPart + ":" + visitorId);
        }
        return embedVisitorPrincipal(tenantId, channelPart, visitorId);
    }

    /** 对照 Go fmt.Sscanf("%d") 的解析：失败或非数字返回 null */
    private static Long parseUint64(String s) {
        if (s.isEmpty()) {
            return null;
        }
        long value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
