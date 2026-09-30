package com.ragagent.mcp.oauth;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.ragagent.mcp.protocol.McpContext;

/**
 * OAuth 协议交换协作者（对照 mcp-go oauth.go 的刷新/注册/授权 URL/code 交换段，
 * 自 {@link OAuthHandler} 机械搬出）：token 刷新与落库、RFC 7591 动态注册、
 * 授权 URL 组装（state 副作用）、CSRF 校验先行 code 交换，及表单编码与
 * OAuth 错误解析静态工具。持门面回引取 config/timeout/CSRF 态与发现委托。
 */
final class OAuthTokenOps {

    private final OAuthHandler service;

    OAuthTokenOps(OAuthHandler service) {
        this.service = service;
    }

    // ── 刷新 ───────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code refreshToken}（oauth.go:240-318）。
     *
     * <p>要点：接受<b>任意 2xx</b>（Supabase 会回 201）；若响应体里带 {@code error} 字段
     * （GitHub 的 HTTP 200 错误）则按错误处理；服务器没回新 refresh token 时<b>沿用旧的</b>
     * （轮换型 refresh token 的常见形态）。</p>
     */
    public OAuthToken refreshToken(McpContext ctx, String refreshToken) {
        AuthServerMetadata metadata = service.getServerMetadata(ctx);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", service.config.clientId());
        if (!service.config.clientSecret().isEmpty()) {
            form.put("client_secret", service.config.clientSecret());
        }
        // RFC 8707：刷新请求也要带 resource
        if (!service.getResourceUrl().isEmpty()) {
            form.put("resource", service.getResourceUrl());
        }

        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        OAuthHttp.Response resp = OAuthHttp.post(metadata.tokenEndpoint(),
                "application/x-www-form-urlencoded", "application/json", body, service.timeout);

        if (resp.status() < 200 || resp.status() >= 300) {
            throw extractOAuthError(resp.body(), resp.status(), "refresh token request failed");
        }

        // GitHub 会在 HTTP 200 里带 error 字段
        OAuthError bodyError = parseOAuthError(resp.body());
        if (bodyError != null) {
            throw OAuthProtocolException.ofOAuthError("refresh token request failed", bodyError);
        }

        OAuthToken token = parseToken(resp.body());
        if (token.expiresIn() > 0) {
            token.applyExpiresIn(token.expiresIn());
        }
        if (token.refreshToken().isEmpty()) {
            token.setRefreshToken(refreshToken);
        }
        service.config.tokenStore().saveToken(ctx, token);
        return token;
    }

    // ── RFC 7591 动态客户端注册 ─────────────────────────────────────────

    /**
     * 对照 Go {@code RegisterClient}（oauth.go:886-966）。
     *
     * <p>注册成功后<b>就地</b>更新 handler 的 client_id/secret，随后的
     * {@link #getClientId} 才拿得到新值（Go 正是靠这个把 client_id 回填进
     * {@code OAuthManager.StartAuthorization}）。</p>
     */
    public void registerClient(McpContext ctx, String clientName) {
        AuthServerMetadata metadata = service.getServerMetadata(ctx);
        if (metadata.registrationEndpoint().isEmpty()) {
            throw OAuthProtocolException.of("server does not support dynamic client registration");
        }

        Map<String, Object> regRequest = new LinkedHashMap<>();
        regRequest.put("client_name", clientName);
        regRequest.put("redirect_uris", List.of(service.config.redirectUri()));
        regRequest.put("token_endpoint_auth_method", "none"); // 公共客户端
        regRequest.put("grant_types", List.of("authorization_code", "refresh_token"));
        regRequest.put("response_types", List.of("code"));
        regRequest.put("scope", String.join(" ", service.config.scopes()));
        if (!service.config.clientUri().isEmpty()) {
            regRequest.put("client_uri", service.config.clientUri());
        }
        if (!service.config.clientSecret().isEmpty()) {
            regRequest.put("token_endpoint_auth_method", "client_secret_post");
        }
        if (!service.getResourceUrl().isEmpty()) {
            regRequest.put("resource", service.getResourceUrl());
        }

        byte[] body;
        try {
            body = OAuthHandler.MAPPER.writeValueAsBytes(regRequest);
        } catch (Exception e) {
            throw OAuthProtocolException.of("failed to marshal registration request: " + e.getMessage(), e);
        }

        OAuthHttp.Response resp = OAuthHttp.post(metadata.registrationEndpoint(),
                "application/json", "application/json", body, service.timeout);

        if (resp.status() != 201 && resp.status() != 200) {
            throw extractOAuthError(resp.body(), resp.status(), "registration request failed");
        }

        Map<?, ?> regResponse;
        try {
            regResponse = OAuthHandler.MAPPER.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            throw OAuthProtocolException.of("failed to decode registration response: " + e.getMessage(), e);
        }
        String clientId = stringOf(regResponse.get("client_id"));
        String clientSecret = stringOf(regResponse.get("client_secret"));
        service.config.clientId(clientId);
        if (!clientSecret.isEmpty()) {
            service.config.clientSecret(clientSecret);
        }
    }

    // ── 授权 URL ───────────────────────────────────────────────────────

    /**
     * 对照 Go {@code GetAuthorizationURL}（oauth.go:1071-1101）。
     *
     * <p><b>注意副作用</b>：Go 在这里顺手调用 {@code SetExpectedState(state)}，
     * 即"发起授权"这一动作本身就把 state 记进了 handler 的 CSRF 期望值。
     * 回调请求是<b>另一个</b> handler 实例，因此必须显式再 set 一次
     * （见 {@code OAuthManager.CompleteAuthorization} 的说明）。</p>
     */
    public String getAuthorizationUrl(McpContext ctx, String state, String codeChallenge) {
        AuthServerMetadata metadata = service.getServerMetadata(ctx);
        service.setExpectedState(state);

        Map<String, String> params = new TreeMap<>();
        params.put("response_type", "code");
        params.put("client_id", service.config.clientId());
        params.put("redirect_uri", service.config.redirectUri());
        params.put("state", state);
        if (!service.config.scopes().isEmpty()) {
            params.put("scope", String.join(" ", service.config.scopes()));
        }
        if (service.config.pkceEnabled() && !codeChallenge.isEmpty()) {
            params.put("code_challenge", codeChallenge);
            params.put("code_challenge_method", "S256");
        }
        if (!service.getResourceUrl().isEmpty()) {
            params.put("resource", service.getResourceUrl());
        }
        return metadata.authorizationEndpoint() + "?" + encodeForm(params);
    }

    // ── code 交换 ──────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ProcessAuthorizationResponse}（oauth.go:971-1068）。
     *
     * <p>CSRF 校验先行：期望值为空报"流程未正确发起"，不匹配报
     * {@code OAuthHandler.INVALID_STATE_MESSAGE}；<b>校验后立刻清空</b>期望值，
     * 使同一 handler 上的第二次回调必然失败。</p>
     */
    public void processAuthorizationResponse(McpContext ctx, String code, String state,
                                             String codeVerifier) {
        service.stateLock.lock();
        try {
            if (service.expectedState.isEmpty()) {
                throw OAuthProtocolException.of(
                        "no expected state found, authorization flow may not have been initiated properly");
            }
            if (!state.equals(service.expectedState)) {
                throw OAuthProtocolException.of(OAuthHandler.INVALID_STATE_MESSAGE);
            }
            service.expectedState = "";
        } finally {
            service.stateLock.unlock();
        }

        AuthServerMetadata metadata = service.getServerMetadata(ctx);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", service.config.clientId());
        form.put("redirect_uri", service.config.redirectUri());
        if (!service.config.clientSecret().isEmpty()) {
            form.put("client_secret", service.config.clientSecret());
        }
        if (service.config.pkceEnabled() && !codeVerifier.isEmpty()) {
            form.put("code_verifier", codeVerifier);
        }
        if (!service.getResourceUrl().isEmpty()) {
            form.put("resource", service.getResourceUrl());
        }

        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        OAuthHttp.Response resp = OAuthHttp.post(metadata.tokenEndpoint(),
                "application/x-www-form-urlencoded", "application/json", body, service.timeout);

        if (resp.status() < 200 || resp.status() >= 300) {
            throw extractOAuthError(resp.body(), resp.status(), "token request failed");
        }

        OAuthError bodyError = parseOAuthError(resp.body());
        if (bodyError != null) {
            throw OAuthProtocolException.ofOAuthError("token request failed", bodyError);
        }

        OAuthToken token = parseToken(resp.body());
        if (token.expiresIn() > 0) {
            token.applyExpiresIn(token.expiresIn());
        }
        service.config.tokenStore().saveToken(ctx, token);
    }
    /** 对照 Go {@code extractOAuthError}：结构化错误优先，否则回落到 "with status N: <body>"。 */
    static OAuthProtocolException extractOAuthError(String body, int statusCode, String context) {
        OAuthError parsed = parseOAuthError(body);
        if (parsed != null) {
            return OAuthProtocolException.ofOAuthError(context, parsed);
        }
        return OAuthProtocolException.ofRawStatus(context, statusCode, body);
    }

    private static OAuthError parseOAuthError(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            OAuthError error = OAuthHandler.MAPPER.readValue(body, OAuthError.class);
            return error.isPresent() ? error : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static OAuthToken parseToken(String body) {
        try {
            return OAuthHandler.MAPPER.readValue(body, OAuthToken.class);
        } catch (Exception e) {
            throw OAuthProtocolException.of("failed to decode token response: " + e.getMessage(), e);
        }
    }

    /**
     * 对照 Go {@code url.Values.Encode()}：键<b>按字典序</b>输出，空格编成 {@code +}，
     * 非保留字符含 {@code ~} 不编码。JDK 的 {@code URLEncoder} 会把 {@code ~} 编成
     * {@code %7E} 且对 {@code *} 的处理不同，故这里自实现以保证字节级一致。
     */
    static String encodeForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(queryEscape(e.getKey())).append('=').append(queryEscape(e.getValue()));
        }
        return sb.toString();
    }

    /** 对照 Go {@code url.QueryEscape}。 */
    static String queryEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte raw : (s == null ? "" : s).getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }
    private static String stringOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
