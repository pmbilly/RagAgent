package com.ragagent.auth.apikey.filter;

import java.io.IOException;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 无条件拒绝 API-Key 主体——对照 Go {@code middleware.DenyAPIKeyPrincipal()}
 * （internal/middleware/api_key_gate.go L155-172）。
 *
 * <p><b>解决的是"门禁绕过"这一类 bug</b>：注册在 engine 根上（{@code /api/v1}
 * 分组之外）的路由不经过 {@link APIKeyGateInterceptor}；而 JWT 的角色守卫
 * （RequireRole / RequireSystemAdmin / RequireOwnershipOrRole）对 API-Key 主体是
 * **短路放行**的——它们假设门禁已经判过了。两者叠加的结果就是：一条没挂门禁的路由，
 * 会对任何有效 Key 放行，**连 full-access 都不需要是**。
 * 所以这类路由必须显式挂上本拦截器。</p>
 *
 * <p>JWT 会话（没有 API-Key scope）直通。</p>
 *
 * <p>当前已登记的路由：{@code GET /api/v1/files/presigned-preview}
 * （WebConfig 699 行起，file-serve 接入时挂上）。保留类型是为了让后续
 * 同类路由接入时不必重新推导这条规则——
 * 任务要求的 default-deny 语义由 {@link APIKeyGateInterceptor} 的"未声明即拒绝"
 * 承担（例：{@code POST /api/v1/agent/tool-approvals/:pending_id} 在 Go 里
 * 就没有声明策略，因此 API Key 一律 403）。</p>
 */
public class DenyAPIKeyPrincipalInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!APIKeyScopeContext.present()) {
            return true;
        }
        return APIKeyGateResponses.writeForbidden(response, 403, APIKeyGateResponses.API_KEY_DENIED);
    }
}
