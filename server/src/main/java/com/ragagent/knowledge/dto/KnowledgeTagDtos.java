package com.ragagent.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * KB 标签 CRUD 面的响应类型（W5a，对照 Go types/tag.go 的
 * {@code KnowledgeTag} / {@code KnowledgeTagWithStats} 与 types/search.go 的
 * {@code PageResult}）。
 *
 * <p>字段序 = Go struct 声明序（embedding 形态 {@code KnowledgeTagWithStats}：
 * 内嵌 KnowledgeTag 的 9 个字段在前、knowledge_count/chunk_count 在后）；
 * Go 非指针零值语义：color NULL → ""、seq_id/tenant_id/sort_order NULL → 0
 * （由 {@code KnowledgeTagResponse.from} 归一）。</p>
 *
 * <p>ListTags 的外层信封是 gin.H{"success","data"}（字母序 data&lt;success），
 * data 是 PageResult struct（total,page,page_size,data 声明序）——由控制器组装。</p>
 */
public final class KnowledgeTagDtos {

    private KnowledgeTagDtos() {
    }

    /** 对照 types.KnowledgeTag（JSON 契约面）。 */
    @JsonPropertyOrder({"id", "seq_id", "tenant_id", "knowledge_base_id",
            "name", "color", "sort_order", "created_at", "updated_at"})
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record KnowledgeTagResponse(
            @JsonProperty("id") String id,
            @JsonProperty("seq_id") long seqId,
            @JsonProperty("tenant_id") long tenantId,
            @JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("name") String name,
            @JsonProperty("color") String color,
            @JsonProperty("sort_order") int sortOrder,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {

        public static KnowledgeTagResponse from(com.ragagent.knowledge.domain.KnowledgeTag t) {
            return new KnowledgeTagResponse(
                    t.getId(),
                    t.getSeqId() == null ? 0 : t.getSeqId(),
                    t.getTenantId() == null ? 0 : t.getTenantId(),
                    t.getKnowledgeBaseId() == null ? "" : t.getKnowledgeBaseId(),
                    t.getName() == null ? "" : t.getName(),
                    t.getColor() == null ? "" : t.getColor(),
                    t.getSortOrder() == null ? 0 : t.getSortOrder(),
                    t.getCreatedAt(),
                    t.getUpdatedAt());
        }
    }

    /** 对照 types.KnowledgeTagWithStats：KnowledgeTag 字段 + 两个计数。 */
    @JsonPropertyOrder({"id", "seq_id", "tenant_id", "knowledge_base_id",
            "name", "color", "sort_order", "created_at", "updated_at",
            "knowledge_count", "chunk_count"})
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record KnowledgeTagWithStats(
            @JsonProperty("id") String id,
            @JsonProperty("seq_id") long seqId,
            @JsonProperty("tenant_id") long tenantId,
            @JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("name") String name,
            @JsonProperty("color") String color,
            @JsonProperty("sort_order") int sortOrder,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt,
            @JsonProperty("knowledge_count") long knowledgeCount,
            @JsonProperty("chunk_count") long chunkCount) {

        public static KnowledgeTagWithStats from(com.ragagent.knowledge.domain.KnowledgeTag t,
                                                 long knowledgeCount, long chunkCount) {
            KnowledgeTagResponse base = KnowledgeTagResponse.from(t);
            return new KnowledgeTagWithStats(base.id(), base.seqId(), base.tenantId(),
                    base.knowledgeBaseId(), base.name(), base.color(), base.sortOrder(),
                    base.createdAt(), base.updatedAt(), knowledgeCount, chunkCount);
        }
    }

    /** 对照 types.PageResult（total,page,page_size,data 声明序）。 */
    @JsonPropertyOrder({"total", "page", "page_size", "data"})
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TagPageResult(
            @JsonProperty("total") long total,
            @JsonProperty("page") int page,
            @JsonProperty("page_size") int pageSize,
            @JsonProperty("data") List<KnowledgeTagWithStats> data) {
    }

/** 创建标签请求（name 必填；color/sort_order 可选）。 */
@com.fasterxml.jackson.databind.annotation.JsonNaming(
        com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateTagRequest(
        @jakarta.validation.constraints.NotBlank(message = "name: 不能为空")
        String name,
        String color,
        Integer sortOrder) {
}

/** 更新标签请求：全指针，不传 = 不变更。 */
@com.fasterxml.jackson.databind.annotation.JsonNaming(
        com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UpdateTagRequest(
        String name,
        String color,
        Integer sortOrder) {
}

/** 删除标签请求：exclude_ids 为保留条目（body 可整体省略）。 */
@com.fasterxml.jackson.databind.annotation.JsonNaming(
        com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeleteTagRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("exclude_ids")
        java.util.List<Long> excludeIds) {
}
}
