package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 知识库的 wiki 专属配置。
 *
 * <p>适用于"启用了 wiki 功能"的文档型知识库。wiki 功能<b>是否开启</b>由
 * {@code IndexingStrategy.WikiEnabled} 控制（见 KnowledgeBaseIndexingStrategy）；本结构只承载
 * wiki 专属的调节项。</p>
 *
 * <p>{@code knowledge_bases.wiki_config} 列在 Java 侧由 {@code KnowledgeBase.wikiConfig}
 * 以 {@code JsonNode} 承载，本类是<b>值类型</b>，另提供 {@link #toJson()} /
 * {@link #fromJson(String)} 两个等价入口供 service 使用。</p>
 *
 * <p>JSON 契约（键名为 snake，§11 登记边界；顺序 = {@code @JsonPropertyOrder} 声明序）：</p>
 * <ul>
 *   <li>{@code synthesis_model_id} / {@code max_pages_per_ingest} 恒输出；</li>
 *   <li>其余字段空串/0 整键省略（{@code @JsonInclude(NON_EMPTY)} 对 String，
 *       {@code NON_DEFAULT} 对 int）。</li>
 * </ul>
 *
 * <p>读路径<b>容忍未知属性</b>：历史行里会有 {@code enabled} / {@code auto_ingest}
 * 等已退役的键，Jackson 默认会报错——故本类的
 * {@link #fromJson(String)} 用配了 {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 的 mapper。</p>
 */
public class WikiConfig {

    /** 读路径宽容的 mapper（忽略未知字段） */
    private static final ObjectMapper READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 用于 wiki 页面生成与更新的 LLM 模型 ID */
    private String synthesisModelId = "";

    /** 单次 ingest 允许创建/更新的页面数上限（0 = 不限） */
    private int maxPagesPerIngest;

    /**
     * 控制 Pass 0 每篇文档抽取多少候选 slug。空 / 未知值按 standard 处理
     * （见 {@link WikiExtractionGranularity#normalize(String)}）。
     * 类型刻意保持 String：历史行/未设置的值是 ""，JSON 往返须保留空串。
     */
    private String extractionGranularity = "";

    /** 控制生成 summary/entity/index 文案的语气、结构与侧重（引用与合并规则仍归系统所有） */
    private String contentInstructions = "";

    /** 告诉候选抽取要强调哪些领域概念，但不替换稳定的 JSON/引用协议 */
    private String extractionInstructions = "";

    /** 单批（batch）认领并处理的待办数；0 → 硬编码默认 5 */
    private int ingestBatchSize;

    /** Map 阶段（逐文档抽取 + 摘要 + chunk 引用）的并发上限；0 → 默认 10 */
    private int ingestMapParallel;

    /** Reduce 阶段（逐 slug 写页面）的并发上限；0 → 默认 10 */
    private int ingestReduceParallel;

    /** 本知识库可同时运行的 ingest 批次数上限（共享 worker 池）；0 → 默认 4 */
    private int ingestMaxInflight;

    // ── *OrDefault 系列：实例方法在字段非正时回落 fallback；静态入口承载
    //    "config 可能为 null" 的语义（null → fallback）。
    //    ⚠️ 全部 @JsonIgnore：否则 Jackson 会把它们当属性写进 wiki_config jsonb，
    //    回读触发 UnrecognizedPropertyException（复发率最高的坑）。 ──

    @JsonIgnore
    public int ingestBatchSizeOrDefault(int fallback) {
        return ingestBatchSize > 0 ? ingestBatchSize : fallback;
    }

    @JsonIgnore
    public int ingestMapParallelOrDefault(int fallback) {
        return ingestMapParallel > 0 ? ingestMapParallel : fallback;
    }

    @JsonIgnore
    public int ingestReduceParallelOrDefault(int fallback) {
        return ingestReduceParallel > 0 ? ingestReduceParallel : fallback;
    }

    @JsonIgnore
    public int ingestMaxInflightOrDefault(int fallback) {
        return ingestMaxInflight > 0 ? ingestMaxInflight : fallback;
    }

    /** null 安全的静态入口：null config → fallback */
    public static int ingestBatchSizeOrDefault(WikiConfig c, int fallback) {
        return c == null ? fallback : c.ingestBatchSizeOrDefault(fallback);
    }

    /** null 安全的静态入口 */
    public static int ingestMapParallelOrDefault(WikiConfig c, int fallback) {
        return c == null ? fallback : c.ingestMapParallelOrDefault(fallback);
    }

    /** null 安全的静态入口 */
    public static int ingestReduceParallelOrDefault(WikiConfig c, int fallback) {
        return c == null ? fallback : c.ingestReduceParallelOrDefault(fallback);
    }

    /** null 安全的静态入口 */
    public static int ingestMaxInflightOrDefault(WikiConfig c, int fallback) {
        return c == null ? fallback : c.ingestMaxInflightOrDefault(fallback);
    }

    /** 归一化后的抽取粒度（空/未知 → standard），出口为合法字面量 */
    @JsonIgnore
    public String normalizedExtractionGranularity() {
        return WikiExtractionGranularity.normalize(extractionGranularity);
    }

    /** 序列化为 JSON 字符串（写侧），失败抛 IllegalStateException */
    public String toJson() {
        try {
            return READER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("marshal wiki config failed", e);
        }
    }

    /** 从 JSON 反序列化，忽略未知字段；空输入返回 null */
    public static WikiConfig fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return READER.readValue(json, WikiConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("unmarshal wiki config failed: " + json, e);
        }
    }

    // ── 访问器 ──

    public String getSynthesisModelId() { return synthesisModelId; }
    public void setSynthesisModelId(String v) { this.synthesisModelId = v == null ? "" : v; }

    public int getMaxPagesPerIngest() { return maxPagesPerIngest; }
    public void setMaxPagesPerIngest(int v) { this.maxPagesPerIngest = v; }

    public String getExtractionGranularity() { return extractionGranularity; }
    public void setExtractionGranularity(String v) { this.extractionGranularity = v == null ? "" : v; }

    public String getContentInstructions() { return contentInstructions; }
    public void setContentInstructions(String v) { this.contentInstructions = v == null ? "" : v; }

    public String getExtractionInstructions() { return extractionInstructions; }
    public void setExtractionInstructions(String v) { this.extractionInstructions = v == null ? "" : v; }

    public int getIngestBatchSize() { return ingestBatchSize; }
    public void setIngestBatchSize(int v) { this.ingestBatchSize = v; }

    public int getIngestMapParallel() { return ingestMapParallel; }
    public void setIngestMapParallel(int v) { this.ingestMapParallel = v; }

    public int getIngestReduceParallel() { return ingestReduceParallel; }
    public void setIngestReduceParallel(int v) { this.ingestReduceParallel = v; }

    public int getIngestMaxInflight() { return ingestMaxInflight; }
    public void setIngestMaxInflight(int v) { this.ingestMaxInflight = v; }
}
