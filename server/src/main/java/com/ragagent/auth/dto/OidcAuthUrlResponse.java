package com.ragagent.auth.dto;

/**
 * OIDC 授权地址响应。
 *
 * <p>键名 = Java 字段名；可空字段显式 null。Nonce 只进 HttpOnly cookie，不建模。</p>
 */
public record OidcAuthUrlResponse(
        String providerDisplayName,
        String authorizationUrl,
        String state) {
}
