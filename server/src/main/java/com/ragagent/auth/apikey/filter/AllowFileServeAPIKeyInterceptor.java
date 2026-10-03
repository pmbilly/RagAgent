package com.ragagent.auth.apikey.filter;

import java.io.IOException;

import com.ragagent.auth.apikey.domain.APIKeyCapability;
import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 原始文件代理路由（{@code /files} 与 KB 作用域的图片代理）的 API-Key 专用守卫。
 *
 * <h2>为什么这些路由需要单独的守卫，而不是一条普通策略</h2>
 * <p>这些路由服务的是**任意存储路径**，路径里只带一个租户段，
 * <b>没有 KB id 可供 KB 受限 Key 的白名单比对</b>。所以：</p>
 * <ul>
 *   <li><b>KB 受限的 Key 一律拒绝</b>——它必须走 KB 作用域的下载路由
 *       （如 {@code /knowledge/:id/download}），那条路径**会**执行白名单校验；</li>
 *   <li>放行条件是"full-access"，或"**不受 KB 限制**且带 retrieve 能力"：
 *       这正好是"已经能读该租户任意 KB 内容"的那一类 Key，
 *       于是把租户边界的原始文件路径暴露给它并不新增任何权限
 *       （handler 仍会执行 {@code ValidateStoragePathTenant} / KB 归属租户校验）；</li>
 *   <li>JWT 会话不带 API-Key scope，直通。</li>
 * </ul>
 */
public class AllowFileServeAPIKeyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null) {
            return true;
        }
        if (scope.fullAccess()
                || (!scope.isKnowledgeBaseRestricted()
                    && scope.hasCapability(APIKeyCapability.RETRIEVE))) {
            return true;
        }
        return APIKeyGateResponses.writeForbidden(response, 403, APIKeyGateResponses.SCOPE_FORBIDDEN);
    }
}
