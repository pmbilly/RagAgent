package com.ragagent.auth.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * api-principal 三端点的请求/响应类型（字段序 = JSON 键序）。
 *
 * <p>响应五个字段（mode/direct_header_name/signed_token_header_name/
 * require_direct_header/has_hmac_secret）恒输出；
 * has_hmac_secret 只报"是否已配置"，明文密钥永不回显（golden mb-apc-get 钉住）。</p>
 *
 * <p><b>未知键容忍</b>：请求体 {@code ignoreUnknown = true}，
 * 别按"严格校验"直觉改。</p>
 */
public final class APIPrincipalDtos {

    private APIPrincipalDtos() {
    }

    /** 缺省归一后的配置视图 */
    public record APIPrincipalConfigResponse(
            String mode,
            String directHeaderName,
            String signedTokenHeaderName,
            boolean requireDirectHeader,
            boolean hasHmacSecret) {
    }

    /**
     * PUT 请求体。用 {@code JsonNode} 区分"没发"与"显式空串/清空"：
     * {@code null}=没发、NullNode=显式 null → 保留存量密钥；TextNode("…")=显式提供 → 覆盖。
     */
    public record APIPrincipalConfigRequest(
            String mode,
            String directHeaderName,
            String signedTokenHeaderName,
            boolean requireDirectHeader,
            JsonNode hmacSecret) {

        /** hmac_secret 是否被显式提供（非缺失、非显式 null） */
        public boolean hasHmacSecret() {
            return hmacSecret != null && !hmacSecret.isNull();
        }

        /** 显式提供的值（asText 宽容处理非文本节点） */
        public String hmacSecretValue() {
            return hmacSecret == null || hmacSecret.isNull() ? null : hmacSecret.asText();
        }
    }

    public record APIPrincipalTestTokenRequest(
            String externalUserId,
            int expiresInSeconds) {
    }

    public record APIPrincipalTestTokenResponse(
            String token,
            String headerName,
            int expiresInSeconds,
            long expiresAtUnix,
            String externalUserId) {
    }
}
