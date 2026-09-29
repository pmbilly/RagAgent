package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeTag;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;

public record KnowledgeTagResponse(
        String id,
        long seqId,
        String knowledgeBaseId,
        String name,
        String color,
        int sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static KnowledgeTagResponse from(KnowledgeTag t) {
        return new KnowledgeTagResponse(
                t.getId(),
                t.getSeqId() == null ? 0 : t.getSeqId(),
                t.getKnowledgeBaseId(),
                t.getName(),
                t.getColor(),
                t.getSortOrder() == null ? 0 : t.getSortOrder(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
