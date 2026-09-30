package com.ragagent.auth.apikey.filter;

import java.io.IOException;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求结束时清空 {@link APIKeyScopeContext} 的 ThreadLocal。
 *
 * <p><b>为什么必须有它</b>：Servlet 容器复用工作线程。若上一请求以 API Key 认证
 * 并把 scope 留在 ThreadLocal 上，同一线程的下一个 JWT 请求会被
 * {@link APIKeyGateInterceptor} 误判为 API Key 主体 → 未声明的路由全部 403。
 * Go 侧不存在这个问题（scope 随 {@code context.Context} 一起被请求生命周期回收），
 * 所以这是 Java 侧特有的、必须显式补上的一环。</p>
 *
 * <p>注册位置：**AuthFilter 之前**（否则认证阶段写入的 scope 会被上一请求的清理动作
 * 跨请求影响），且包住整条链（{@code try/finally}）。
 * 建议 order = {@code Ordered.HIGHEST_PRECEDENCE + 15}，urlPatterns = {@code /*}。</p>
 */
public class APIKeyScopeCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            APIKeyScopeContext.clear();
        }
    }
}
