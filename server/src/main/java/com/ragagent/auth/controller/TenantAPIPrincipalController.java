package com.ragagent.auth.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.APIPrincipalConfig;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.dto.APIPrincipalDtos.APIPrincipalConfigRequest;
import com.ragagent.auth.dto.APIPrincipalDtos.APIPrincipalConfigResponse;
import com.ragagent.auth.dto.APIPrincipalDtos.APIPrincipalTestTokenRequest;
import com.ragagent.auth.dto.APIPrincipalDtos.APIPrincipalTestTokenResponse;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.ErrorCode;
import com.ragagent.common.web.GoJsonBindError;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照 Go internal/handler/tenant.go L869-1110 的 api-principal 三条路由（Owner+）：
 * GET/PUT /tenants/{id}/api-principal-config、POST /tenants/{id}/api-principal-test-token。
 *
 * <p><b>缺省归一</b>（apiPrincipalConfigForResponse）：cfg 为 nil → mode=tenant +
 * 两个默认头名；direct_header_name / signed_token_header_name **恒**输出 Go 的
 * 默认常量（不回显存储值）；hmac_secret 永不回显，只回 has_hmac_secret。</p>
 *
 * <p><b>*** 占位符</b>：GET 不再披露明文，客户端编辑配置时把打码值原样交回表示
 * "保留存量密钥"——PUT 对 "***" 特判为 no-op（golden mb-apc-put-placeholder 钉住）。
 * 显式 null / 缺省同义（Go 的 *string nil）；显式空串 = 清空。</p>
 *
 * <p><b>已知差异</b>：test-token 的 HS256 签名走 jjwt——对 &lt;256bit 的 hmac_secret
 * jjwt 签名端抛 WeakKeyException → 500（Go 的 jwt 库接受任意长度密钥）。dev/生产
 * 密钥均 ≥32 字节，golden 未触及该分叉。</p>
 */
@RestController
public class TenantAPIPrincipalController {

    static final String DEFAULT_DIRECT_HEADER = "X-External-User-ID";
    static final String DEFAULT_TOKEN_HEADER = "X-External-User-Token";
    static final Duration DEFAULT_TEST_TOKEN_TTL = Duration.ofMinutes(15);
    static final Duration MAX_TEST_TOKEN_TTL = Duration.ofHours(1);
    static final int MAX_EXTERNAL_USER_ID_LEN = 128;
    /** 客户端回传的打码占位符——PUT 收到它表示"保留存量密钥" */
    static final String SECRET_REDACTED = "***";

    /**
     * 请求绑定的 mapper：Go 的 json.Unmarshal 默认忽略未知字段 →
     * FAIL_ON_UNKNOWN_PROPERTIES 关掉（请求体带多余键不报错）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final TenantService tenantService;

    public TenantAPIPrincipalController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    /** GET /tenants/{id}/api-principal-config（Owner+） */
    @GetMapping("/api/v1/tenants/{id}/api-principal-config")
    public Map<String, Object> getAPIPrincipalConfig(@PathVariable String id) {
        long tenantId = parseTenantId(id);
        Tenant tenant = loadTenant(tenantId);
        return TenantMemberController.envelope(configForResponse(tenant.getApiPrincipalConfig()));
    }

    /** PUT /tenants/{id}/api-principal-config（Owner+；*** = 保留存量密钥） */
    @PutMapping("/api/v1/tenants/{id}/api-principal-config")
    public Map<String, Object> updateAPIPrincipalConfig(@PathVariable String id, HttpServletRequest request) {
        long tenantId = parseTenantId(id);
        String rawBody = TenantMemberController.rawBody(request);
        APIPrincipalConfigRequest req = bindRequest(rawBody);

        String mode = req.mode() == null || req.mode().isEmpty()
                ? APIPrincipalConfig.MODE_TENANT
                : req.mode();
        switch (mode) {
            case APIPrincipalConfig.MODE_TENANT:
            case APIPrincipalConfig.MODE_DIRECT_HEADER:
            case APIPrincipalConfig.MODE_SIGNED_TOKEN:
                break;
            default:
                throw new BizException(AppError.validation("mode must be tenant, direct_header, or signed_token"));
        }

        Tenant tenant = loadTenant(tenantId);
        APIPrincipalConfig existing = tenant.getApiPrincipalConfig();
        String existingSecret = existing == null ? "" : existing.hmacSecret;
        String hmacSecret = existingSecret;
        if (req.hasHmacSecret()) {
            String provided = req.hmacSecretValue() == null ? "" : req.hmacSecretValue().trim();
            // GET 不披露明文 → 客户端回传打码占位符 = "保留存量密钥"，视为 no-op
            if (!SECRET_REDACTED.equals(provided)) {
                hmacSecret = provided;
            }
        }
        APIPrincipalConfig cfg = new APIPrincipalConfig();
        cfg.mode = mode;
        cfg.directHeaderName = DEFAULT_DIRECT_HEADER;
        cfg.signedTokenHeaderName = DEFAULT_TOKEN_HEADER;
        cfg.requireDirectHeader = req.requireDirectHeader();
        cfg.hmacSecret = hmacSecret;
        if (APIPrincipalConfig.MODE_SIGNED_TOKEN.equals(cfg.mode)
                && (cfg.hmacSecret == null || cfg.hmacSecret.trim().isEmpty())) {
            throw new BizException(AppError.validation("hmac_secret is required for signed_token mode"));
        }
        tenant.setApiPrincipalConfig(cfg);

        Tenant updated = tenantService.updateTenant(tenant);
        return TenantMemberController.envelope(configForResponse(updated.getApiPrincipalConfig()));
    }

    /** POST /tenants/{id}/api-principal-test-token（Owner+；HS256，TTL 15min 缺省 / 1h 上限） */
    @PostMapping("/api/v1/tenants/{id}/api-principal-test-token")
    public Map<String, Object> createAPIPrincipalTestToken(@PathVariable String id, HttpServletRequest request) {
        long tenantId = parseTenantId(id);
        APIPrincipalTestTokenRequest req = bindTestTokenRequest(TenantMemberController.rawBody(request));

        String externalUserId = req.externalUserId() == null ? "" : req.externalUserId().trim();
        validateExternalUserId(externalUserId);

        Tenant tenant = loadTenant(tenantId);
        APIPrincipalConfig cfg = tenant.getApiPrincipalConfig();
        if (cfg == null || !APIPrincipalConfig.MODE_SIGNED_TOKEN.equals(cfg.mode)) {
            throw new BizException(AppError.validation("signed_token mode is required"));
        }
        String secret = cfg.hmacSecret == null ? "" : cfg.hmacSecret.trim();
        if (secret.isEmpty()) {
            throw new BizException(AppError.validation("hmac_secret is required for signed_token mode"));
        }

        Duration ttl = DEFAULT_TEST_TOKEN_TTL;
        if (req.expiresInSeconds() > 0) {
            ttl = Duration.ofSeconds(req.expiresInSeconds());
        }
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TEST_TOKEN_TTL) > 0) {
            throw new BizException(AppError.validation("expires_in_seconds must be between 1 and 3600"));
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String token;
        try {
            // claims 为 map（jwt.MapClaims）→ encoding/json 序列化按字母序：
            // aud < exp < iat < sub < tenant_id（jjwt 按插入序，故按字母序插入）
            token = Jwts.builder()
                    .claim("aud", "weknora")
                    .expiration(Date.from(expiresAt))
                    .issuedAt(Date.from(now))
                    .claim("sub", externalUserId)
                    .claim("tenant_id", String.valueOf(tenantId))
                    .signWith(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "HmacSHA256"))
                    .compact();
        } catch (RuntimeException e) {
            // jjwt 对 <256bit 密钥抛 WeakKeyException（Go 无此限制）——已知差异，见类注释
            throw new BizException(AppError.internal("Failed to create API principal test token")
                    .withDetails(e.getMessage()));
        }

        return TenantMemberController.envelope(new APIPrincipalTestTokenResponse(
                token,
                DEFAULT_TOKEN_HEADER,
                (int) ttl.getSeconds(),
                expiresAt.getEpochSecond(),
                externalUserId));
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /** 对照 apiPrincipalConfigForResponse：缺省归一 + 密钥打码（只回 has_hmac_secret） */
    static APIPrincipalConfigResponse configForResponse(APIPrincipalConfig cfg) {
        if (cfg == null) {
            cfg = new APIPrincipalConfig();
        }
        String mode = cfg.mode == null || cfg.mode.isEmpty() ? APIPrincipalConfig.MODE_TENANT : cfg.mode;
        return new APIPrincipalConfigResponse(
                mode,
                DEFAULT_DIRECT_HEADER,
                DEFAULT_TOKEN_HEADER,
                cfg.requireDirectHeader,
                cfg.hmacSecret != null && !cfg.hmacSecret.trim().isEmpty());
    }

    /** 对照 validateAPIPrincipalExternalUserID：必填 / ≤128 / 无控制字符 */
    private static void validateExternalUserId(String id) {
        String problem = null;
        if (id.isEmpty()) {
            problem = "external_user_id is required";
        } else if (id.length() > MAX_EXTERNAL_USER_ID_LEN) {
            problem = "external_user_id is too long";
        } else {
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                if (c < 0x20 || c == 0x7f) {
                    problem = "external_user_id contains invalid characters";
                    break;
                }
            }
        }
        if (problem != null) {
            // Go：handler 把 AppError 的 err.Error() 塞进 details → "error code: 1010,
            // error message: …" 双前缀原文（AppError.Error() 的形态，照抄）
            String doublePrefixed = new BizException(AppError.validation(problem)).getMessage();
            throw new BizException(AppError.validation("external_user_id is invalid").withDetails(doublePrefixed));
        }
    }

    private Tenant loadTenant(long tenantId) {
        Tenant tenant = tenantService.getTenantById(tenantId);
        if (tenant == null) {
            // GetTenantByID 的 gorm.ErrRecordNotFound 非业务错误 → 500 + err 原文 details
            throw new BizException(AppError.internal("Failed to load workspace").withDetails("record not found"));
        }
        return tenant;
    }

    private static long parseTenantId(String raw) {
        try {
            long v = Long.parseLong(raw == null ? "" : raw.trim());
            if (v > 0) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        // 不可达死代码：PathTenantMatch 在 handler 之前以同码 1010 拒绝（§9 audit 回补）
        throw new BizException(AppError.badRequest("Invalid workspace ID"));
    }

    private static APIPrincipalConfigRequest bindRequest(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.validation("Invalid request data")
                    .withDetails(GoJsonBindError.message(null, null)));
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            return MAPPER.treeToValue(node, APIPrincipalConfigRequest.class);
        } catch (Exception e) {
            throw new BizException(AppError.validation("Invalid request data")
                    .withDetails(GoJsonBindError.message(rawBody, e.getMessage())));
        }
    }

    private static APIPrincipalTestTokenRequest bindTestTokenRequest(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new BizException(AppError.validation("Invalid request data")
                    .withDetails(GoJsonBindError.message(null, null)));
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            return MAPPER.treeToValue(node, APIPrincipalTestTokenRequest.class);
        } catch (Exception e) {
            throw new BizException(AppError.validation("Invalid request data")
                    .withDetails(GoJsonBindError.message(rawBody, e.getMessage())));
        }
    }
}
