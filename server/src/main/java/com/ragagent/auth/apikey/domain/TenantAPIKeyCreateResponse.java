package com.ragagent.auth.apikey.domain;

/**
 * 创建 API Key 的响应体（对照 Go {@code handler.tenantAPIKeyCreateResponse}，
 * internal/handler/tenant.go L168-171）。
 *
 * <p>Go 用**内嵌** {@code tenantAPIKeyResponse} + {@code Token string json:"token"}：
 * encoding/json 把内嵌 struct 的字段**平铺**在顶层（本工程里 Tenant 没有
 * json tag 或 tag 为类型名时才有歧义，这里没有），所以最终键序是
 * 内嵌字段序在前、{@code token} 在最后。</p>
 *
 * <p><b>{@code token} 是明文 Key 唯一的返回时机</b>：Go 的注释与
 * {@code autoCreateTenantAPIKey} 都强调"key 只在创建时返回一次"，
 * 之后库里只有 SHA-256 摘要（{@code key_hash}）与密文（{@code api_key}）。
 * 这个响应形状正是 {@code tenantWithAPIKey} 想复刻的"变更前契约"。</p>
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

    /** 对照 Go 的值构造：内嵌响应 + Token。 */
    public static TenantAPIKeyCreateResponse of(TenantAPIKeyResponse base, String token) {
        return new TenantAPIKeyCreateResponse(
                base.id(), base.scopeType(), base.name(), base.apiKey(), base.fullAccess(),
                base.knowledgeBaseIds(), base.capabilities(), base.lastUsedAt(), base.expiresAt(),
                base.createdAt(), token);
    }
}
