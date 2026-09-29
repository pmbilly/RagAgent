package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeTag;
import java.time.OffsetDateTime;

/** 标签视图 + 文档数 / 分块数。 */
public record KnowledgeTagWithStats(
        String id,
        long seqId,
        String knowledgeBaseId,
        String name,
        String color,
        int sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long knowledgeCount,
        long chunkCount) {

    public static KnowledgeTagWithStats from(KnowledgeTag t,
                                             long knowledgeCount, long chunkCount) {
        KnowledgeTagResponse base = KnowledgeTagResponse.from(t);
        return new KnowledgeTagWithStats(base.id(), base.seqId(), base.knowledgeBaseId(),
                base.name(), base.color(), base.sortOrder(),
                base.createdAt(), base.updatedAt(), knowledgeCount, chunkCount);
    }
}
