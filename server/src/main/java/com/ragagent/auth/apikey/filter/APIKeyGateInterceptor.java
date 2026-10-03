package com.ragagent.auth.apikey.filter;

import java.io.IOException;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * API-Key 门禁拦截器。
 *
 * <p>门禁挂在 {@code /api/v1} 上，位置是"路由之后、其它守卫之前"。
 * Spring 的 {@code HandlerInterceptor.preHandle} 恰好满足同一时序：
 * 它在 handler mapping 完成之后、controller 之前运行，因此可以读到
 * {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}
 * （匹配到的路由 pattern）。</p>
 *
 * <h2>语义</h2>
 * <ol>
 *   <li><b>没有 API-Key scope → 直接放行</b>。JWT 会话不受本门禁约束。</li>
 *   <li>有 scope → 查策略表判定；<b>未声明即拒绝</b>（default deny）。</li>
 *   <li>拒绝时 403 纯字符串信封
 *       {@code {"error":"Forbidden: API key scope does not allow this operation"}}。</li>
 * </ol>
 *
 * <p>⚠️ <b>注册顺序</b>：必须在 {@code RbacInterceptor} **之前**（先做能力维度判定，
 * 再做角色维度判定）。对 API-Key 主体，{@code RbacInterceptor} 的角色判定是短路的
 * ——角色维度由本门禁全权代表。</p>
 */
public class APIKeyGateInterceptor implements HandlerInterceptor {

    private final APIKeyRouteAuthorizer authorizer;

    public APIKeyGateInterceptor(APIKeyRouteAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        TenantAPIKeyScope scope = APIKeyScopeContext.current();
        if (scope == null) {
            return true;
        }
        // Spring：BEST_MATCHING_PATTERN_ATTRIBUTE 在 handler mapping 后被写入，
        // 形如 "/api/v1/knowledge-bases/{id}/knowledge/file"。
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String fullPath = pattern == null ? request.getRequestURI() : pattern.toString();
        if (authorizer.authorize(scope, request.getMethod(), fullPath)) {
            return true;
        }
        return APIKeyGateResponses.writeForbidden(response, 403, APIKeyGateResponses.SCOPE_FORBIDDEN);
    }
}
