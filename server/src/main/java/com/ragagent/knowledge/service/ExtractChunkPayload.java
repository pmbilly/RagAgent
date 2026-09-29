package com.ragagent.knowledge.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TracingContext;

/**
 * 分块图抽取任务的载荷。
 * <p>{@code knowledge_id} / {@code attempt} / {@code chunk_index} 三个键带 omitempty：
 * 缺省表示"旧版在飞任务"——worker 侧因此跳过 span 记录（无法挂到父 attempt 的
 * postprocess 阶段）。</p>
 * Java 侧同形，空值整键省略。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractChunkPayload(
        long tenantId,
        String chunkId,
        String modelId,
        /** 关联回父 attempt 的 postprocess 阶段（0/"" = 跳过 span 记录）。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String knowledgeId,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int attempt,
        /** 该分块在父知识文本分块集中的 0 基序数（子 span 名后缀 {@code chunk[i]}）。 */
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) int chunkIndex,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfParentObsId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceparent,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfUserId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfSessionId) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ExtractChunkPayload {
        chunkId = chunkId == null ? "" : chunkId;
        modelId = modelId == null ? "" : modelId;
        knowledgeId = knowledgeId == null ? "" : knowledgeId;
        lfTraceId = lfTraceId == null ? "" : lfTraceId;
        lfParentObsId = lfParentObsId == null ? "" : lfParentObsId;
        lfTraceparent = lfTraceparent == null ? "" : lfTraceparent;
        lfUserId = lfUserId == null ? "" : lfUserId;
        lfSessionId = lfSessionId == null ? "" : lfSessionId;
    }

    /** 兼容构造：不带追踪载体。 */
    public ExtractChunkPayload(long tenantId, String chunkId, String modelId,
                               String knowledgeId, int attempt, int chunkIndex) {
        this(tenantId, chunkId, modelId, knowledgeId, attempt, chunkIndex, "", "", "", "", "");
    }

    /** 带追踪载体的构造（入队侧用；载体为空时与兼容构造等价）。 */
    public static ExtractChunkPayload withTracing(long tenantId, String chunkId, String modelId,
                                                  String knowledgeId, int attempt, int chunkIndex,
                                                  TracingContext tracing) {
        TracingContext tc = tracing == null ? TracingContext.EMPTY : tracing;
        return new ExtractChunkPayload(tenantId, chunkId, modelId, knowledgeId, attempt, chunkIndex,
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
            throw new IllegalStateException("marshal extract chunk payload failed", e);
        }
    }

    public static ExtractChunkPayload fromJson(String json) {
        try {
            return MAPPER.readValue(json, ExtractChunkPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("unmarshal extract chunk payload: " + e.getMessage(), e);
        }
    }
}
