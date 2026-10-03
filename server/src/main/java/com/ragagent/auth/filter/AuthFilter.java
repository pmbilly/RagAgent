package com.ragagent.auth.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import com.ragagent.auth.service.TokenValidationException;
import com.ragagent.auth.service.UserService;
import com.ragagent.auth.service.ValidatedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 全局认证过滤器：三通道认证链。
 *
 * 通道顺序（严格固定）：
 *  1. OPTIONS 预检 / noAuthAPI 白名单 → 直接放行
 *  2. Bearer JWT → UserService.validateToken；成功走 authenticateJWTUser
 *     （空间解析 → TENANT_REQUIRED/角色解析，委托 {@link WsAuthSupport}）；失败不立即拒绝，继续通道 3
 *  3. X-API-Key → TenantAPIKeyService 全量接线（APIKeyAuthChannel）：
 *     租户/平台 Key 鉴权 + 作用域注入
 *  全部未命中 → 401（bearerPresented 决定消息区分"未登录"与"登录态过期"）
 *
 * 覆盖 /*：未匹配路径同样 401（golden 已锁定）。
 * 未匹配 controller 的放行请求由 DispatcherServlet 返回 404（端点随模块补齐）。
 *
 * JWT 认证装配链（attach/authenticate/tenant 解析/角色装配）已抽至
 * {@link WsAuthSupport}——本过滤器不是 Spring bean（WebConfig 里 new 进
 * FilterRegistrationBean），而 SandboxTerminalController 需要注入同一能力。
 */
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    /** 无鉴权白名单：精确路径 → 允许方法 */
    private static final Map<String, Set<String>> NO_AUTH_API = Map.ofEntries(
            Map.entry("/health", Set.of("GET")),
            Map.entry("/api/v1/auth/register", Set.of("POST")),
            Map.entry("/api/v1/auth/login", Set.of("POST")),
            Map.entry("/api/v1/auth/auto-setup", Set.of("POST")),
            Map.entry("/api/v1/auth/invitations/lookup", Set.of("POST")),
            Map.entry("/api/v1/auth/register-by-invite", Set.of("POST")),
            Map.entry("/api/v1/auth/config", Set.of("GET")),
            Map.entry("/api/v1/auth/oidc/config", Set.of("GET")),
            Map.entry("/api/v1/auth/oidc/url", Set.of("GET")),
            Map.entry("/api/v1/auth/oidc/start", Set.of("GET")),
            Map.entry("/api/v1/auth/oidc/callback", Set.of("GET")),
            Map.entry("/api/v1/mcp-oauth/callback", Set.of("GET")),
            Map.entry("/api/v1/auth/refresh", Set.of("POST")),
            Map.entry("/api/v1/files/presigned", Set.of("GET", "HEAD")));

    private final UserService userService;
    private final WsAuthSupport wsAuthSupport;
    private final com.ragagent.auth.apikey.filter.APIKeyAuthChannel apiKeyAuthChannel;

    public AuthFilter(UserService userService,
                      WsAuthSupport wsAuthSupport,
                      com.ragagent.auth.apikey.filter.APIKeyAuthChannel apiKeyAuthChannel) {
        this.userService = userService;
        this.wsAuthSupport = wsAuthSupport;
        this.apiKeyAuthChannel = apiKeyAuthChannel;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 通道 0：OPTIONS 预检
        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 0.5：路径穿越守卫。下方白名单匹配用的
        // getRequestURI() 是未解码、未规范化的原文，形如 /api/v1/embed/../api/v1/sessions
        // 的点段路径能混过前缀检查进"无鉴权区"。Spring PathPatternParser 不解析 ..
        // （大概率落 404 而非越权），但这层安全不应依赖框架巧合：解码后出现 ..
        // 段一律 400（%2e%2e 会被 URLDecoder 归一成 ..，一并覆盖）。
        String rawUri = request.getRequestURI();
        String decodedUri = java.net.URLDecoder.decode(rawUri == null ? "" : rawUri,
                java.nio.charset.StandardCharsets.UTF_8);
        for (String seg : decodedUri.split("/")) {
            if ("..".equals(seg)) {
                response.setStatus(400);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"error\":\"invalid request path\"}");
                return;
            }
        }

        // 通道 1：白名单
        if (isNoAuthAPI(request.getRequestURI(), request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 1.5：embed 公开面（embed 路由组不经全局 Auth，
        // 由 com.ragagent.embed 的 EmbedAuthFilter 自行鉴权，本过滤器整体让路）。
        if (request.getRequestURI().startsWith("/api/v1/embed/")) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 1.6：IM 平台回调（注册在认证链之前——IM 平台用自己的签名校验，
        // 签名校验在 handler 内做）。
        if (request.getRequestURI().startsWith("/api/v1/im/callback/")) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 1.7：/r/ 能力 URL（注册在认证链之前——短时令牌自证，
        // 面向无法带 WeKnora 头的 IM 平台客户端）。
        if (request.getRequestURI().startsWith("/r/")) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 1.8：沙箱终端 WS（注册在认证链之前——浏览器 WS 握手带不了
        // Authorization/X-API-Key，
        // 短生命周期票据走 ticket query 参数，handler 内自鉴权
        // （ParseSandboxTerminalTicket + CheckSandboxTerminalAuth + AttachAuthenticatedUser）。
        // 只放行 GET；terminal-ticket 的 POST 仍在正常 Auth 之下。
        if ("GET".equals(request.getMethod())
                && isSandboxTerminalPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        // 通道 2：Bearer JWT
        boolean bearerPresented = false;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            bearerPresented = true;
            String token = authHeader.substring("Bearer ".length());
            try {
                ValidatedToken vt = userService.validateToken(token);
                if (wsAuthSupport.authenticateJwtUser(request, response, vt)) {
                    chain.doFilter(request, response);
                }
                return;
            } catch (TokenValidationException e) {
                // bearer 校验失败继续尝试 API key，不立即拒绝
                log.warn("[auth] bearer token rejected: {}", e.getMessage());
            }
        }

        // 通道 3：X-API-Key。
        // 鉴权通过时由 channel 自行写入 principal/scope 上下文，不再往下走其它通道。
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isEmpty()) {
            if (apiKeyAuthChannel.authenticate(request, response)) {
                chain.doFilter(request, response);
            }
            return;
        }

        // 全部未命中
        WsAuthSupport.writeUnauthorized(response, bearerPresented
                ? "Unauthorized: invalid or expired token"
                : "Unauthorized: missing authentication");
    }

    /** 白名单判定：全路径精确匹配 + 方法包含 */
    static boolean isNoAuthAPI(String path, String method) {
        Set<String> methods = NO_AUTH_API.get(path);
        return methods != null && methods.contains(method);
    }

    /**
     * 沙箱终端 WS 路径（{@code /api/v1/sessions/:id/sandbox/terminal}）：
     * id 是 path 段（非空、不含 /），后缀 {@code /sandbox/terminal}。
     */
    static boolean isSandboxTerminalPath(String uri) {
        if (uri == null) {
            return false;
        }
        if (!uri.startsWith("/api/v1/sessions/") || !uri.endsWith("/sandbox/terminal")) {
            return false;
        }
        String rest = uri.substring("/api/v1/sessions/".length(),
                uri.length() - "/sandbox/terminal".length());
        return !rest.isEmpty() && !rest.contains("/");
    }

    /** tenantless 放行清单（这些路径无活动空间也可访问） */
    static boolean isTenantOptionalAPI(String path, String method) {
        if ("/api/v1/auth/me".equals(path)
                && ("GET".equals(method) || "PUT".equals(method))) {
            return true;
        }
        if ("/api/v1/auth/me/preferences".equals(path) && "PUT".equals(method)) {
            return true;
        }
        if ("/api/v1/auth/logout".equals(path) && "POST".equals(method)) {
            return true;
        }
        if ("/api/v1/auth/change-password".equals(path) && "POST".equals(method)) {
            return true;
        }
        if ("/api/v1/auth/validate".equals(path) && "GET".equals(method)) {
            return true;
        }
        if ("/api/v1/auth/switch-tenant".equals(path) && "POST".equals(method)) {
            return true;
        }
        if ("/api/v1/tenants".equals(path) && "POST".equals(method)) {
            return true;
        }
        return path != null && path.startsWith("/api/v1/me/invitations");
    }

    // ── 响应写入（契约逐字符锁定，golden 测试比对） ──────────────────────────

    /** 401 响应体：{"error":"Unauthorized: ..."} */
    static void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        WsAuthSupport.writeUnauthorized(response, message);
    }

    static void writePlainError(HttpServletResponse response, int status, String message) throws IOException {
        WsAuthSupport.writePlainError(response, status, message);
    }

    static void writeJson(HttpServletResponse response, String body) throws IOException {
        WsAuthSupport.writeJson(response, body);
    }
}
