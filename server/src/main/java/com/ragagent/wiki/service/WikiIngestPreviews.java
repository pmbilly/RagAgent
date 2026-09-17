package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * trace 视图用的"内容预览"渲染（对照 Go wiki_ingest.go 的
 * {@code previewExtractedItems} / {@code topCitedSlugs} / {@code previewNewSlugs}，
 * L1419-1504）。
 *
 * <p>这些结果会进 spans 表的 JSONB output 列，因此<b>累计体积</b>比逐项保真更重要；
 * 每项都被裁到一个小而固定的预算。</p>
 *
 * <p><b>键序</b>：Go 返回 {@code map[string]string} / {@code map[string]any}，
 * 经 {@code encoding/json} 序列化时<b>按键的字母序</b>输出（Go 对 map 的既定行为）。
 * Java 侧因此统一用 {@link TreeMap} 构造，保证与 Go 的字节输出一致——
 * 这正是约定文档 §9 反复强调的"map 形态必须按字母序构造"。</p>
 */
public final class WikiIngestPreviews {

    private WikiIngestPreviews() {}

    /** 对照 Go {@code previewExtractedItems}：键为 {description, name, slug}（字母序）。 */
    public static List<Map<String, String>> previewExtractedItems(List<ExtractedItem> items, int limit) {
        if (limit <= 0) {
            limit = 1;
        }
        if (items == null) {
            return List.of();
        }
        List<ExtractedItem> head = items.size() > limit ? items.subList(0, limit) : items;
        List<Map<String, String>> out = new ArrayList<>(head.size());
        for (ExtractedItem it : head) {
            Map<String, String> row = new TreeMap<>();
            row.put("name", WikiTextUtils.previewText(it.getName(), 60));
            row.put("slug", it.getSlug());
            row.put("description", WikiTextUtils.previewText(it.getDescription(), 120));
            out.add(row);
        }
        return out;
    }

    /**
     * 对照 Go {@code topCitedSlugs}：按 chunk 引用数取 top-N 的 slug。
     *
     * <p>供 {@code postprocess.wiki.classify} span 用，让 trace 能展示"引用阶段给哪些
     * 候选 slug 挂了最多 chunk"——排查"这次 LLM 抽了奇怪的东西"时不必打开完整的
     * chunk 列表做 diff。</p>
     *
     * <p>排序：chunk 数<b>降序</b>；并列时 slug <b>升序</b>（对照 Go 的
     * {@code if entries[i].count != entries[j].count → count 降序; else slug 升序}）。
     * Go 在这一步之前没有对 slug 做二次处理，因此 Java 侧用同样的比较器即可保证
     * 并列项顺序一致。</p>
     *
     * @return null 表示没有引用（对照 Go {@code return nil}）
     */
    public static List<Map<String, Object>> topCitedSlugs(Map<String, List<String>> citations, int limit) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        record Entry(String slug, int count) {}
        List<Entry> entries = new ArrayList<>(citations.size());
        for (Map.Entry<String, List<String>> e : citations.entrySet()) {
            int count = e.getValue() == null ? 0 : e.getValue().size();
            entries.add(new Entry(e.getKey(), count));
        }
        entries.sort(Comparator.comparingInt(Entry::count).reversed()
                .thenComparing(Entry::slug));
        if (limit > 0 && entries.size() > limit) {
            entries = entries.subList(0, limit);
        }
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            Map<String, Object> row = new TreeMap<>();
            row.put("slug", e.slug());
            row.put("chunks", e.count());
            out.add(row);
        }
        return out;
    }

    /** 对照 Go {@code previewNewSlugs}：键为 {chunks, name, slug, type}（字母序）。 */
    public static List<Map<String, String>> previewNewSlugs(List<NewSlugFromCitation> items, int limit) {
        if (limit <= 0) {
            limit = 1;
        }
        if (items == null) {
            return List.of();
        }
        List<NewSlugFromCitation> head = items.size() > limit ? items.subList(0, limit) : items;
        List<Map<String, String>> out = new ArrayList<>(head.size());
        for (NewSlugFromCitation it : head) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", WikiTextUtils.previewText(it.name(), 60));
            row.put("slug", it.slug());
            row.put("type", it.type());
            row.put("chunks", String.valueOf(it.sourceChunkCount()));
            out.add(new TreeMap<>(row));
        }
        return out;
    }
}
