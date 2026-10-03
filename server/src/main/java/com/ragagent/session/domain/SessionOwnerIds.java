package com.ragagent.session.domain;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.common.context.TenantContext;

/**
 * {@code sessions.user_id} 的取值规则。
 *
 * <p>这一列同时承载四种主体，取哪一种由调用方身份决定：</p>
 * <ul>
 *   <li>普通 Web 用户 → 用户 UUID</li>
 *   <li>API 外部用户 / embed 会话 → 主体派生 ID {@code "type:id"}</li>
 *   <li>租户 API Key（无外部身份）→ {@code "api_tenant_key:<tenantID>:<keyID>"}（**按 Key 隔离**）</li>
 * </ul>
 *
 * <p>前缀常量必须逐字保持稳定：列表查询拿它们做 {@code LIKE '前缀%'} 来分
 * "api" 桶的，改一个字符就会让历史行落到错误的来源筛选里。</p>
 */
public final class SessionOwnerIds {

    /** 租户 API-Key 主体的 owner 前缀（按 Key 隔离）。 */
    public static final String API_TENANT_KEY_PREFIX = "api_tenant_key:";

    /** API 外部用户主体的 owner 前缀。 */
    public static final String API_EXTERNAL_USER_PREFIX = "api_external_user:";

    /** embed 访客会话的 owner 前缀。 */
    public static final String EMBED_SESSION_PREFIX = "embed_session:";

    private SessionOwnerIds() {
    }

    /**
     * 存下来的 owner 是否来自租户 API-Key 请求（带不带外部用户身份都算）。
     *
     * <p>先 trim 再判前缀。</p>
     */
    public static boolean isApiSessionOwnerId(String ownerId) {
        if (ownerId == null) {
            return false;
        }
        String v = ownerId.trim();
        return v.startsWith(API_TENANT_KEY_PREFIX) || v.startsWith(API_EXTERNAL_USER_PREFIX);
    }

    /**
     * 当前调用方的 {@code sessions.user_id}。
     *
     * <p>分支顺序有语义：主体类型的特判**先于**普通的 user id 回落，且 API-Key 那条
     * 需要同时拿得到 Key 作用域与租户 ID 才生效——缺任一个都会落到最后的 user id 分支。</p>
     */
    public static String currentSessionOwnerId() {
        TenantContext.Principal principal = TenantContext.currentPrincipal();
        if (isValid(principal)) {
            String type = principal.type().trim();
            String id = principal.id().trim();
            if (TenantContext.PrincipalTypes.API_EXTERNAL_USER.equals(type)
                    || TenantContext.PrincipalTypes.EMBED_SESSION.equals(type)) {
                return type + ":" + id;
            }
            if (TenantContext.PrincipalTypes.API_TENANT.equals(type)) {
                TenantAPIKeyScope scope = APIKeyScopeContext.current();
                Long tenantId = TenantContext.currentTenantId();
                if (scope != null && scope.keyId() > 0 && tenantId != null && tenantId > 0) {
                    return API_TENANT_KEY_PREFIX + tenantId + ":" + scope.keyId();
                }
            }
        }
        String userId = TenantContext.currentUserId();
        return userId == null ? "" : userId;
    }

    /** 主体有效：trim 后两段都非空。 */
    private static boolean isValid(TenantContext.Principal p) {
        return p != null
                && p.type() != null && !p.type().trim().isEmpty()
                && p.id() != null && !p.id().trim().isEmpty();
    }
}
