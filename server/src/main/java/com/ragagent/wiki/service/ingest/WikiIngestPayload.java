package com.ragagent.wiki.service.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * wiki ingest 批次触发任务的载荷。
 *
 * <p>真正的文档 ID 存在 {@code task_pending_ops} 表里；本载荷只携带触发元数据，
 * 让 worker 能解析出队列三元组 {@code (task_type, scope, scope_id)} 并处理
 * 该元组下排队的所有行。</p>
 *
 * <p><b>langfuse 追踪载体</b>：五个 {@code lf_*} 组件直接<b>平铺</b>在 record 上
 * （与业务键同级；{@code @JsonUnwrapped} 不支持 record 的 Creator 参数）；空值整键省略，
 * 未启用追踪时载荷字节与接线前一致。结构视图见 {@link #tracing()}。
 * 这五个键是 {@code lf_*} 平铺追踪载具的一部分（四个域的队列载荷同形），键名刻意保持
 * snake 并保留空值省略——前缀是防撞名的命名空间，见 HANDOFF §14.6 边界清单。</p>
 *
 * <p>载荷只在单 JVM 内的 {@link InProcessWikiIngestTaskQueue} 流动（死信留档除外），
 * 自有键名即 Java 字段名（camelCase），键序随声明序。</p>
 */
public record WikiIngestPayload(
        long tenantId,
        String knowledgeBaseId,
        String language,
        /** 发起该任务的根 trace id（兼容旧负载；关联以 traceparent 为准）。 */
        @JsonProperty("lf_trace_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceId,
        /** 仅向后兼容保留：OTLP 路径的父子关系走 traceparent。 */
        @JsonProperty("lf_parent_obs_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfParentObsId,
        /** W3C Trace Context：{@code 00-<trace_id>-<span_id>-<flags>}。 */
        @JsonProperty("lf_traceparent")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfTraceparent,
        /** 跨异步边界保留的用户/租户标签。 */
        @JsonProperty("lf_user_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfUserId,
        /** 跨异步边界保留的会话标签。 */
        @JsonProperty("lf_session_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lfSessionId) {

    public WikiIngestPayload {
        lfTraceId = lfTraceId == null ? "" : lfTraceId;
        lfParentObsId = lfParentObsId == null ? "" : lfParentObsId;
        lfTraceparent = lfTraceparent == null ? "" : lfTraceparent;
        lfUserId = lfUserId == null ? "" : lfUserId;
        lfSessionId = lfSessionId == null ? "" : lfSessionId;
    }

    /** 兼容构造：不带追踪载体（等价于未启用追踪的入队点）。 */
    public WikiIngestPayload(long tenantId, String knowledgeBaseId, String language) {
        this(tenantId, knowledgeBaseId, language, "", "", "", "", "");
    }

    /** 带追踪载体的构造（入队侧用；载体为空时与兼容构造等价）。 */
    public static WikiIngestPayload withTracing(long tenantId, String knowledgeBaseId,
                                                String language,
                                                com.ragagent.common.context.TracingContext tracing) {
        com.ragagent.common.context.TracingContext tc = tracing == null
                ? com.ragagent.common.context.TracingContext.EMPTY : tracing;
        return new WikiIngestPayload(tenantId, knowledgeBaseId, language,
                tc.traceId(), tc.parentObservationId(), tc.traceparent(),
                tc.userId(), tc.sessionId());
    }

    /** 追踪载体的结构视图（worker 侧续接用）。 */
    public com.ragagent.common.context.TracingContext tracing() {
        return new com.ragagent.common.context.TracingContext(
                lfTraceId, lfParentObsId, lfTraceparent, lfUserId, lfSessionId);
    }

    /** 零值载荷（测试与"仅知 KB"的调度路径用）。 */
    public static WikiIngestPayload of(String knowledgeBaseId) {
        return new WikiIngestPayload(0L, knowledgeBaseId, null);
    }

    /** 供 {@code Map<String,String>} 模板数据使用：KB 级下游步骤只关心 id 与语言。 */
    public String languageOrEmpty() {
        return language == null ? "" : language;
    }
}
