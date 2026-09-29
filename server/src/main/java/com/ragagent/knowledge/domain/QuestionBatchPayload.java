package com.ragagent.knowledge.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TracingContext;
import com.ragagent.common.web.JsonMappers;

/**
 * 问题生成**批**任务的载荷。
 * <p>只带 chunk id（普通键 + 边界邻块 id），<b>不带 chunk 内容</b>——worker 运行时读新内容，
 * 与 {@link ExtractChunkPayload} 同法；批大小固定 {@link QuestionBatchPlanner#BATCH_SIZE}=20
 * <p><b>追踪载体</b>：与 {@code ExtractChunkPayload} 同形（平铺 {@code lf_*} 五键，空值整键省略），
 * worker 侧续接同一棵树。</p>
 */
public record QuestionBatchPayload(
        long tenantId,
        String knowledgeBaseId,
        String knowledgeId,
        int questionCount,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String language,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int attempt,
        List<String> chunkIds,
        int batchIndex,
        /** 批窗口前一个文本分块（重建邻接上下文用；无则空）。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String prevChunkId,
        /** 批窗口后一个文本分块（同 prev）。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String nextChunkId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfParentObsId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceparent,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfUserId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfSessionId) {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    public QuestionBatchPayload {
        knowledgeBaseId = knowledgeBaseId == null ? "" : knowledgeBaseId;
        knowledgeId = knowledgeId == null ? "" : knowledgeId;
        language = language == null ? "" : language;
        chunkIds = chunkIds == null ? List.of() : new ArrayList<>(chunkIds);
        prevChunkId = prevChunkId == null ? "" : prevChunkId;
        nextChunkId = nextChunkId == null ? "" : nextChunkId;
        lfTraceId = lfTraceId == null ? "" : lfTraceId;
        lfParentObsId = lfParentObsId == null ? "" : lfParentObsId;
        lfTraceparent = lfTraceparent == null ? "" : lfTraceparent;
        lfUserId = lfUserId == null ? "" : lfUserId;
        lfSessionId = lfSessionId == null ? "" : lfSessionId;
    }

    /** 兼容构造：不带追踪载体。 */
    public QuestionBatchPayload(long tenantId, String knowledgeBaseId, String knowledgeId,
                                int questionCount, String language, int attempt, List<String> chunkIds,
                                int batchIndex, String prevChunkId, String nextChunkId) {
        this(tenantId, knowledgeBaseId, knowledgeId, questionCount, language, attempt, chunkIds, batchIndex,
                prevChunkId, nextChunkId, "", "", "", "", "");
    }

    /** 带追踪载体的构造（入队侧用）。 */
    public static QuestionBatchPayload withTracing(long tenantId, String knowledgeBaseId, String knowledgeId,
                                                   int questionCount, String language, int attempt,
                                                   List<String> chunkIds, int batchIndex,
                                                   String prevChunkId, String nextChunkId,
                                                   TracingContext tracing) {
        TracingContext tc = tracing == null ? TracingContext.EMPTY : tracing;
        return new QuestionBatchPayload(tenantId, knowledgeBaseId, knowledgeId, questionCount, language,
                attempt, chunkIds, batchIndex, prevChunkId, nextChunkId,
                tc.traceId(), tc.parentObservationId(), tc.traceparent(), tc.userId(), tc.sessionId());
    }

    /** 追踪载体的结构视图（worker 侧续接用）。 */
    public TracingContext tracing() {
        return new TracingContext(lfTraceId, lfParentObsId, lfTraceparent, lfUserId, lfSessionId);
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
