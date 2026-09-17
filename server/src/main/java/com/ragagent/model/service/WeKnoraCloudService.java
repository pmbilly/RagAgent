package com.ragagent.model.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.service.TenantService;
import com.ragagent.common.crypto.CryptoService;
import com.ragagent.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对照 Go internal/application/service/weknoracloud.go（SaveCredentials + CheckStatus）。
 *
 * 注意 CheckStatus 的实际行为以代码为准（注释与实现有出入）：
 * CredentialsConfig.Scan 读库时已宽容解密——失败会把 app_secret 置空，
 * 因此 enc:v1: 前缀永远不会到达 CheckStatus，needs_reinit=true 分支在 Go 中不可达。
 * Java 复刻同样链路：读库 → 宽容解密 → GetWeKnoraCloud() 要求 app_id+app_secret 均非空。
 */
@Service
public class WeKnoraCloudService {

    private static final Logger log = LoggerFactory.getLogger(WeKnoraCloudService.class);
    /** 对照 provider.WeKnoraCloudBaseURL */
    public static final String BASE_URL = "https://weknora.weixin.qq.com";

    private final TenantService tenantService;
    private final TenantMapper tenantMapper;
    private final CryptoService cryptoService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WeKnoraCloudService(TenantService tenantService, TenantMapper tenantMapper,
                               CryptoService cryptoService) {
        this.tenantService = tenantService;
        this.tenantMapper = tenantMapper;
        this.cryptoService = cryptoService;
    }

    /** 对照 SaveCredentials：先校验凭证（真实外呼），再落库到 tenants.credentials */
    public void saveCredentials(String appId, String appSecret) {
        if (appId == null || appId.isEmpty()) {
            throw new IllegalArgumentException("app_id is required");
        }
        if (appSecret == null || appSecret.isEmpty()) {
            throw new IllegalArgumentException("app_secret is required");
        }
        try {
            verifyCredentials(appId, appSecret);
        } catch (Exception e) {
            throw new IllegalArgumentException("credential verification failed: " + e.getMessage());
        }
        long tenantId = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        Tenant tenant = tenantService.getTenantById(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("tenant not found");
        }
        // 对照 CredentialsConfig.Value()：落库前 AES-256-GCM 加密 app_secret
        String encrypted = cryptoService.encryptAESGCM(appSecret, cryptoService.getAESKey());
        tenant.setCredentials(credentialsNode(appId, encrypted));
        // credentials 列由 JacksonTypeHandler 原样写回（app_secret 已加密，对照 Value()）
        tenantMapper.updateById(tenant);
    }

    /** 对照 verifyCredentials：GET {base}/api/v1/health + 签名头，10s 超时 */
    private void verifyCredentials(String appId, String appSecret) throws Exception {
        String healthUrl = BASE_URL + "/api/v1/health";
        String requestId = "verify-" + System.nanoTime();
        Map<String, String> signHeaders = sign(appId, appSecret, requestId, "{}");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .GET()
                .timeout(Duration.ofSeconds(10));
        signHeaders.forEach(builder::header);
        log.info("credential verification request: method=GET url={} app_id={} request_id={}",
                healthUrl, appId, requestId);
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("credential verification HTTP failed: url={} err={}", healthUrl, e.toString());
            throw new IllegalStateException("service unreachable: " + e.getMessage());
        }
        int status = resp.statusCode();
        if (status == 401 || status == 403) {
            throw new IllegalStateException("invalid APPID or APPSECRET (HTTP " + status + ")");
        }
        if (status != 200) {
            throw new IllegalStateException("invalid response status code: " + status);
        }
    }

    /**
     * 对照 CheckStatus。
     * @return ObjectNode {"has_models":..,"needs_reinit":..,"reason"(omitempty)}——struct 字段序
     */
    public JsonNode checkStatus() {
        long tenantId = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        com.fasterxml.jackson.databind.node.ObjectNode result =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        Tenant tenant = tenantService.getTenantById(tenantId);
        if (tenant == null || tenant.getCredentials() == null) {
            result.put("has_models", false);
            result.put("needs_reinit", false);
            return result;
        }
        JsonNode creds = tenant.getCredentials().get("weknoracloud");
        // 对照 CredentialsConfig.Scan：读库时已宽容解密；此处再按 lenient 语义取明文
        String appId = creds != null && creds.get("app_id") != null ? creds.get("app_id").asText() : "";
        String appSecret = "";
        if (creds != null && creds.get("app_secret") != null) {
            var decrypted = cryptoService.decryptStoredSecretLenient(creds.get("app_secret").asText());
            appSecret = decrypted.ok() ? decrypted.plaintext() : "";
        }
        // 对照 GetWeKnoraCloud()：app_id / app_secret 均非空才算已配置
        if (appId.isEmpty() || appSecret.isEmpty()) {
            result.put("has_models", false);
            result.put("needs_reinit", false);
            return result;
        }
        result.put("has_models", true);
        result.put("needs_reinit", false);
        return result;
    }

    private static JsonNode credentialsNode(String appId, String encryptedSecret) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        var wc = root.putObject("weknoracloud");
        wc.put("app_id", appId);
        wc.put("app_secret", encryptedSecret == null ? "" : encryptedSecret);
        return root;
    }

    // ── 对照 internal/models/utils/signer.go Sign ─────────────────────────

    private static final String NONCE_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Random RANDOM = new SecureRandom();

    static Map<String, String> sign(String appId, String apiKey, String requestId, String bodyJson) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        StringBuilder nonce = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            nonce.append(NONCE_CHARS.charAt(RANDOM.nextInt(NONCE_CHARS.length())));
        }
        String bodyForHash = bodyJson == null || bodyJson.isEmpty() ? "{}" : bodyJson;
        String bodyMd5 = md5Hex(bodyForHash);
        Map<String, String> params = new TreeMap<>(Map.of(
                "x-appid", appId,
                "x-api-key", apiKey,
                "x-request-id", requestId,
                "x-timestamp", timestamp,
                "x-nonce", nonce.toString(),
                "body", bodyMd5));
        String signature = md5Hex(params.entrySet().stream()
                .map(e -> rfc3986Encode(e.getKey()) + "=" + rfc3986Encode(e.getValue()))
                .collect(Collectors.joining("&")));
        return Map.of(
                "X-APPID", appId,
                "X-API-Key", apiKey,
                "X-Request-ID", requestId,
                "X-Timestamp", timestamp,
                "X-Nonce", nonce.toString(),
                "X-Signature", signature);
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC3986 编码：保留 A-Z a-z 0-9 - _ . ~，其余 %XX（对照 rfc3986Encode） */
    static String rfc3986Encode(String s) {
        StringBuilder buf = new StringBuilder();
        for (char r : s.toCharArray()) {
            if ((r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z')
                    || (r >= '0' && r <= '9') || r == '-' || r == '_' || r == '.' || r == '~') {
                buf.append(r);
            } else {
                buf.append(String.format(Locale.ROOT, "%%%02X", (int) r));
            }
        }
        return buf.toString();
    }
}
