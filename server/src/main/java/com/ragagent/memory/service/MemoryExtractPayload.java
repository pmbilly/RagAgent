package com.ragagent.memory.service;

import com.ragagent.common.web.JsonMappers;
import com.ragagent.common.context.TracingContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.memory.domain.MemoryScope;

/**
 * 一次蒸馏任务的全部输入。
 *
 * <h2>为什么所有东西都在负载里</h2>
 * <p>任务在新线程上跑，请求当时的上下文（{@code TenantContext} 等）一律为空——
 * 所以请求当时知道的、而负载没带的作用域，到任务真正运行时已经没了。
 * 所有需要的信息都必须随负载携带。</p>
 *
 * <h2>⚠️ 一处已知差异</h2>
 * <ol>
 *   <li><b>{@code language} 恒为空串</b>：语言上下文（{@code LanguageContextKey}）
 *       尚未接入这条链路，因此没有来源。它只影响提示词语言，且只在这条内部链路上传递、
 *       不出响应，所以外部不可见。</li>
 * </ol>
 *
 * <p><b>langfuse 追踪载体</b>：以嵌套对象 {@code tracing} 随负载携带（早期是五个平铺的
 * {@code lf_*} 键；载具只在进程内队列流动、无外部消费者，故改形）。
 * 空载体整键省略，worker 侧取 {@link #tracing()} 续接同一棵树。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryExtractPayload(
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("subject_id") String subjectId,
        @JsonProperty("session_id") String sessionId,
        /** 结束触发那一轮的助手消息，用来界定抽取窗口，也是产出条目的来源。 */
        @JsonProperty("message_id") String messageId,
        /**
         * {@code chat_model_id} 空串时**省略整个键**。
         *
         * <p>用 {@code NON_DEFAULT} 而不是 {@code NON_NULL}：字段在紧凑构造器里已经把
         * null 归一成 {@code ""}，省略的是**空串**
         * （§7.5 第 5 条）。{@code tenant_id} 是 0 时必须照常输出——
         * 这也是注解只加在这两个分量上、不放类头的原因。</p>
         */
        @JsonProperty("chat_model_id")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) String chatModelId,
        /** {@code language} 同样 omitempty。 */
        @JsonProperty("language")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) String language,
        /**
         * 观测载体（嵌套键 {@code tracing}，五个 {@code lf_*} 组件收在里面）。
         * 空载体经 {@code EmptyOmitFilter} 整键省略。
         */
        @JsonProperty("tracing")
        @JsonInclude(value = JsonInclude.Include.CUSTOM,
                valueFilter = TracingContext.EmptyOmitFilter.class) TracingContext tracing) {

    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public MemoryExtractPayload {
        subjectId = subjectId == null ? "" : subjectId;
        sessionId = sessionId == null ? "" : sessionId;
        messageId = messageId == null ? "" : messageId;
        chatModelId = chatModelId == null ? "" : chatModelId;
        language = language == null ? "" : language;
    }

    /** 兼容构造：不带追踪载体（等价于未启用追踪的入队点）。 */
    public MemoryExtractPayload(long tenantId, String subjectId, String sessionId,
                                String messageId, String chatModelId, String language) {
        this(tenantId, subjectId, sessionId, messageId, chatModelId, language, null);
    }

    /** 带追踪载体的构造（入队侧用；载体为空时与兼容构造等价）。 */
    public static MemoryExtractPayload withTracing(long tenantId, String subjectId, String sessionId,
                                                   String messageId, String chatModelId, String language,
                                                   com.ragagent.common.context.TracingContext tracing) {
        com.ragagent.common.context.TracingContext tc = tracing == null
                ? com.ragagent.common.context.TracingContext.EMPTY : tracing;
        return new MemoryExtractPayload(tenantId, subjectId, sessionId, messageId, chatModelId,
                language, tc.isEmpty() ? null : tc);
    }

    /** 追踪载体的结构视图（worker 侧续接用）。 */
    public com.ragagent.common.context.TracingContext tracing() {
        return tracing == null ? com.ragagent.common.context.TracingContext.EMPTY : tracing;
    }

    /** 全零值负载，供"没有触发轮次"的调用点。 */
    public static MemoryExtractPayload empty() {
        return new MemoryExtractPayload(0, "", "", "", "", "", null);
    }

    /** 负载以 JSON 形态进队列。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("marshal memory extract payload failed", e);
        }
    }

    /** 从队列里的 JSON 反序列化负载。 */
    public static MemoryExtractPayload fromJson(String json) {
        try {
            return MAPPER.readValue(json, MemoryExtractPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("unmarshal memory extract payload: " + e.getMessage(), e);
        }
    }

    /** 重建本负载的记忆 scope。 */
    public MemoryScope scope() {
        return new MemoryScope(tenantId, subjectId);
    }
}
