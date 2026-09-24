package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单篇文档的摄取结果，供批次的收尾阶段使用（对照 Go {@code docIngestResult} +
 * {@code wikiIngestPageRef}，wiki_ingest.go L1257-1284）。
 *
 * <p>Java 侧把 Go 的两个 struct 合并成一个类（{@link PageRef} 作嵌套 record），
 * 因为它们永远成对出现，且 Go 侧 {@code wikiIngestPageRef} 只有两个字段。</p>
 */
public class DocIngestResult {

    /** 对照 Go {@code wikiIngestPageRef}（L1258-1261）：本文档触碰过的页面，带 slug 与人类可读标题。 */
    public record PageRef(String slug, String title) {}

    private String knowledgeId = "";
    private String docTitle = "";

    /** 对照 Go {@code Summary}：文档的一句话摘要（来自摘要页） */
    private String summary = "";

    /**
     * 对照 Go {@code Pages}：本文档触碰过的 wiki 页面，同时携带链接/撤回记账用的
     * slug 与其人类可读标题。
     */
    private List<PageRef> pages = new ArrayList<>();

    /**
     * 对照 Go {@code MapStats}：{@code mapOneDocument} 结束时抓取的逐文档 map 阶段指标。
     * 会进 {@code postprocess.wiki} span 的 output，让 trace 视图能显示"map 阶段产出了什么"
     * ——尽管 span 本身要等到批次的 reduce + cleanup 阶段结束才关闭（这样用户看到的
     * 耗时覆盖本文档的整条管线，而不只是 LLM 抽取）。
     */
    private Map<String, Object> mapStats;

    /**
     * 对照 Go {@code WikiSpan}：{@code mapOneDocument} 开头打开的 postprocess.wiki
     * 子 span（已接 {@link com.ragagent.knowledge.service.SpanTracker}）。
     * 可能为 null（没找到父 attempt）——nil 时所有 tracker 辅助方法均为 no-op，
     * 与 Go 的 nil span 同形。
     */
    private com.ragagent.knowledge.service.SpanTracker.SpanHandle wikiSpan;

    public DocIngestResult() {}

    public DocIngestResult(String knowledgeId) {
        this.knowledgeId = knowledgeId == null ? "" : knowledgeId;
    }

    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { knowledgeId = v == null ? "" : v; }

    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String v) { docTitle = v == null ? "" : v; }

    public String getSummary() { return summary; }
    public void setSummary(String v) { summary = v == null ? "" : v; }

    public List<PageRef> getPages() { return pages; }
    public void setPages(List<PageRef> v) { pages = v == null ? new ArrayList<>() : v; }

    public Map<String, Object> getMapStats() { return mapStats; }
    public void setMapStats(Map<String, Object> v) { mapStats = v; }

    public com.ragagent.knowledge.service.SpanTracker.SpanHandle getWikiSpan() { return wikiSpan; }
    public void setWikiSpan(com.ragagent.knowledge.service.SpanTracker.SpanHandle v) {
        wikiSpan = v;
    }
}
