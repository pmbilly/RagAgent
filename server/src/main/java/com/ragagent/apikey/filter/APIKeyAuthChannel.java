package com.ragagent.apikey.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.apikey.domain.TenantAPIKey;
import com.ragagent.apikey.domain.TenantAPIKeyScope;
import com.ragagent.apikey.mapper.TenantAPIKeyNotFoundException;
import com.ragagent.apikey.service.TenantAPIKeyService;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * X-API-Key 认证通道（对照 Go internal/middleware/auth.go 的通道 3：
 * {@code authenticateAPIKeyRequest} L371-451、{@code attachPlatformAPIKeyAuthContext}
 * L455-471、{@code attachAPIKeyAuthContext} L488-560）。
 *
 * <p><b>调用位置</b>：{@code AuthFilter} 的第 3 条通道。Go 的 Auth() 三通道顺序是
 * Bearer → X-API-Key → 401；Java 侧 {@code AuthFilter} 目前把第 3 条通道写成了
 * 固定 401 {@code API key service is not configured}（阶段 1 的占位）。
 * 接法是把那段替换为：</p>
 * <pre>{@code
 * String apiKey = request.getHeader("X-API-Key");
 * if (apiKey != null && !apiKey.isEmpty()) {
 *     if (apiKeyAuthChannel.authenticate(request, response)) { chain.doFilter(request, response); }
 *     return;
 * }
 * }</pre>
 * <p>并把本 bean 注入 {@code AuthFilter}（构造参数）与 {@code WebConfig.authFilter(...)}。
 * 详见任务报告"待接线"。</p>
 *
 * <h2>成功时写入了什么</h2>
 * <ul>
 *   <li>{@link TenantContext}：tenantId / principal（api_tenant 或 api_platform）/
 *       role（viewer，full-access 租户 Key 为 owner）/ 合成用户 ID；</li>
 *   <li>{@link APIKeyScopeContext}：供 {@link APIKeyGateInterceptor} 与下游
 *       KB 白名单判定使用。这是"JWT 直通 / API Key 受门禁"的判定依据。</li>
 * </ul>
 *
 * <h2>已知差异（相对于 Go，见任务报告）</h2>
 * <ol>
 *   <li>{@code userService.GetUserByTenantID} 在 Java 尚未翻译 → 租户 Key 一律走
 *       Go 的**合成用户兜底分支** {@code system-<tenantId>}
 *       （Go 在该查询失败时也落在同一分支，行为一致；只是少了"租户首位用户"这条路径）。</li>
 *   <li>{@code resolveAPIPrincipal}（API 主体模式 / HMAC 签名令牌）未翻译 →
 *       等价于 Go 里 {@code cfg == nil || cfg.Mode == "" || cfg.Mode == tenant}
 *       的回落分支：principal = {@code api_tenant/<tenantId>}，不做 X-External-User-ID 校验。</li>
 * </ol>
 */
@Component
public class APIKeyAuthChannel {

    private final TenantAPIKeyService apiKeyService;
    private final TenantService tenantService;

    public APIKeyAuthChannel(TenantAPIKeyService apiKeyService, TenantService tenantService) {
        this.apiKeyService = apiKeyService;
        this.tenantService = tenantService;
    }

    /**
     * 尝试以 X-API-Key 认证。
     *
     * @return {@code true} = 认证通过（已写入上下文，调用方继续过滤链）；
     *         {@code false} = 已写出错误响应，调用方**不得**继续
     */
    public boolean authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        TenantAPIKey key;
        try {
            key = apiKeyService.authenticate(request.getHeader("X-API-Key"));
        } catch (TenantAPIKeyNotFoundException e) {
            // 不存在 / 已撤销 / 已过期 → 同一个响应（刻意的信息隐藏）
            writeJson(response, 401, "{\"error\":\"Unauthorized: invalid API key\"}");
            return false;
        }

        if (key.isPlatform()) {
            String tenantHeader = trimToEmpty(request.getHeader("X-Tenant-ID"));
            if (tenantHeader.isEmpty()) {
                if (!isPlatformTenantOptionalApi(request.getRequestURI(), request.getMethod())) {
                    // 键按字母序：code < error（对照 Go 的 gin.H）
                    writeJson(response, 409, "{\"code\":\"TENANT_REQUIRED\","
                            + "\"error\":\"Workspace required: platform API keys must send X-Tenant-ID\"}");
                    return false;
                }
                attachPlatformKey(key);
            } else {
                long targetTenantId = parseTenantHeader(tenantHeader, response);
                if (targetTenantId <= 0) {
                    return false;
                }
                if (!attachTenantKey(response, targetTenantId, key)) {
                    return false;
                }
            }
        } else {
            long tenantId = key.tenantIdValue();
            if (tenantId == 0L) {
                writeJson(response, 401, "{\"error\":\"Unauthorized: invalid API key scope\"}");
                return false;
            }
            String tenantHeader = trimToEmpty(request.getHeader("X-Tenant-ID"));
            if (!tenantHeader.isEmpty()) {
                long requestedTenantId = parseTenantHeader(tenantHeader, response);
                if (requestedTenantId <= 0) {
                    return false;
                }
                if (requestedTenantId != tenantId) {
                    writeJson(response, 403,
                            "{\"error\":\"Forbidden: workspace API key cannot switch workspaces\"}");
                    return false;
                }
            }
            if (!attachTenantKey(response, tenantId, key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对照 {@code attachPlatformAPIKeyAuthContext}：平台 Key 未指定 X-Tenant-ID 时
     * 的 tenantless 会话。角色固定 Viewer（只为兼容旧守卫，真正权威是能力清单）。
     */
    private void attachPlatformKey(TenantAPIKey key) {
        String principalId = String.valueOf(key.getId());
        TenantContext.set(null,
                new TenantContext.Principal(TenantContext.PrincipalTypes.API_PLATFORM, principalId),
                "viewer", false, platformSyntheticUserId(principalId), false);
        APIKeyScopeContext.set(new TenantAPIKeyScope(
                key.getId(), "platform", false, null, key.getCapabilities()));
    }

    /**
     * 对照 {@code attachAPIKeyAuthContext}：把 Key 绑定到某个租户会话。
     * 租户存在性与"Key 是否属于该租户"由调用方保证。
     *
     * @return false 表示已写响应（租户不存在）
     */
    private boolean attachTenantKey(HttpServletResponse response,
                                    long tenantId, TenantAPIKey key) throws IOException {
        Tenant tenant = tenantService.getTenantById(tenantId);
        if (tenant == null) {
            // 对照 Go：只记 warn，对外仍是统一的 invalid API key
            writeJson(response, 401, "{\"error\":\"Unauthorized: invalid API key\"}");
            return false;
        }

        String principalType;
        String principalId;
        String userId;
        if (key.isPlatform()) {
            principalType = TenantContext.PrincipalTypes.API_PLATFORM;
            principalId = String.valueOf(key.getId());
            userId = platformSyntheticUserId(principalId);
        } else {
            // 对照 resolveAPIPrincipal 的回落分支（见类注释"已知差异"）
            principalType = TenantContext.PrincipalTypes.API_TENANT;
            principalId = String.valueOf(tenantId);
            userId = "system-" + tenantId;
        }

        // 对照 Go：full-access 且非平台 → Owner，否则 Viewer。
        // 这只是"旧守卫兼容"的角色影子，真实权威是 FullAccess + Capabilities + KB 白名单。
        boolean fullAccess = key.isFullAccess() && !key.isPlatform();
        String role = fullAccess ? "owner" : "viewer";
        TenantContext.set(tenantId,
                new TenantContext.Principal(principalType, principalId),
                role, false, userId, false);
        APIKeyScopeContext.set(new TenantAPIKeyScope(
                key.getId(), key.getScopeType(), fullAccess, key.getKnowledgeBaseIds(), key.getCapabilities()));
        return true;
    }

    /**
     * 对照 {@code platformAPIKeyIdentity} 的合成用户 ID：
     * {@code platform-api-key-<keyID>}。平台 Key 借此保留**一个稳定的机器身份**，
     * 同时用 X-Tenant-ID 选择目标空间（邮箱格式 {@code ...@api-key.local} 同 Go）。
     */
    private static String platformSyntheticUserId(String principalId) {
        return "platform-api-key-" + principalId;
    }

    /**
     * 对照 {@code isPlatformTenantOptionalAPI}（auth.go）：
     * 平台 Key 没带 X-Tenant-ID 时，只有这几条控制面路由可以无空间放行。
     *
     * <p>对照 Go 的注释：必须**精确匹配** {@code /api/v1/system/admin} 前缀
     * （裸 {@code HasPrefix} 会误放行 {@code /api/v1/system/admin-foo} 这类同前缀路径）。</p>
     */
    public static boolean isPlatformTenantOptionalApi(String path, String method) {
        String p = trimToEmpty(path);
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.equals("/api/v1/system/admin") || p.startsWith("/api/v1/system/admin/")) {
            return true;
        }
        if ("GET".equals(method) && (p.equals("/api/v1/tenants/all") || p.equals("/api/v1/tenants/search"))) {
            return true;
        }
        return "POST".equals(method) && p.equals("/api/v1/tenants");
    }

    /** 解析并写出畸形的 X-Tenant-ID（对照 Go 的 {@code strconv.ParseUint} + 0 判定）。 */
    private static long parseTenantHeader(String header, HttpServletResponse response) throws IOException {
        long parsed;
        try {
            parsed = Long.parseLong(header);
        } catch (NumberFormatException e) {
            parsed = 0L;
        }
        if (parsed <= 0L) {
            writeJson(response, 400, "{\"error\":\"Invalid X-Tenant-ID header\"}");
            return 0L;
        }
        return parsed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
