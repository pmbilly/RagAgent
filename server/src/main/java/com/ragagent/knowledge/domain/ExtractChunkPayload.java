package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TracingContext;
import com.ragagent.common.web.JsonMappers;

/**
 * 分块图抽取任务的载荷（队列内传递）。
 *
 * <p>{@code knowledgeId} / {@code attempt} / {@code chunkIndex} 为 0 或空表示「旧版在飞任务」——
 * worker 侧据此跳过 span 记录（挂不到父 attempt 的 postprocess 阶段）。所有键恒输出。
 */
public record ExtractChunkPayload(
        long tenantId,
        String chunkId,
        String modelId,
        /** 关联回父 attempt 的 postprocess 阶段（0/"" = 跳过 span 记录）。 */
        String knowledgeId,
        int attempt,
        /** 该分块在父知识文本分块集中的 0 基序数（子 span 名后缀 {@code chunk[i]}）。 */
        int chunkIndex,
        String lfTraceId,
        String lfParentObsId,
        String lfTraceparent,
        String lfUserId,
        String lfSessionId) {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

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
