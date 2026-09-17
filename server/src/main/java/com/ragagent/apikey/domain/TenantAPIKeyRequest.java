package com.ragagent.apikey.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 创建 / 更新租户 API Key 的请求体。
 *
 * <p>Go 侧刻意声明了**两个同形 struct**：
 * {@code handler.tenantAPIKeyCreateRequest}（handler/tenant.go L138-144）与
 * {@code handler.tenantAPIKeyUpdateRequest}（L146-153），
 * 后者在 {@code UpdateAPIKey} 里直接强转成前者复用校验
 * （{@code validateTenantAPIKeyRequest(ctx, h.kbService, tenantID, tenantAPIKeyCreateRequest(req))}）。
 * Java 侧用同一个 record 表达这层"字段语义一致"的意图，避免两份会漂移的定义。</p>
 *
 * <p>{@code expires_at_unix} 是 **Unix 秒**的指针语义（Go {@code *int64}）：
 * {@code null} = 不设置到期时间；{@code 0} 是一个合法的（但已过期）时间戳。
 * 所以这里必须用包装类型 {@code Long}，不能用原始 {@code long}。</p>
 */
public record TenantAPIKeyRequest(
        @JsonProperty("name") String name,
        @JsonProperty("full_access") boolean fullAccess,
        @JsonProperty("knowledge_base_ids") List<String> knowledgeBaseIds,
        @JsonProperty("capabilities") List<String> capabilities,
        @JsonProperty("expires_at_unix") Long expiresAtUnix) {
}
