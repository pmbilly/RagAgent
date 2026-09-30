package com.ragagent.memory.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.settings.MemoryKeys;

/**
 * 长期记忆模块的 langfuse 观测门面（对照 Go {@code internal/tracing/langfuse} 的
 * {@code GetManager().StartSpan} / {@code SpanOptions} / {@code Span.Finish}）。
 *
 * <h2>2026-09-24 C 批接线</h2>
 * <p>此前是空实现门面（Java 侧 langfuse 未实现时保形不产出）；本批起转真：
 * {@link #start} 直接向 {@code LangfuseManager} 开 span，{@link Span#finish} 把
 * output/metadata 原样上报（含两个 {@code summarize*} 的产出）。管理器未启用时
 * 恒为 no-op 句柄，成本与接线前一致。</p>
 */
public final class MemoryTrace {

    private MemoryTrace() {}

    /** 对照 Go {@code defaultHitPreviewLimit}。 */
    public static final int DEFAULT_HIT_PREVIEW_LIMIT = 25;

    /**
     * 对照 Go {@code langfuse.Manager.StartSpan}：开一个 span。
     *
     * <p>调用方拿到的 {@link Span} 必须 {@code finish}——Go 靠显式调用，
     * Java 侧同样是显式，没有 try-finally 包起来是因为 Go 也没有
     * （那些 span 的结束点与业务分支一一对应）。</p>
     */
    public static Span start(String name, Map<String, Object> input) {
        com.ragagent.tracing.langfuse.Span inner = com.ragagent.tracing.langfuse.LangfuseManager.get()
                .startSpan(new com.ragagent.tracing.langfuse.LangfuseManager.SpanOptions(
                        name, input, null));
        return new Span(name, input, inner);
    }

    /** 对照 Go {@code langfuse.Span}：真 span 的薄包装（保留 name/input 访问器供调用点/测试用）。 */
    public static final class Span {
        private final String name;
        private final Map<String, Object> input;
        private final com.ragagent.tracing.langfuse.Span inner;

        Span(String name, Map<String, Object> input, com.ragagent.tracing.langfuse.Span inner) {
            this.name = name;
            this.input = input;
            this.inner = inner;
        }

        public String name() {
            return name;
        }

        public Map<String, Object> input() {
            return input;
        }

        /** 对照 Go {@code Span.Finish(output, metadata, err)}：非空 err → ERROR 状态 + exception 事件。 */
        public void finish(Map<String, Object> output, Map<String, Object> metadata, Throwable error) {
            if (inner == null) {
                return;
            }
            String err = error == null ? null
                    : (error.getMessage() == null ? error.toString() : error.getMessage());
            inner.finish(output, metadata, err);
        }
    }

    /**
     * 对照 Go {@code langfuse.TruncateRunes}：截到最多 {@code maxRunes} 个码点，
     * 且被截断时**追加 "..."**。
     */
    public static String truncateRunes(String s, int maxRunes) {
        if (maxRunes <= 0) {
            return "";
        }
        if (s == null) {
            return "";
        }
        if (MemoryKeys.runeLength(s) <= maxRunes) {
            return s;
        }
        return MemoryKeys.runeSlice(s, maxRunes) + "...";
    }

    /**
     * 对照 Go {@code langfuse.SummarizeMemoryRecallOutput}：给一个 memory.recall span
     * 造 output。{@code recalled_items} 恒在，被截断时才有 {@code recalled_items_truncated}。
     */
    public static Map<String, Object> summarizeMemoryRecallOutput(Map<String, Object> meta,
                                                                 List<MemoryItem> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (meta != null) {
            out.putAll(meta);
        }
        List<Map<String, Object>> recalled = previewItems(items, DEFAULT_HIT_PREVIEW_LIMIT);
        out.put("recalled_items", recalled.isEmpty() ? null : recalled);
        if (items != null && items.size() > DEFAULT_HIT_PREVIEW_LIMIT) {
            out.put("recalled_items_truncated", items.size() - DEFAULT_HIT_PREVIEW_LIMIT);
        }
        return out;
    }

    /** 对照 Go {@code summarizeMemoryItems}。 */
    private static List<Map<String, Object>> previewItems(List<MemoryItem> items, int limit) {
        int effective = limit <= 0 ? DEFAULT_HIT_PREVIEW_LIMIT : limit;
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int n = Math.min(items.size(), effective);
        List<Map<String, Object>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            MemoryItem item = items.get(i);
            if (item == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("kind", item.getKind());
            row.put("topic", truncateRunes(item.getTopic(), 80));
            row.put("importance", item.getImportance());
            row.put("preview", truncateRunes(item.getContent(), 160));
            out.add(row);
        }
        return out;
    }

    /**
     * 对照 Go {@code langfuse.SummarizeRetrievalContextOutput}。
     *
     * <p>照抄那条小规则：{@code background} 为空时**整个键被删掉**（不是留空串）。</p>
     */
    public static Map<String, Object> summarizeRetrievalContextOutput(
            String background, List<String> interests, List<String> documents, List<MemoryItem> items) {
        List<Map<String, Object>> conditioned = previewItems(items, DEFAULT_HIT_PREVIEW_LIMIT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("background", truncateRunes(background, 240));
        out.put("interest_count", interests == null ? 0 : interests.size());
        out.put("document_count", documents == null ? 0 : documents.size());
        out.put("interests", interests);
        out.put("documents", documents);
        out.put("conditioned_items", conditioned.isEmpty() ? null : conditioned);
        if ("".equals(out.get("background"))) {
            out.remove("background");
        }
        return out;
    }
}
