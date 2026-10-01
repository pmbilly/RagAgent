package com.ragagent.auth.dto;

/**
 * OIDC 配置响应。
 *
 * <p>键名 = Java 字段名；未配置时 providerDisplayName 显式 null。</p>
 */
public record OidcConfigResponse(boolean enabled, String providerDisplayName) {
}
