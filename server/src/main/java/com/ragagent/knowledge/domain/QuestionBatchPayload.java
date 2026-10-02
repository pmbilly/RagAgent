package com.ragagent.knowledge.domain;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TracingContext;
import com.ragagent.common.web.JsonMappers;

/**
 * 问题生成**批**任务的载荷。
 * <p>只带 chunk id（普通键 + 边界邻块 id），<b>不带 chunk 内容</b>——worker 运行时读新内容，
 * 与 {@link ExtractChunkPayload} 同法；批大小固定 {@link QuestionBatchPlanner#BATCH_SIZE}=20
 * <p><b>追踪载体</b>：与 {@link ExtractChunkPayload} 同形（B5 起为嵌套键 {@code tracing}），
 * worker 侧续接同一棵树。</p>
 */
public record QuestionBatchPayload(
        long tenantId,
        String knowledgeBaseId,
        String knowledgeId,
        int questionCount,
        String language,
        int attempt,
        List<String> chunkIds,
        int batchIndex,
        /** 批窗口前一个文本分块（重建邻接上下文用；无则空）。 */
        String prevChunkId,
        /** 批窗口后一个文本分块（同 prev）。 */
        String nextChunkId,
        /**
         * 观测载体（B5：嵌套键 {@code tracing}，五个 {@code lf_*} 组件收在里面）。
         *
         * <p>知识域约定：字段一律<b>显式输出</b>、键名即 Java 字段名（禁 {@code @JsonInclude}/
         * {@code @JsonProperty}）——故此处不加注解，空载体输出 {@code "tracing":{}}；
         * 载体自身组件的省略规则由 {@link TracingContext} 负责。</p>
         */
        TracingContext tracing) {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    public QuestionBatchPayload {
        knowledgeBaseId = knowledgeBaseId == null ? "" : knowledgeBaseId;
        knowledgeId = knowledgeId == null ? "" : knowledgeId;
        language = language == null ? "" : language;
        chunkIds = chunkIds == null ? List.of() : new ArrayList<>(chunkIds);
        prevChunkId = prevChunkId == null ? "" : prevChunkId;
        nextChunkId = nextChunkId == null ? "" : nextChunkId;
    }

    /** 兼容构造：不带追踪载体。 */
    public QuestionBatchPayload(long tenantId, String knowledgeBaseId, String knowledgeId,
                                int questionCount, String language, int attempt, List<String> chunkIds,
                                int batchIndex, String prevChunkId, String nextChunkId) {
        this(tenantId, knowledgeBaseId, knowledgeId, questionCount, language, attempt, chunkIds, batchIndex,
                prevChunkId, nextChunkId, null);
    }

    /** 带追踪载体的构造（入队侧用）。 */
    public static QuestionBatchPayload withTracing(long tenantId, String knowledgeBaseId, String knowledgeId,
                                                   int questionCount, String language, int attempt,
                                                   List<String> chunkIds, int batchIndex,
                                                   String prevChunkId, String nextChunkId,
                                                   TracingContext tracing) {
        TracingContext tc = tracing == null ? TracingContext.EMPTY : tracing;
        return new QuestionBatchPayload(tenantId, knowledgeBaseId, knowledgeId, questionCount, language,
                attempt, chunkIds, batchIndex, prevChunkId, nextChunkId, tc.isEmpty() ? null : tc);
    }

    /** 追踪载体的结构视图（worker 侧续接用）。 */
    public TracingContext tracing() {
        return tracing == null ? TracingContext.EMPTY : tracing;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("marshal question batch payload failed", e);
        }
    }

    public static QuestionBatchPayload fromJson(String json) {
        try {
            return MAPPER.readValue(json, QuestionBatchPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("unmarshal question batch payload: " + e.getMessage(), e);
        }
    }
}
