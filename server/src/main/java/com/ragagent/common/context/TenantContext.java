package com.ragagent.common.context;

/**
 * 请求级认证会话信息：由 Filter 链填充，service 层经 current*() 读取。
 * 虚拟线程下安全（每请求一个线程），但跨线程传递必须显式取值传递。
 *
 * tenantId 为 null 表示 tenantless 会话（身份级路由）；role 为 null 表示未附加角色，
 * 读取方 fail-closed 默认 Viewer。
 */
public final class TenantContext {

    /** 认证主体（type + id）；type 取值见 PrincipalTypes */
    public record Principal(String type, String id) {}

    /** 主体类型常量 */
    public static final class PrincipalTypes {
        public static final String WEB_USER = "web_user";
        public static final String API_TENANT = "api_tenant";
        public static final String API_PLATFORM = "api_platform";
        public static final String API_EXTERNAL_USER = "api_external_user";
        /** IM 渠道用户（会话 owner 判定会回落到普通 user id）。 */
        public static final String IM_USER = "im_user";
        public static final String EMBED_CHANNEL = "embed_channel";
        public static final String EMBED_SESSION = "embed_session";
        public static final String EMBED_VISITOR = "embed_visitor";

        private PrincipalTypes() {}
    }

    public static Principal webUserPrincipal(String userId) {
        return new Principal(PrincipalTypes.WEB_USER, userId);
    }

    private static final ThreadLocal<Long> tenantId = new ThreadLocal<>();
    private static final ThreadLocal<Principal> principal = new ThreadLocal<>();
    private static final ThreadLocal<String> role = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> systemAdmin = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> canAccessAllTenants = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> userId = new ThreadLocal<>();
    private static final ThreadLocal<String> embedVisitorId = new ThreadLocal<>();
    private static final ThreadLocal<String> requestId = new ThreadLocal<>();

    private TenantContext() {}

    public static Long currentTenantId() {
        return tenantId.get();
    }

    public static Principal currentPrincipal() {
        return principal.get();
    }

    /** 对照 TenantRoleFromContext：未附加时返回 null（调用方 fail-closed） */
    public static String currentRole() {
        return role.get();
    }

    public static String currentUserId() {
        return userId.get();
    }

    public static String currentEmbedVisitorId() {
        return embedVisitorId.get();
    }

    public static String currentRequestId() {
        return requestId.get();
    }

    public static boolean isSystemAdmin() {
        return Boolean.TRUE.equals(systemAdmin.get());
    }

    /** 对照 User.CanAccessAllTenants（跨空间超管判定的另一半，需配合 EnableCrossTenantAccess） */
    public static boolean canAccessAllTenants() {
        return Boolean.TRUE.equals(canAccessAllTenants.get());
    }

    /** 对照 applyAuthSession 的常规会话（tenantId/role 允许 null = tenantless） */
    public static void set(Long tid, Principal p, String r, boolean sysAdmin, String uid,
                           boolean accessAllTenants) {
        tenantId.set(tid);
        principal.set(p);
        role.set(r);
        systemAdmin.set(sysAdmin);
        userId.set(uid);
        canAccessAllTenants.set(accessAllTenants);
    }

    public static void setEmbedVisitorId(String visitorId) {
        embedVisitorId.set(visitorId);
    }

    public static void setRequestId(String rid) {
        requestId.set(rid);
    }

    public static void clear() {
        tenantId.remove();
        principal.remove();
        role.remove();
        systemAdmin.remove();
        userId.remove();
        canAccessAllTenants.remove();
        embedVisitorId.remove();
        requestId.remove();
    }
}
