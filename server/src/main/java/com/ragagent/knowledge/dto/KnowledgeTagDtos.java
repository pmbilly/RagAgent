package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ragagent.knowledge.domain.KnowledgeTag;
import jakarta.validation.constraints.NotBlank;

/**
 * 知识库标签的传输对象。
 *
 * <p>响应 record 按新契约：camelCase、零注解、内部字段 {@code tenantId} 不下发、
 * 分页统一 {@code {items, page, pageSize, total}}；请求 record 仍用
 * {@code @JsonNaming(SnakeCaseStrategy)}（请求侧 camelCase 属独立批次）。</p>
 */
public final class KnowledgeTagDtos {

    private KnowledgeTagDtos() {
    }

    /**
     * 标签视图。
     *
     * @param seqId 展示用序号（实体缺省 0）
     */
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

    /** 标签视图 + 引用计数（列表页展示"被 N 篇文档 / M 个分块引用"）。 */
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

    /** 标签分页结果。 */
    public record TagPageResult(List<KnowledgeTagWithStats> items, int page, int pageSize, long total) {
    }

    /** 创建标签请求（name 必填；color/sortOrder 可选）。 */
    public record CreateTagRequest(
            @NotBlank(message = "name: 不能为空")
            String name,
            String color,
            Integer sortOrder) {
    }

    /** 更新标签请求：全指针，不传 = 不变更。 */
    public record UpdateTagRequest(
            String name,
            String color,
            Integer sortOrder) {
    }

    /** 删除标签请求：excludeIds 为保留条目（body 可整体省略）。 */
    public record DeleteTagRequest(List<Long> excludeIds) {
    }
}
