package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.Chunk;

/**
 * 分块（Chunk）对外视图。
 *
 * <p>契约要点（见 {@code docs/knowledge-api-contract-v1.md}）：</p>
 * <ul>
 *   <li>JSON 字段名 = Java 字段名（camelCase，零 {@code @JsonProperty}）；</li>
 *   <li>可空字段显式输出 {@code null}——实体的空串默认值在此归一（{@code tagId}、
 *       {@code lastEditorId}、{@code preChunkId}/{@code nextChunkId}/{@code parentChunkId}、
 *       {@code contentHash}、{@code imageInfo}）；</li>
 *   <li>内部字段不下发：{@code tenantId}、{@code deletedAt}、{@code sourceContent}
 *       （实体里本就 {@code @JsonIgnore}）与 {@code contextHeader}；</li>
 *   <li>布尔字段不带 {@code is} 前缀：实体 {@code isIsEnabled()} → {@code enabled}。</li>
 * </ul>
 *
 * <p><b>不透明载荷</b>：{@code relationChunks} / {@code indirectRelationChunks} /
 * {@code metadata} 是 jsonb 列，内容由写入方决定，这里原样透传（不在读路径重写其内部键）。</p>
 */
public record ChunkResponse(
        String id,
        Long seqId,
        String knowledgeId,
        String knowledgeBaseId,
        String tagId,
        String content,
        int contentRevision,
        String indexStatus,
        String lastEditorId,
        int chunkIndex,
        boolean enabled,
        int flags,
        int status,
        int startAt,
        int endAt,
        String preChunkId,
        String nextChunkId,
        String chunkType,
        String parentChunkId,
        JsonNode relationChunks,
        JsonNode indirectRelationChunks,
        JsonNode metadata,
        String contentHash,
        String imageInfo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** 由实体组装视图。 */
    public static ChunkResponse from(Chunk c) {
        return new ChunkResponse(
                c.getId(),
                c.getSeqId(),
                c.getKnowledgeId(),
                c.getKnowledgeBaseId(),
                emptyToNull(c.getTagId()),
                c.getContent(),
                c.getContentRevision(),
                c.getIndexStatus(),
                emptyToNull(c.getLastEditorId()),
                c.getChunkIndex(),
                c.isIsEnabled(),
                c.getFlags(),
                c.getStatus(),
                c.getStartAt(),
                c.getEndAt(),
                emptyToNull(c.getPreChunkId()),
                emptyToNull(c.getNextChunkId()),
                c.getChunkType(),
                emptyToNull(c.getParentChunkId()),
                c.getRelationChunks(),
                c.getIndirectRelationChunks(),
                c.getMetadata(),
                emptyToNull(c.getContentHash()),
                emptyToNull(c.getImageInfo()),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }

    /** 空串按"未设置"处理（契约：不用空串代替 null）。 */
    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
