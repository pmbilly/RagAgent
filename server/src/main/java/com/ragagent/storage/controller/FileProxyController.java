package com.ragagent.storage.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.storage.fileserve.FileProxyService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 文件代理面的引擎级路由（收尾批 W5c，对照 Go internal/router/files.go 的注册段）。
 *
 * <p>这四组路由的鉴权分层各不相同（关键在<b>注册位置</b>）：</p>
 * <ul>
 *   <li><b>GET /files</b>：Go 注册在 Auth 中间件<b>之后</b>（router.go L189→L192）→
 *       需登录；<b>在 /api/v1 组之外</b> → APIKeyGate 不跑，路由自带
 *       AllowFileServeAPIKey；无 RBAC 角色门。Java：AuthFilter 全局覆盖 +
 *       控制器内 allowFileServeApiKey，拦截器 pattern 天然不覆盖 /files。</li>
 *   <li><b>GET+HEAD /api/v1/files/presigned</b>：Go 注册在 Auth 之后（L195）但
 *       Auth 的 noAuthAPI 白名单放行 GET/HEAD（IM 平台 HEAD 预检契约）→ 免鉴权、
 *       HMAC 自证。Java：AuthFilter NO_AUTH_API 已有该条目。</li>
 *   <li><b>GET /api/v1/files/presigned-preview</b>：Go 注册在<b>引擎根</b>（L198，
 *       非 /api/v1 组）→ APIKeyGate 不跑，所以显式 DenyAPIKeyPrincipal 先行、
 *       RequireRole(Admin) 次之（RequireRole 对 API-Key 主体短路，见 rbac.go）。
 *       Java：APIKeyGateInterceptor 对 /api/v1/files/** 排除（WebConfig），本控制器
 *       先做 DenyAPIKeyPrincipal（403 文案与 Go 一致），角色门由 RbacInterceptor 的
 *       ADMIN 规则承担（对 API-Key 主体同样短路）。</li>
 *   <li><b>GET+HEAD /r/{token}</b>：Go 注册在 Auth 中间件<b>之前</b>（L177）→
 *       完全无鉴权，短时令牌自证。Java：AuthFilter 的 /r/ 前缀让路。</li>
 * </ul>
 *
 * <p><b>HEAD 的 404 形态</b>：Go 只给 /r/* 与 presigned 注册了 HEAD；对其余 GET 路由，
 * gin 的 HEAD 请求落 NoRoute（gin.go serveError：404 + Content-Type "text/plain" +
 * "404 page not found" 无换行）。Spring 的 @GetMapping 透明匹配 HEAD（吞体），
 * 故这三处显式按 gin 形态回 404。</p>
 */
@RestController
public class FileProxyController {

    private final FileProxyService proxy;
    private final TenantService tenantService;

    public FileProxyController(FileProxyService proxy, TenantService tenantService) {
        this.proxy = proxy;
        this.tenantService = tenantService;
    }

    // ── GET /files（租户级存储代理）─────────────────────────────────────────

    @GetMapping("/files")
    public void files(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxy.serveTenantFiles(request, response);
    }

    /**
     * Go 只注册了 GET：gin 对 HEAD 落 NoRoute（404 "404 page not found"，
     * Content-Type text/plain——见类注释）。显式 HEAD 映射优先于 Spring 的
     * HEAD→GET 回退，保证与 Go 一致。
     */
    @RequestMapping(value = "/files", method = RequestMethod.HEAD)
    public void filesHead(HttpServletResponse response) throws IOException {
        writeGinNoRoute(response);
    }

    // ── GET+HEAD /api/v1/files/presigned（IM 签名 URL）──────────────────────

    @RequestMapping(value = "/api/v1/files/presigned", method = { RequestMethod.GET, RequestMethod.HEAD })
    public void presigned(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxy.servePresigned(request, response);
    }

    // ── GET /api/v1/files/presigned-preview（Admin 诊断）────────────────────

    @GetMapping("/api/v1/files/presigned-preview")
    public void presignedPreview(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // DenyAPIKeyPrincipal 由 DenyAPIKeyPrincipalInterceptor 承担（WebConfig 接线）。
        // 对照 Go handler 体：tenant 从请求上下文取（Auth 已装入），缺失 → 401
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null || tenantId == 0) {
            FileProxyService.writeErrorJson(response, 401, "unauthorized: workspace context missing");
            return;
        }
        Tenant tenant = tenantService.getTenantById(tenantId);
        if (tenant == null) {
            FileProxyService.writeErrorJson(response, 401, "unauthorized: workspace context missing");
            return;
        }
        proxy.presignedPreview(request, response, tenant);
    }

    @RequestMapping(value = "/api/v1/files/presigned-preview", method = RequestMethod.HEAD)
    public void presignedPreviewHead(HttpServletResponse response) throws IOException {
        writeGinNoRoute(response);
    }

    // ── GET+HEAD /r/{token}（capability URL）────────────────────────────────

    @RequestMapping(value = "/r/{token}", method = { RequestMethod.GET, RequestMethod.HEAD })
    public void resourceGrant(@PathVariable("token") String token, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        proxy.serveResourceGrant(token, request, response);
    }

    /** gin NoRoute 形态（供 GET-only 路由的 HEAD 处理复用）。 */
    public static void writeGinNoRoute(HttpServletResponse response) throws IOException {
        response.setStatus(404);
        response.setContentLength("404 page not found".length());
        response.setContentType("text/plain");
        response.getOutputStream().write("404 page not found".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Go 只注册了 GET 的路由对 HEAD 的响应（gin NoRoute 形态，见类注释）。
     * 返回 true 表示已写响应。只设 Content-Type: text/plain（无 charset，gin 同），
     * body 走字节流避免容器给 writer 附加编码后缀。
     */
    public static boolean notFoundForHead(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!"HEAD".equals(request.getMethod())) {
            return false;
        }
        response.setContentLength("404 page not found".length());
        response.setContentType("text/plain");
        response.getOutputStream().write("404 page not found".getBytes(StandardCharsets.US_ASCII));
        return true;
    }
}
