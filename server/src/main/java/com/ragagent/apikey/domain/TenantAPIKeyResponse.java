package com.ragagent.apikey.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * API Key 的响应投影（对照 Go {@code handler.tenantAPIKeyResponse}，
 * internal/handler/tenant.go L155-166）。
 *
 * <p>用 record → 字段声明序即 Go struct 的声明序（约定 §9 的键序规则：
 * struct 响应按字段声明序输出）。</p>
 *
 * <p>零值语义逐字段对照：</p>
 * <ul>
 *   <li>{@code id} / {@code full_access}：非指针 → 恒输出（0 / false 也输出）；</li>
 *   <li>{@code scope_type}：经 {@code NormalizeAPIKeyScopeType} 归一，恒输出，
 *       未知口径回落 {@code "tenant"}；</li>
 *   <li>{@code knowledge_base_ids}：**无 omitempty** → 恒输出，nil 时输出 {@code null}
 *       （full-access Key 就是这个形态）；</li>
 *   <li>{@code capabilities}：**无 omitempty** → 恒输出；且构造时经
 *       {@code NormalizeAPIKeyCapabilities}，nil 会变成 {@code []} ——所以这一列
 *       **永远不会是 null**，与上一行形成刻意的不对称；</li>
 *   <li>{@code last_used_at} / {@code expires_at}：指针 + omitempty → nil 时**省略键**；</li>
 *   <li>{@code created_at}：非指针 time.Time → 恒输出。</li>
 * </ul>
 */
@JsonPropertyOrder({"id", "scope_type", "name", "api_key", "full_access",
        "knowledge_base_ids", "capabilities", "last_used_at", "expires_at", "created_at"})
public record TenantAPIKeyResponse(
        @JsonProperty("id") long id,
        @JsonProperty("scope_type") String scopeType,
        @JsonProperty("name") String name,
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("full_access") boolean fullAccess,
        @JsonProperty("knowledge_base_ids") List<String> knowledgeBaseIds,
        @JsonProperty("capabilities") List<String> capabilities,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("last_used_at") OffsetDateTime lastUsedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    /**
     * 对照 {@code tenantAPIKeyForResponse}（handler/tenant.go L784-800）。
     *
     * <p>null 入参返回 **Go 的零值 struct**（{@code tenantAPIKeyResponse{}}）：
     * 字符串字段是 {@code ""} 而非 null，{@code scope_type} **不经过**归一化，
     * {@code capabilities} 保持 nil → 输出 {@code null}（不是 {@code []}）。
     * 这里逐字段照抄该零值差异。</p>
     */
    public static TenantAPIKeyResponse from(TenantAPIKey key) {
        if (key == null) {
            return new TenantAPIKeyResponse(0L, "", "", "", false, null, null, null, null, null);
        }
        return new TenantAPIKeyResponse(
                key.getId() == null ? 0L : key.getId(),
                APIKeyScopeType.normalize(key.getScopeType()),
                key.getName(),
                key.getApiKey(),
                key.isFullAccess(),
                key.getKnowledgeBaseIds(),
                APIKeyCapability.normalizeAll(key.getCapabilities()),
                key.getLastUsedAt(),
                key.getExpiresAt(),
                key.getCreatedAt());
    }
}
