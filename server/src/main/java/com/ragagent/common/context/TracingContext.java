package com.ragagent.common.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 可嵌入异步负载的观测上下文：
 * 把请求侧的 W3C traceparent（外加 trace id / 用户 / 会话提示）随任务负载跨进程携带，
 * worker 侧取出后续接同一条 trace —— 让 Langfuse 把「HTTP 请求 + 异步处理」缝成一棵树。
 *
 * <p>五个键一律 {@code lf_} 前缀 + omitempty：空值**整键省略**，
 * 未启用 langfuse 时负载字节不变；旧负载（无这些键）也能反序列化。</p>
 */
@JsonPropertyOrder({"lf_trace_id", "lf_parent_obs_id", "lf_traceparent", "lf_user_id", "lf_session_id"})
@JsonIgnoreProperties(ignoreUnknown = true)
public record TracingContext(
        /** 发起该任务的根 trace id（兼容旧负载用；关联以 traceparent 为准）。 */
        @JsonProperty("lf_trace_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String traceId,
        /** 仅向后兼容保留：OTLP 路径的父子关系走 traceparent。 */
        @JsonProperty("lf_parent_obs_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String parentObservationId,
        /** W3C Trace Context：{@code 00-<trace_id>-<span_id>-<flags>}。 */
        @JsonProperty("lf_traceparent")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String traceparent,
        /** 跨异步边界保留的用户/租户标签（无上游 trace 时让独立根仍归到正确的租户）。 */
        @JsonProperty("lf_user_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String userId,
        /** 同上：跨边界保留的会话标签。 */
        @JsonProperty("lf_session_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String sessionId) {

    /** 全空载体。 */
    public static final TracingContext EMPTY = new TracingContext("", "", "", "", "");

    /**
     * 「空载体 → 整键省略」的序列化过滤器。
     *
     * <p>为什么需要它：record 的属性按<b>访问器</b>序列化，而各载荷的 {@code tracing()}
     * 会把 {@code null} 归一成 {@link #EMPTY}（消费侧不必判空）——于是字段上的
     * {@code @JsonInclude(NON_NULL)} 永远看不到 null，空载体就会写成 {@code "tracing":{}}。
     * 用 {@code Include.CUSTOM} + 本过滤器按<b>值</b>判定：与空载体相等即省略，
     * 未启用追踪时负载字节与平铺期一致。</p>
     */
    public static final class EmptyOmitFilter {
        @Override
        public boolean equals(Object other) {
            return other instanceof TracingContext tc && tc.isEmpty();
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    public TracingContext {
        traceId = traceId == null ? "" : traceId;
        parentObservationId = parentObservationId == null ? "" : parentObservationId;
        traceparent = traceparent == null ? "" : traceparent;
        userId = userId == null ? "" : userId;
        sessionId = sessionId == null ? "" : sessionId;
    }

    /** 是否为空载体（调度短路径与断言用）。{@code @JsonIgnore}：
     *  否则 Jackson 把 {@code isEmpty()} 当布尔属性 {@code empty} 序列化进负载。 */
    @JsonIgnore
    public boolean isEmpty() {
        return traceId.isEmpty() && parentObservationId.isEmpty() && traceparent.isEmpty()
                && userId.isEmpty() && sessionId.isEmpty();
    }
}
