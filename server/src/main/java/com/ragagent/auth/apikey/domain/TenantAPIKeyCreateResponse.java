package com.ragagent.auth.apikey.domain;

/**
 * 创建 API Key 的响应体。
 *
 * <p>响应体 = {@link TenantAPIKeyResponse} 的全部字段平铺在顶层，
 * 外加末位的 {@code token}（键序：投影字段序在前、{@code token} 最后）。</p>
 *
 * <p><b>{@code token} 是明文 Key 唯一的返回时机</b>：key 只在创建时返回一次，
 * 之后库里只有 SHA-256 摘要（{@code key_hash}）与密文（{@code api_key}）。</p>
 */
public record TenantAPIKeyCreateResponse(
        long id,
        String scopeType,
        String name,
        String apiKey,
        boolean fullAccess,
        java.util.List<String> knowledgeBaseIds,
        java.util.List<String> capabilities,
        java.time.OffsetDateTime lastUsedAt,
        java.time.OffsetDateTime expiresAt,
        java.time.OffsetDateTime createdAt,
        String token) {

    /** 值构造：投影字段 + Token。 */
    public static TenantAPIKeyCreateResponse of(TenantAPIKeyResponse base, String token) {
        return new TenantAPIKeyCreateResponse(
                base.id(), base.scopeType(), base.name(), base.apiKey(), base.fullAccess(),
                base.knowledgeBaseIds(), base.capabilities(), base.lastUsedAt(), base.expiresAt(),
                base.createdAt(), token);
    }
}
