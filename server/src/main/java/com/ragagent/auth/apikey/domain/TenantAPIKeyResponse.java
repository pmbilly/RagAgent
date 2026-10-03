package com.ragagent.auth.apikey.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * API Key 的响应投影。
 *
 * <p>record 字段声明序即 JSON 键序（键序按字段声明序输出）。</p>
 *
 * <p>逐字段输出语义：</p>
 * <ul>
 *   <li>{@code id} / {@code full_access}：非可空 → 恒输出（0 / false 也输出）；</li>
 *   <li>{@code scope_type}：经 {@link APIKeyScopeType#normalize} 归一，恒输出，
 *       未知口径回落 {@code "tenant"}；</li>
 *   <li>{@code knowledgeBaseIds}：恒输出，null 时输出 {@code null}
 *       （full-access Key 就是这个形态）；</li>
 *   <li>{@code capabilities}：恒输出；且构造时经
 *       {@code APIKeyCapability.normalizeAll}，null 会变成 {@code []} ——所以这一列
 *       **永远不会是 null**，与上一行形成刻意的不对称；</li>
 *   <li>{@code last_used_at} / {@code expires_at}：可空 → null 时**省略键**；</li>
 *   <li>{@code created_at}：非可空 → 恒输出。</li>
 * </ul>
 */
public record TenantAPIKeyResponse(
        long id,
        String scopeType,
        String name,
        String apiKey,
        boolean fullAccess,
        List<String> knowledgeBaseIds,
        List<String> capabilities,
        OffsetDateTime lastUsedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {

    /**
     * 实体 → 响应投影。
     *
     * <p>null 入参返回**零值投影**：
     * 字符串字段是 {@code ""} 而非 null，{@code scope_type} **不经过**归一化，
     * {@code capabilities} 保持 null → 输出 {@code null}（不是 {@code []}）。
     * 零值差异逐字段保留。</p>
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
