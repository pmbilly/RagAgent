package com.ragagent.auth.apikey.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.ragagent.auth.apikey.domain.APIKeyScopeContext;
import com.ragagent.auth.apikey.domain.TenantAPIKey;
import com.ragagent.auth.apikey.domain.TenantAPIKeyScope;
import com.ragagent.auth.apikey.mapper.TenantAPIKeyNotFoundException;
import com.ragagent.auth.apikey.service.TenantAPIKeyService;
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
 * <p><b>调用位置</b>：{@code AuthFilter} 的第 3 条通道（Bearer → X-API-Key → 401，
 * 三通道顺序与 Go {@code Auth()} 一致）。2026-09-23 走查批把 API 主体解析补全：
 * {@code resolveAPIPrincipal} 的 direct_header / signed_token 两模式 + 首位用户
 * （GetUserByTenantID）路径全部接线，与 Go 逐行为一致。</p>
 *
 * <h2>成功时写入了什么</h2>
 * <ul>
 *   <li>{@link TenantContext}：tenantId / principal（api_tenant、api_platform 或
 *       api_external_user）/ role（viewer，full-access 租户 Key 为 owner）/ 用户 ID
 *       （租户首位用户，无则合成 {@code system-<tenantId>}；平台 Key 恒合成身份）；</li>
 *   <li>{@link APIKeyScopeContext}：供 {@link APIKeyGateInterceptor} 与下游
 *       KB 白名单判定使用。这是"JWT 直通 / API Key 受门禁"的判定依据。</li>
 * </ul>
 *
 * <h2>已知差异（相对于 Go）</h2>
 * <p>无行为差异。Go 中间件的 external-user 头名是**常量**（X-External-User-ID /
 * X-External-User-Token，auth.go L23-24），并不读配置里的自定义头名——Java 原样照抄
 * 这一怪癖。signed_token 校验为手写 HMAC-SHA256（golang-jwt 不限密钥长度，
 * jjwt 会拒短密钥，为逐输入等价故手工实现）。</p>
 */
@Component
public class APIKeyAuthChannel {

    /** 对照 auth.go L23-26 的常量（Go 中间件不读 cfg 里的自定义头名，原样照抄）。 */
    private static final String EXTERNAL_USER_ID_HEADER = "X-External-User-ID";
    private static final String EXTERNAL_USER_TOKEN_HEADER = "X-External-User-Token";
    private static final int MAX_EXTERNAL_USER_ID_LEN = 128;
    private static final long MAX_EXTERNAL_USER_TOKEN_TTL_SECONDS = 24 * 3600L;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    private final TenantAPIKeyService apiKeyService;
    private final TenantService tenantService;

    private final com.ragagent.auth.service.UserService userService;

    public APIKeyAuthChannel(TenantAPIKeyService apiKeyService, TenantService tenantService,
            com.ragagent.auth.service.UserService userService) {
        this.apiKeyService = apiKeyService;
        this.tenantService = tenantService;
        this.userService = userService;
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
                if (!attachTenantKey(request, response, targetTenantId, key)) {
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
            if (!attachTenantKey(request, response, tenantId, key)) {
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
    /** 对照 attachTenantKey：租户身份 + 首位用户（或合成用户）+ API 主体模式解析。 */
    private boolean attachTenantKey(HttpServletRequest request, HttpServletResponse response,
                                    long tenantId, TenantAPIKey key) throws IOException {
        Tenant tenant = tenantService.getTenantById(tenantId);
        if (tenant == null) {
            // 对照 Go：只记 warn，对外仍是统一的 invalid API key
            writeJson(response, 401, "{\"error\":\"Unauthorized: invalid API key\"}");
            return false;
        }

        // 对照 attachAPIKeyAuthContext L512-523：租户首位用户（GetUserByTenantID，
        // created_at 最早），查不到走合成用户兜底 system-<tenantId>（错误一律吞掉走兜底）。
        com.ragagent.auth.domain.User user = userService.getUserByTenantIdFirst(tenantId);
        String userId = user != null ? user.getId() : "system-" + tenantId;

        String principalType;
        String principalId;
        if (key.isPlatform()) {
            principalType = TenantContext.PrincipalTypes.API_PLATFORM;
            principalId = String.valueOf(key.getId());
            userId = platformSyntheticUserId(principalId);
        } else {
            // 对照 resolveAPIPrincipal（auth.go L561-620）：按租户 API principal
            // 模式解析主体；配置缺失/tenant 模式回落 api_tenant/<tenantId>。
            ApiPrincipalResolutionOut resolution = resolveApiPrincipal(tenantId,
                    tenant.getApiPrincipalConfig(), request);
            if (resolution.error() != null) {
                writeJson(response, 401,
                        "{\"error\":\"" + resolution.error() + "\"}");
                return false;
            }
            principalType = resolution.principalType();
            principalId = resolution.principalId();
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

    /** 对照 resolveAPIPrincipal 的返回（principal 或 401 文案）。 */
    private record ApiPrincipalResolutionOut(String principalType, String principalId,
            String error) {}

    private ApiPrincipalResolutionOut resolveApiPrincipal(long tenantId,
            com.ragagent.auth.domain.APIPrincipalConfig cfg, HttpServletRequest request) {
        // Go L566-576：fallback = api_tenant/<tenantId>；cfg 缺失/mode 空/tenant 模式 → 回落
        if (cfg == null || cfg.mode == null || cfg.mode.isEmpty()
                || com.ragagent.auth.domain.APIPrincipalConfig.MODE_TENANT.equals(cfg.mode)) {
            return fallbackTenant(tenantId);
        }
        switch (cfg.mode) {
            case com.ragagent.auth.domain.APIPrincipalConfig.MODE_DIRECT_HEADER -> {
                // Go 用常量头名 X-External-User-ID（不读 cfg.directHeaderName——原样照抄）
                String externalUserId = trimToEmpty(
                        request.getHeader(EXTERNAL_USER_ID_HEADER));
                if (externalUserId.isEmpty()) {
                    if (cfg.requireDirectHeader) {
                        return new ApiPrincipalResolutionOut(null, null,
                                "Unauthorized: missing external user id header");
                    }
                    return fallbackTenant(tenantId);
                }
                if (!isValidExternalUserId(externalUserId)) {
                    return new ApiPrincipalResolutionOut(null, null,
                            "Unauthorized: invalid external user id");
                }
                return new ApiPrincipalResolutionOut(TenantContext.PrincipalTypes.API_EXTERNAL_USER,
                        tenantId + ":" + externalUserId, null);
            }
            case com.ragagent.auth.domain.APIPrincipalConfig.MODE_SIGNED_TOKEN -> {
                String token = trimToEmpty(request.getHeader(EXTERNAL_USER_TOKEN_HEADER));
                String sub = verifyExternalUserJwt(token, tenantId,
                        cfg.hmacSecret == null ? "" : cfg.hmacSecret);
                if (sub == null) {
                    return new ApiPrincipalResolutionOut(null, null,
                            "Unauthorized: invalid external user token");
                }
                return new ApiPrincipalResolutionOut(TenantContext.PrincipalTypes.API_EXTERNAL_USER,
                        tenantId + ":" + sub, null);
            }
            default -> {
                return fallbackTenant(tenantId);
            }
        }
    }

    private ApiPrincipalResolutionOut fallbackTenant(long tenantId) {
        return new ApiPrincipalResolutionOut(TenantContext.PrincipalTypes.API_TENANT,
                String.valueOf(tenantId), null);
    }

    /** 对照 validateExternalUserID（L645-657）：空 / >128 码点 / 控制字符拒绝。 */
    private static boolean isValidExternalUserId(String id) {
        if (id.isEmpty()) {
            return false;
        }
        if (id.length() > MAX_EXTERNAL_USER_ID_LEN) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对照 verifyExternalUserJWT（L610-643 + validate/claims 检查）：HS256 手工
     * 校验（golang-jwt 不限密钥长度，jjwt 会拒短密钥——为逐输入等价故手写 HMAC）。
     * 校验链：三段式 → alg=HS256 → 签名 → aud 含 "weknora" → exp 必需 →
     * TTL ≤ 24h → nbf → tenant_id 匹配 → sub 非空。失败返回 null。
     */
    private static String verifyExternalUserJwt(String token, long tenantId, String secret) {
        if (token.isEmpty() || secret.isEmpty()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode header = JSON_MAPPER.readTree(
                    java.util.Base64.getUrlDecoder().decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText(""))) {
                return null;
            }
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signingInput = (parts[0] + "." + parts[1])
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] expected = mac.doFinal(signingInput);
            byte[] provided = java.util.Base64.getUrlDecoder().decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expected, provided)) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode claims = JSON_MAPPER.readTree(
                    java.util.Base64.getUrlDecoder().decode(parts[1]));
            // aud 含 "weknora"（字符串或数组）
            com.fasterxml.jackson.databind.JsonNode aud = claims.get("aud");
            boolean audOk = false;
            if (aud != null && aud.isTextual()) {
                audOk = "weknora".equals(aud.asText());
            } else if (aud != null && aud.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode a : aud) {
                    if ("weknora".equals(a.asText())) {
                        audOk = true;
                        break;
                    }
                }
            }
            if (!audOk) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode exp = claims.get("exp");
            if (exp == null || !exp.canConvertToLong()) {
                return null; // WithExpirationRequired
            }
            long expAt = exp.asLong();
            long now = System.currentTimeMillis() / 1000L;
            if (expAt <= 0 || now >= expAt) {
                return null;
            }
            if (expAt - now > MAX_EXTERNAL_USER_TOKEN_TTL_SECONDS) {
                return null; // token lifetime exceeds 24h
            }
            com.fasterxml.jackson.databind.JsonNode nbf = claims.get("nbf");
            if (nbf != null && nbf.canConvertToLong() && now < nbf.asLong()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode tenantClaim = claims.get("tenant_id");
            long gotTenant = tenantClaim != null && tenantClaim.canConvertToLong()
                    && tenantClaim.asLong() > 0 ? tenantClaim.asLong() : 0;
            if (gotTenant != tenantId) {
                return null;
            }
            String sub = claims.path("sub").asText("").trim();
            return sub.isEmpty() ? null : sub;
        } catch (RuntimeException | java.security.GeneralSecurityException | IOException e) {
            return null;
        }
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
