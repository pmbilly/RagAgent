package com.ragagent.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import com.ragagent.common.security.SsrfGuard;
import org.springframework.stereotype.Component;

/**
 * OIDC 业务。
 *
 * 实现边界：只实现确定性部分（配置校验 + 授权 URL 构造）。
 * enabled 之后的 discovery 抓取（网络）与 code 交换
 * （网络 + JWKS + 建号）
 * 整体推迟——dev 两侧 OIDC 均 disabled，这些分支不可达。
 */
@Component
public class OidcService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OidcConfig config;
    private final OidcStateCodec stateCodec;
    private final SsrfGuard ssrfGuard;

    public OidcService(OidcConfig config, OidcStateCodec stateCodec, SsrfGuard ssrfGuard) {
        this.config = config;
        this.stateCodec = stateCodec;
        this.ssrfGuard = ssrfGuard;
    }

    /** service 层错误；消息会进 403 details / login_failed description */
    public static class OidcException extends RuntimeException {
        public OidcException(String message) {
            super(message);
        }
    }

    /** 授权 URL 构造结果（nonce 不进 JSON，由 controller 绑 cookie） */
    public record AuthorizationUrl(String providerDisplayName, String authorizationUrl,
                                   String state, String nonce) {
    }

    /** 配置就绪校验：启用开关 + 显式端点或 discovery 缺一不可 + 各端点 SSRF 校验 */
    public OidcConfig requireConfig() {
        if (config == null || !config.isEnable()) {
            throw new OidcException("OIDC login is disabled");
        }
        boolean needsDiscovery = isBlank(config.getAuthorizationEndpoint())
                || isBlank(config.getTokenEndpoint())
                || isBlank(config.getJwksUri())
                || isBlank(config.getIssuerUrl());
        if (needsDiscovery) {
            if (isBlank(config.getDiscoveryUrl())) {
                if (isBlank(config.getAuthorizationEndpoint()) || isBlank(config.getTokenEndpoint())) {
                    throw new OidcException("OIDC discovery_url or explicit endpoints are required");
                }
            } else {
                // discovery 的 HTTP 抓取不实现（dev 不可达）
                throw new OidcException("OIDC discovery document loading is not available in this deployment");
            }
        }
        if (isBlank(config.getAuthorizationEndpoint()) || isBlank(config.getTokenEndpoint())) {
            throw new OidcException("OIDC discovery document missing required endpoints");
        }
        validateEndpoint("authorization", config.getAuthorizationEndpoint(), true);
        validateEndpoint("token", config.getTokenEndpoint(), true);
        validateEndpoint("userinfo", config.getUserInfoEndpoint(), false);
        validateEndpoint("jwks", config.getJwksUri(), false);
        return config;
    }

    /** 端点 SSRF 校验 */
    private void validateEndpoint(String label, String endpoint, boolean required) {
        String ep = endpoint == null ? "" : UserService.goTrimSpace(endpoint);
        if (ep.isEmpty()) {
            if (required) {
                throw new OidcException("OIDC " + label + " endpoint is required");
            }
            return;
        }
        try {
            ssrfGuard.validateURLForSSRF(ep);
        } catch (SsrfGuard.SsrfException e) {
            throw new OidcException("OIDC " + label + " endpoint failed SSRF validation: " + e.getMessage());
        }
    }

    /** 构造授权 URL。 */
    public AuthorizationUrl getAuthorizationUrl(String redirectUri) {
        OidcConfig cfg = requireConfig();
        if (isBlank(redirectUri)) {
            throw new OidcException("redirect_uri is required");
        }
        // 24 随机字节 base64url 无填充（32 字符）
        byte[] nonceBytes = new byte[24];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);

        String state = stateCodec.sign(nonce, UserService.goTrimSpace(redirectUri), 0);

        // query 键按字母序
        StringBuilder query = new StringBuilder();
        appendQuery(query, "client_id", cfg.getClientId());
        appendQuery(query, "redirect_uri", redirectUri);
        appendQuery(query, "response_type", "code");
        appendQuery(query, "scope", String.join(" ", cfg.getScopes()));
        appendQuery(query, "state", state);

        String authUrl = cfg.getAuthorizationEndpoint();
        authUrl += (authUrl.contains("?") ? "&" : "?") + query;
        return new AuthorizationUrl(cfg.getProviderDisplayName(), authUrl, state, nonce);
    }

    /**
     * 登录门控：code/redirect_uri 空值检查 + 配置就绪门。
     * 其后的 code 交换 / userinfo / provisioning 整体推迟。
     * provisioning 参数保留占位（UserService.PROVISIONING_*），当前不读。
     */
    public void loginWithOidc(String code, String redirectUri, String provisioning) {
        if (isBlank(code)) {
            throw new OidcException("code is required");
        }
        if (isBlank(redirectUri)) {
            throw new OidcException("redirect_uri is required");
        }
        requireConfig();
        // code 交换起的网络步不实现（dev OIDC disabled，不可达）
        throw new OidcException("OIDC code exchange is not available in this deployment");
    }

    private static void appendQuery(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(goQueryEscape(key)).append('=').append(goQueryEscape(value));
    }

    /**
     * Query 值转义：alnum 与 - _ . ~ 原样，空格 → +，
     * 其余按 UTF-8 字节 %XX（大写 hex）。注意 Java URLEncoder 会把 ~ 编成 %7E，不可用。
     */
    static String goQueryEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static boolean isBlank(String s) {
        return s == null || UserService.goTrimSpace(s).isEmpty();
    }
}
