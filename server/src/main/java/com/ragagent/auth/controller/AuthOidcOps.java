package com.ragagent.auth.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.auth.service.OidcService;
import com.ragagent.auth.service.UserService;
import com.ragagent.auth.service.OidcStateCodec;
import com.ragagent.common.error.AppError;
import com.ragagent.auth.dto.OidcAuthUrlResponse;
import com.ragagent.auth.dto.OidcConfigResponse;
import com.ragagent.common.error.BizException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;

/**
 * AuthController 的 OIDC 簇：/oidc/config|url|start|callback 四端点执行体与
 * 共享辅助（nonce cookie 绑定、state 解码、302 字节形态、HTML/URL 转义）。
 */
final class AuthOidcOps {

    private static final Logger log = LoggerFactory.getLogger(AuthOidcOps.class);

    private final AuthController service;

    AuthOidcOps(AuthController service) {
        this.service = service;
    }

    // ── GET /oidc/config（无鉴权公共读） ────────────────────────────────────

    ResponseEntity<OidcConfigResponse> getOidcConfig() {
        boolean enabled = service.oidcConfig != null && service.oidcConfig.isEnable();
        String providerDisplayName = service.oidcConfig == null ? ""
                : UserService.goTrimSpace(service.oidcConfig.getProviderDisplayName());
        return ResponseEntity.ok(new OidcConfigResponse(enabled, providerDisplayName));
    }

    // ── GET /oidc/url ───────────────────────────────────────────────────────

    ResponseEntity<OidcAuthUrlResponse> getOidcAuthorizationUrl(
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            HttpServletRequest request, HttpServletResponse response) {
        String trimmed = UserService.goTrimSpace(redirectUri == null ? "" : redirectUri);
        if (trimmed.isEmpty()) {
            throw new BizException(AppError.validation("redirect_uri is required"));
        }
        OidcService.AuthorizationUrl result = authorizationUrlOr403(trimmed);
        // 绑定 state nonce 到浏览器，防授权码被重放到受害者回调（对照 setOIDCNonceCookie）
        setOidcNonceCookie(request, response, result.nonce());
        return ResponseEntity.ok(new OidcAuthUrlResponse(result.providerDisplayName(),
                result.authorizationUrl(), result.state()));
    }

    // ── GET /oidc/start（直接 302 到 IdP） ─────────────────────────────────

    ResponseEntity<String> oidcStart(HttpServletRequest request, HttpServletResponse response) {
        OidcService.AuthorizationUrl result = authorizationUrlOr403(oidcCallbackUrl(request));
        setOidcNonceCookie(request, response, result.nonce());
        return AuthOidcOps.redirectFound(result.authorizationUrl());
    }

    // ── GET /oidc/callback ──────────────────────────────────────────────────

    ResponseEntity<String> oidcRedirectCallback(
            @RequestParam(value = "error", required = false) String providerError,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "code", required = false) String code,
            HttpServletRequest request, HttpServletResponse response) {
        final String frontendRedirectUri = "/";

        String err = UserService.goTrimSpace(providerError == null ? "" : providerError);
        if (!err.isEmpty()) {
            String redirectUrl = frontendRedirectUri + "#oidc_error=" + AuthOidcOps.urlQueryEscape(err);
            String desc = UserService.goTrimSpace(errorDescription == null ? "" : errorDescription);
            if (!desc.isEmpty()) {
                redirectUrl += "&oidc_error_description=" + AuthOidcOps.urlQueryEscape(desc);
            }
            return AuthOidcOps.redirectFound(redirectUrl);
        }

        OidcStateCodec.Payload decoded = decodeOidcState(
                UserService.goTrimSpace(state == null ? "" : state), request);
        if (decoded == null) {
            return AuthOidcOps.redirectFound(frontendRedirectUri + "#oidc_error=" + AuthOidcOps.urlQueryEscape("invalid_state"));
        }
        // 一次性：校验后立即清除绑定 cookie（对照 c.SetCookie(name, "", -1, ...) 的字节形态）
        response.addHeader("Set-Cookie", AuthOidcOps.OIDC_NONCE_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly");

        String trimmedCode = UserService.goTrimSpace(code == null ? "" : code);
        if (trimmedCode.isEmpty()) {
            return AuthOidcOps.redirectFound(frontendRedirectUri + "#oidc_error=" + AuthOidcOps.urlQueryEscape("missing_code"));
        }

        try {
            service.oidcService.loginWithOidc(trimmedCode, UserService.goTrimSpace(decoded.redirectUri()),
                    service.resolveDefaultTenantMode());
        } catch (OidcService.OidcException e) {
            return AuthOidcOps.redirectFound(frontendRedirectUri + "#oidc_error=" + AuthOidcOps.urlQueryEscape("login_failed")
                    + "&oidc_error_description=" + AuthOidcOps.urlQueryEscape(e.getMessage()));
        }
        // 成功分支不可达：loginWithOidc 的网络步整体推迟，必抛 OidcException。
        // encodeOIDCCallbackPayload / !resp.Success 分支随之推迟。
        throw new BizException(AppError.internal("OIDC callback success path is not available"));
    }

    // ── OIDC 共享辅助 ───────────────────────────────────────────────────────

    /** OIDC nonce cookie 的名字与有效期。 */
    static final String OIDC_NONCE_COOKIE_NAME = "weknora_oidc_nonce";
    static final int OIDC_NONCE_COOKIE_MAX_AGE = 600;


    OidcService.AuthorizationUrl authorizationUrlOr403(String redirectUri) {
        try {
            return service.oidcService.getAuthorizationUrl(redirectUri);
        } catch (OidcService.OidcException e) {
            log.error("Failed to generate OIDC authorization URL: {}", e.getMessage());
            throw new BizException(AppError.forbidden("OIDC authorization unavailable")
                    .withDetails(e.getMessage()));
        }
    }
    static void setOidcNonceCookie(HttpServletRequest request, HttpServletResponse response,
                                           String nonce) {
        if (nonce == null || nonce.isEmpty()) {
            return;
        }
        boolean secure = request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        StringBuilder sb = new StringBuilder(AuthOidcOps.OIDC_NONCE_COOKIE_NAME).append('=').append(nonce)
                .append("; Path=/; Max-Age=").append(AuthOidcOps.OIDC_NONCE_COOKIE_MAX_AGE);
        if (secure) {
            sb.append("; Secure");
        }
        sb.append("; HttpOnly; SameSite=Lax");
        response.addHeader("Set-Cookie", sb.toString());
    }
    static String oidcCallbackUrl(HttpServletRequest request) {
        String scheme = "http";
        if (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))) {
            scheme = "https";
        }
        return scheme + "://" + request.getHeader("Host") + "/api/v1/auth/oidc/callback";
    }
    OidcStateCodec.Payload decodeOidcState(String rawState, HttpServletRequest request) {
        OidcStateCodec.Payload payload;
        try {
            payload = service.oidcStateCodec.verify(rawState);
        } catch (OidcStateCodec.StateException e) {
            return null;
        }
        String cookieNonce = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (AuthOidcOps.OIDC_NONCE_COOKIE_NAME.equals(c.getName())) {
                    cookieNonce = c.getValue();
                    break;
                }
            }
        }
        if (cookieNonce == null || UserService.goTrimSpace(cookieNonce).isEmpty()) {
            return null;
        }
        if (!cookieNonce.equals(payload.nonce())) {
            return null;
        }
        return payload;
    }
    static ResponseEntity<String> redirectFound(String location) {
        String body = "<a href=\"" + htmlEscapeString(location) + "\">Found</a>.\n\n";
        return ResponseEntity.status(302)
                .header("Location", location)
                .header("Content-Type", "text/html; charset=utf-8")
                .body(body);
    }
    static String htmlEscapeString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '&' -> sb.append("&amp;");
                case '\'' -> sb.append("&#39;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&#34;");
                default -> sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }
    static String urlQueryEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            switch (value.charAt(i)) {
                case '%' -> sb.append("%25");
                case ' ' -> sb.append("%20");
                case '#' -> sb.append("%23");
                case '&' -> sb.append("%26");
                case '+' -> sb.append("%2B");
                case '=' -> sb.append("%3D");
                case '?' -> sb.append("%3F");
                default -> sb.append(value.charAt(i));
            }
        }
        return sb.toString();
    }
}
