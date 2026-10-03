package com.ragagent.mcp.domain;

import com.ragagent.common.context.TenantContext;

/**
 * principal 辅助方法集：规范化 / 有效性 / 存储标识 / 上下文提取。
 *
 * 复用 {@link TenantContext.Principal}（type + id 二元组）。
 * 注意 principal 与 UserID 是**两回事**：IM 用户、embed 访客不是 WeKnora 账号，
 * 不能隐含 RBAC 权限。MCP OAuth 的 token / 元数据快照按 principal 隔离，不是按 user 隔离。
 */
public final class McpPrincipal {

    public static final String WEB_USER = "web_user";
    public static final String API_TENANT = "api_tenant";
    public static final String API_PLATFORM = "api_platform";
    public static final String API_EXTERNAL_USER = "api_external_user";
    public static final String IM_USER = "im_user";
    public static final String EMBED_CHANNEL = "embed_channel";
    public static final String EMBED_SESSION = "embed_session";
    public static final String EMBED_VISITOR = "embed_visitor";

    private McpPrincipal() {}

    /** 规范化：两端去空白；null 原样返回 */
    public static TenantContext.Principal normalize(TenantContext.Principal p) {
        if (p == null) {
            return null;
        }
        return new TenantContext.Principal(trim(p.type()), trim(p.id()));
    }

    /** 有效判定：type 与 id 都非空 */
    public static boolean valid(TenantContext.Principal p) {
        TenantContext.Principal n = normalize(p);
        return n != null && !n.type().isEmpty() && !n.id().isEmpty();
    }

    /** 存储标识 `type:id`；无效返回空串 */
    public static String storageId(TenantContext.Principal p) {
        TenantContext.Principal n = normalize(p);
        if (n == null || n.type().isEmpty() || n.id().isEmpty()) {
            return "";
        }
        return n.type() + ":" + n.id();
    }

    /**
     * 上下文里有 principal 就用它，
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

    /** 构造 embed 访客 principal */
    public static TenantContext.Principal embedVisitorPrincipal(long tenantId, String channelId, String visitorId) {
        return new TenantContext.Principal(EMBED_VISITOR,
                tenantId + ":" + trim(channelId) + ":" + trim(visitorId));
    }

    /**
     * embed 聊天会话在有 X-Embed-Visitor 时
     * 映射成**按访客**的 principal，否则回落到聊天会话 principal 本身。
     *
     * 无效时返回 null。
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

    /** 十进制数字串解析：失败或非数字返回 null */
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
