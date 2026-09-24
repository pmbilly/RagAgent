package com.ragagent.knowledge.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TracingContext;

/**
 * 分块图抽取任务的载荷（对照 Go {@code types.ExtractChunkPayload}，
 * internal/types/task.go L281-296）。
 *
 * <p>{@code knowledge_id} / {@code attempt} / {@code chunk_index} 三个键带 omitempty：
 * 缺省表示"旧版在飞任务"——worker 侧因此跳过 span 记录（无法挂到父 attempt 的
 * postprocess 阶段）。</p>
 *
 * <p><b>追踪载体</b>：Go 内嵌 {@code types.TracingContext}（平铺的 {@code lf_*} 五键）；
 * Java 侧同形，空值整键省略。</p>
 */
@JsonPropertyOrder({"tenant_id", "chunk_id", "model_id", "knowledge_id", "attempt", "chunk_index",
        "lf_trace_id", "lf_parent_obs_id", "lf_traceparent", "lf_user_id", "lf_session_id"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractChunkPayload(
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("model_id") String modelId,
        /** 关联回父 attempt 的 postprocess 阶段（0/"" = 跳过 span 记录）。 */
        @JsonProperty("knowledge_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String knowledgeId,
        @JsonProperty("attempt") @JsonInclude(JsonInclude.Include.NON_DEFAULT) int attempt,
        /** 该分块在父知识文本分块集中的 0 基序数（子 span 名后缀 {@code chunk[i]}）。 */
        @JsonProperty("chunk_index") @JsonInclude(JsonInclude.Include.NON_DEFAULT) int chunkIndex,
        @JsonProperty("lf_trace_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceId,
        @JsonProperty("lf_parent_obs_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfParentObsId,
        @JsonProperty("lf_traceparent") @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceparent,
        @JsonProperty("lf_user_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfUserId,
        @JsonProperty("lf_session_id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfSessionId) {

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

    /** 对照 Go 的 {@code json.Marshal(payload)}：载荷以 JSON 形态进队列。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("marshal extract chunk payload failed", e);
        }
    }

    /** 对照 Go 的 {@code json.Unmarshal(t.Payload(), &p)}。 */
    public static ExtractChunkPayload fromJson(String json) {
        try {
            return MAPPER.readValue(json, ExtractChunkPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("unmarshal extract chunk payload: " + e.getMessage(), e);
        }
    }
}
