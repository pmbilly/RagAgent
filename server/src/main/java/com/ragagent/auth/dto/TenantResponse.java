package com.ragagent.auth.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tenant 的 API 投影（对照 Go handler/dto/tenant.go TenantResponse）。
 *
 * 秘密承载列按调用者角色裁剪：role ≥ admin 才输出 web_search_config /
 * parser_engine_config / credentials / storage_engine_config（对照
 * NewTenantResponseWithRole 的 includeSecrets 分支）。
 * config 字段为 null 时省略（Go 指针 + omitempty）；deleted_at 恒输出（null → "deleted_at":null）。
 */
@JsonPropertyOrder({
        "id", "name", "description", "status", "retriever_engines", "business",
        "storage_quota", "storage_used", "context_config", "web_search_config",
        "parser_engine_config", "credentials", "storage_engine_config",
        "chat_history_config", "retrieval_config", "memory_config",
        "created_at", "updated_at", "deleted_at"
})
public record TenantResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("status") String status,
        @JsonProperty("retriever_engines") JsonNode retrieverEngines,
        @JsonProperty("business") String business,
        @JsonProperty("storage_quota") Long storageQuota,
        @JsonProperty("storage_used") Long storageUsed,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("context_config") JsonNode contextConfig,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("web_search_config") JsonNode webSearchConfig,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("parser_engine_config") JsonNode parserEngineConfig,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("credentials") JsonNode credentials,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("storage_engine_config") JsonNode storageEngineConfig,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("chat_history_config") JsonNode chatHistoryConfig,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("retrieval_config") JsonNode retrievalConfig,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("memory_config") JsonNode memoryConfig,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("deleted_at") OffsetDateTime deletedAt) {

    /**
     * 从 jsonb 列构造（对照 NewTenantResponseWithRole）。
     * includeSecrets=false 时四个秘密字段置 null（省略输出）。
     * Go 侧 string/int64 均为非指针：NULL Scan 为零值，故此处对 null 做零值归一化
     * （"" / 0），保证响应字节一致。
     */
    public static TenantResponse from(com.ragagent.auth.domain.Tenant t, boolean includeSecrets) {
        return new TenantResponse(
                t.getId() == null ? 0 : t.getId(),
                t.getName() == null ? "" : t.getName(),
                t.getDescription() == null ? "" : t.getDescription(),
                t.getStatus() == null ? "" : t.getStatus(),
                t.getRetrieverEngines(),
                t.getBusiness() == null ? "" : t.getBusiness(),
                t.getStorageQuota() == null ? 0 : t.getStorageQuota(),
                t.getStorageUsed() == null ? 0 : t.getStorageUsed(),
                t.getContextConfig(),
                includeSecrets ? t.getWebSearchConfig() : null,
                includeSecrets ? t.getParserEngineConfig() : null,
                includeSecrets ? t.getCredentials() : null,
                includeSecrets ? t.getStorageEngineConfig() : null,
                t.getChatHistoryConfig(), t.getRetrievalConfig(), t.getMemoryConfig(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getDeletedAt());
    }
}
