package com.ragagent.knowledge.dto.chunk;

import com.ragagent.knowledge.domain.ChunkRevision;
import java.time.OffsetDateTime;

/** chunk 修订视图：内容 + 编辑者/来源 + 创建/编辑时间。 */
public record ChunkRevisionResponse(
        String id,
        String knowledgeBaseId,
        String knowledgeId,
        String chunkId,
        int revision,
        String content,
        boolean enabled,
        String editorId,
        String editSource,
        OffsetDateTime editedAt,
        OffsetDateTime createdAt) {

    public static ChunkRevisionResponse from(ChunkRevision r) {
        return new ChunkRevisionResponse(
                r.getId(),
                r.getKnowledgeBaseId(),
                r.getKnowledgeId(),
                r.getChunkId(),
                r.getRevision(),
                r.getContent(),
                r.isEnabled(),
                emptyToNull(r.getEditorId()),
                emptyToNull(r.getEditSource()),
                r.getEditedAt(),
                r.getCreatedAt());
    }

private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
