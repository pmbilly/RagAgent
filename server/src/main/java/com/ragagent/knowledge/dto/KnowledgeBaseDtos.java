package com.ragagent.knowledge.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 知识库域传输对象：更新、混合检索、复制请求与重建索引响应。
 * 请求 record 用标准 {@code @JsonNaming(SnakeCaseStrategy)}；跨字段条件校验
 * （如 hybrid-search 的 query_text/embedding 二选一）在 controller 内判定。
 */
public final class KnowledgeBaseDtos {

    private KnowledgeBaseDtos() {
    }

    /** 更新知识库请求：name 传空串校验失败（null = 不变更，controller 判定）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UpdateKbRequest(String name, String description, JsonNode config) {
    }

    /** 混合检索请求：query_text 与 query_embedding 至少其一（precomputed-vector 语义）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HybridSearchRequest(
            String queryText,
            float[] queryEmbedding,
            Double vectorThreshold,
            Double keywordThreshold,
            Integer matchCount,
            Boolean disableKeywordsMatch,
            Boolean disableVectorMatch,
            Boolean skipContextEnrichment,
            List<String> knowledgeBaseIds,
            List<String> knowledgeIds,
            List<String> tagIds) {
    }

    /** 复制知识库请求：target_id 缺省 = 创建新库。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CopyKbRequest(
            @jakarta.validation.constraints.NotBlank(message = "source_id: 不能为空")
            String sourceId,
            String targetId,
            String taskId) {
    }

    public record HybridSearchResponse(Object data, boolean success) {
    }

    /** 重建索引响应。 */
    public record RebuildIndexResponse(
            @JsonProperty("document_count") long documentCount) {
    }
}
