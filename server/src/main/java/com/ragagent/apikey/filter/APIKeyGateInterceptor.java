package com.ragagent.apikey.filter;

import java.io.IOException;

import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * API-Key 门禁拦截器——对照 Go {@code APIKeyRouteAuthorizer.Middleware()}
 * （internal/middleware/api_key_gate.go L111-126）。
 *
 * <p>Go 把它挂在 {@code /api/v1} 分组上，位置是"路由之后、其它守卫之前"
 * （只有路由后 {@code c.FullPath()} 才有值）。Spring 的
 * {@code HandlerInterceptor.preHandle} 恰好满足同一时序：
 * 它在 handler mapping 完成之后、controller 之前运行，因此可以读到
 * {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}——
 * 这就是 {@code c.FullPath()} 的 Java 等价物。</p>
 *
 * <h2>语义（逐句对照 Go）</h2>
 * <ol>
 *   <li><b>没有 API-Key scope → 直接放行</b>。JWT 会话不受本门禁约束
 *       （对照 Go {@code scope, ok := ...; if !ok { c.Next(); return }}）。</li>
 *   <li>有 scope → 查策略表判定；<b>未声明即拒绝</b>（default deny）。</li>
 *   <li>拒绝时 403 纯字符串信封
 *       {@code {"error":"Forbidden: API key scope does not allow this operation"}}。</li>
 * </ol>
 *
 * <p>⚠️ <b>注册顺序</b>：必须在 {@code RbacInterceptor} **之前**（先做能力维度判定，
 * 再做角色维度判定）。Go 里 RequireRole 对 API-Key 主体是短路的——角色维度
 * 由本门禁全权代表。Java 的 {@code RbacInterceptor} 目前没有这个短路分支，
 * 见任务报告"待接线"。</p>
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
        // 形如 "/api/v1/knowledge-bases/{id}/knowledge/file"（对照 gin 的 c.FullPath()）。
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String fullPath = pattern == null ? request.getRequestURI() : pattern.toString();
        if (authorizer.authorize(scope, request.getMethod(), fullPath)) {
            return true;
        }
        return APIKeyGateResponses.writeForbidden(response, 403, APIKeyGateResponses.SCOPE_FORBIDDEN);
    }
}
