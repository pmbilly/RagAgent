package com.ragagent.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.ragagent.common.context.TenantContext;

/**
 * 一次进行中的 OAuth 授权码流程所需的全部临时数据。
 *
 * <p><b>键名</b>：本记录只序列化进 Redis/内存（同一份 JSON），键名＝组件名；
 * 部署窗口内由 {@code OAuthStateStore} 的兼容读接住旧的下划线 blob。</p>
 *
 * <p><b>为什么必须服务端存储</b>：本结构持有 PKCE 的
 * {@code code_verifier}，那是<b>绝不能发往授权服务器</b>的秘密（授权请求里只发它的
 * SHA-256 摘要 code_challenge）。因此 state 参数只是一个不透明句柄，
 * 真正的数据存在服务端（Redis 多实例 / Lite 内存）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthState(
        long tenantId,
        String userId,
        Principal principal,
        String serviceId,
        String codeVerifier,
        String clientId,
        String redirectUri,
        /**
         * 回调完成后浏览器最终被弹回的前端地址（不是授权服务器回跳的 redirect_uri）。
         */
        String frontendRedirect) {

    public OAuthState {
        userId = nz(userId);
        serviceId = nz(serviceId);
        codeVerifier = nz(codeVerifier);
        clientId = nz(clientId);
        redirectUri = nz(redirectUri);
        frontendRedirect = nz(frontendRedirect);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    /** principal 的 (type, id) 序列化形态（仅用于 Redis 往返）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Principal(String type, String id) {

        static Principal of(TenantContext.Principal p) {
            return p == null ? new Principal("", "") : new Principal(nz(p.type()), nz(p.id()));
        }

        TenantContext.Principal toContextPrincipal() {
            return new TenantContext.Principal(nz(type), nz(id));
        }
    }

    public TenantContext.Principal principalOrNull() {
        return principal == null ? null : principal.toContextPrincipal();
    }
}
