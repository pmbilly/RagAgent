package com.ragagent.memory.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.memory.domain.MemoryScope;

/**
 * 一次蒸馏任务的全部输入（对照 Go {@code types.MemoryExtractPayload}，
 * internal/types/task.go L264-278）。
 *
 * <h2>为什么所有东西都在负载里</h2>
 * <p>Go 的 asynq 与 Lite 执行器交给 handler 的是一个**裸 ctx**，
 * 所以请求当时知道的、而负载没带的作用域，到任务真正运行时已经没了。
 * Java 侧的进程内队列同样在新线程上跑，{@code TenantContext} 一律为空——
 * 所以这个约束在 Java 里只会更紧，不会更松。</p>
 *
 * <h2>⚠️ 与 Go 的两处已知差异</h2>
 * <ol>
 *   <li><b>{@code language} 恒为空串</b>：Go 由 {@code types.LanguageNameFromContext(ctx)}
 *       填，Java 侧的语言上下文（{@code LanguageContextKey}）尚未翻译，
 *       因此没有来源。它只影响提示词语言，且只在这条内部链路上传递、不出响应，
 *       所以外部不可见。</li>
 *   <li><b>{@code TracingContext} 未复刻</b>：Go 把 langfuse 的追踪头一起塞进负载
 *       （{@code langfuse.InjectTracing}）；Java 的 langfuse 是空实现，
 *       带上也没有消费者。</li>
 * </ol>
 * <p>两者都在 §8 的模块日志里记过。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryExtractPayload(
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("subject_id") String subjectId,
        @JsonProperty("session_id") String sessionId,
        /** 结束触发那一轮的助手消息，用来界定抽取窗口，也是产出条目的来源。 */
        @JsonProperty("message_id") String messageId,
        /**
         * {@code chat_model_id} 带 omitempty：空串时**省略键**（照抄 Go 的 tag）。
         *
         * <p>用 {@code NON_DEFAULT} 而不是 {@code NON_NULL}：字段在紧凑构造器里已经把
         * null 归一成 {@code ""}，而 Go 的 {@code omitempty} 省略的是**空串**
         * （§7.5 第 5 条）。{@code tenant_id} 没有 omitempty，所以 0 必须照常输出——
         * 这也是注解只加在这两个分量上、不放类头的原因。</p>
         */
        @JsonProperty("chat_model_id")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) String chatModelId,
        /** {@code language} 同样 omitempty。 */
        @JsonProperty("language")
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) String language) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public MemoryExtractPayload {
        subjectId = subjectId == null ? "" : subjectId;
        sessionId = sessionId == null ? "" : sessionId;
        messageId = messageId == null ? "" : messageId;
        chatModelId = chatModelId == null ? "" : chatModelId;
        language = language == null ? "" : language;
    }

    /** 对照 Go 的 {@code types.MemoryExtractPayload{}}：全零值，供"没有触发轮次"的调用点。 */
    public static MemoryExtractPayload empty() {
        return new MemoryExtractPayload(0, "", "", "", "", "");
    }

    /** 对照 Go 的 {@code json.Marshal(payload)}：负载以 JSON 形态进队列。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("marshal memory extract payload failed", e);
        }
    }

    /** 对照 Go 的 {@code json.Unmarshal(task.Payload(), &payload)}。 */
    public static MemoryExtractPayload fromJson(String json) {
        try {
            return MAPPER.readValue(json, MemoryExtractPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("unmarshal memory extract payload: " + e.getMessage(), e);
        }
    }

    /** 对照 Go 的 {@code MemoryScope{TenantID, SubjectID}} 重建 + {@code Valid()}。 */
    public MemoryScope scope() {
        return new MemoryScope(tenantId, subjectId);
    }
}
