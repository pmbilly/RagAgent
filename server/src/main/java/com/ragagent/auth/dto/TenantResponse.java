package com.ragagent.auth.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tenant 的 API 投影。
 *
 * 秘密承载列按调用者角色裁剪：role ≥ admin 才输出 web_search_config /
 * parser_engine_config / credentials / storage_engine_config（includeSecrets 分支）。
 * config 字段为 null 时省略；deleted_at 恒输出（null → "deleted_at":null）。
 */
public record TenantResponse(
        Long id,
        String name,
        String description,
        String status,
        JsonNode retrieverEngines,
        String business,
        Long storageQuota,
        Long storageUsed,

        JsonNode contextConfig,

        JsonNode webSearchConfig,

        JsonNode parserEngineConfig,

        JsonNode credentials,

        JsonNode storageEngineConfig,

        JsonNode chatHistoryConfig,

        JsonNode retrievalConfig,

        JsonNode memoryConfig,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {

    /**
     * 从实体构造。includeSecrets=false 时四个秘密字段置 null（省略输出）。
     * 标量列非空语义：NULL 归一为零值（"" / 0），保证响应字节一致。
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
