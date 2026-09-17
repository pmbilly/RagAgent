package com.ragagent.common.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 阶段 0 骨架版认证 Filter，对照 Go middleware/auth.go 的未认证响应契约：
 * - 无凭据        → 401 {"error":"Unauthorized: missing authentication"}
 * - Bearer 已携带 → 401 {"error":"Unauthorized: invalid or expired token"}
 *   （骨架期尚无 JWT 校验；阶段 1 接入 UserService.validateToken 后，
 *     "invalid or expired" 只在校验失败路径返回，与 Go 一致）
 *
 * Go 的 Auth 挂在 engine 全局（/health 之前注册的路由除外），未匹配的路径同样 401
 * （golden: GET /no-such-page → 401），故本 Filter 覆盖 /*，仅放行 noAuthPaths。
 */
public class AuthFilter extends OncePerRequestFilter {

    /** 无需认证的路径前缀（阶段 1 起按 Go isNoAuthAPI 的清单扩充：/api/v1/auth/login 等） */
    private static final java.util.Set<String> NO_AUTH_PATHS = java.util.Set.of(
            "/health"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return NO_AUTH_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        boolean bearerPresented = authHeader != null && authHeader.startsWith("Bearer ");
        String message = bearerPresented
                ? "Unauthorized: invalid or expired token"
                : "Unauthorized: missing authentication";
        writeUnauthorized(response, message);
    }

    static void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 与 Go gin.H{"error": ...} 序列化一致：{"error":"..."}
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
