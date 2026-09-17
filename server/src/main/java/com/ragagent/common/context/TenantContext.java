package com.ragagent.common.context;

/**
 * 对照 Go context.Context 传递的租户/principal/visitor 信息。
 * 由 Filter 链（对应 Go middleware 链）填充，service 层经 current() 读取。
 * 虚拟线程下安全（每请求一个线程），但跨线程传递必须显式取值传递。
 */
public final class TenantContext {

    public enum PrincipalType {
        WEB_USER,
        API_KEY,
        EMBED_SESSION,
        EMBED_VISITOR
    }

    public record Principal(PrincipalType type, String id) {}

    private static final ThreadLocal<Long> tenantId = new ThreadLocal<>();
    private static final ThreadLocal<Principal> principal = new ThreadLocal<>();
    private static final ThreadLocal<String> role = new ThreadLocal<>();
    private static final ThreadLocal<String> embedVisitorId = new ThreadLocal<>();
    private static final ThreadLocal<String> requestId = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> systemAdmin = ThreadLocal.withInitial(() -> false);

    private TenantContext() {}

    public static Long currentTenantId() {
        return tenantId.get();
    }

    public static Principal currentPrincipal() {
        return principal.get();
    }

    public static String currentRole() {
        return role.get();
    }

    public static String currentEmbedVisitorId() {
        return embedVisitorId.get();
    }

    public static String currentRequestId() {
        return requestId.get();
    }

    public static boolean isSystemAdmin() {
        return systemAdmin.get();
    }

    public static void set(Long tid, Principal p, String r, boolean sysAdmin) {
        tenantId.set(tid);
        principal.set(p);
        role.set(r);
        systemAdmin.set(sysAdmin);
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
        embedVisitorId.remove();
        requestId.remove();
        systemAdmin.remove();
    }
}
