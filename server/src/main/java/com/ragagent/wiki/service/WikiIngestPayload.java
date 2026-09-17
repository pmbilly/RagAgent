package com.ragagent.wiki.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * wiki ingest 批次触发任务的载荷（对照 Go {@code WikiIngestPayload}，
 * wiki_ingest.go L289-299）。
 *
 * <p>真正的文档 ID 存在 {@code task_pending_ops} 表里；本载荷只携带触发元数据，
 * 让 worker 能解析出队列三元组 {@code (task_type, scope, scope_id)} 并处理
 * 该元组下排队的所有行。</p>
 *
 * <p><b>与 Go 的一处取舍</b>：Go 内嵌 {@code types.TracingContext}（langfuse 追踪上下文）。
 * Java 侧未实现 langfuse 追踪（见 {@code translation-conventions.md} §9 阶段 4.0
 * 已知差异 1），因此这里只保留 {@code tenant_id} / {@code knowledge_base_id} /
 * {@code language} 三个业务字段——等价于 Go 在<b>未启用追踪</b>时的载荷。</p>
 *
 * <p><b>JSON 键序</b>：Go 是 {@code struct} 序列化，按字段声明序输出，
 * 因此 Java 侧用 {@link JsonPropertyOrder} 钉住 <b>tenant_id, knowledge_base_id,
 * language</b>（{@code language} 有 omitempty）。载荷会落进
 * {@code task_pending_ops.payload}，跨语言读写时键序不影响语义，但保持一致便于比对。</p>
 */
@com.fasterxml.jackson.annotation.JsonPropertyOrder({
        "tenant_id", "knowledge_base_id", "language"})
public record WikiIngestPayload(
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @JsonProperty("language") @JsonInclude(JsonInclude.Include.NON_EMPTY) String language) {

    /** 对照 Go 的零值载荷（测试与"仅知 KB"的调度路径用）。 */
    public static WikiIngestPayload of(String knowledgeBaseId) {
        return new WikiIngestPayload(0L, knowledgeBaseId, null);
    }

    /** 供 {@code Map<String,String>} 模板数据使用：KB 级下游步骤只关心 id 与语言。 */
    public String languageOrEmpty() {
        return language == null ? "" : language;
    }
}
