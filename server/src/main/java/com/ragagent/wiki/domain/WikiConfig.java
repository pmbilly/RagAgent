package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 知识库的 wiki 专属配置（对照 Go types.WikiConfig，internal/types/wiki_page.go L524-630）。
 *
 * <p>适用于"启用了 wiki 功能"的文档型知识库。wiki 功能<b>是否开启</b>由
 * {@code IndexingStrategy.WikiEnabled} 控制（见 KnowledgeBaseIndexingStrategy）；本结构只承载
 * wiki 专属的调节项。</p>
 *
 * <p>GORM 隐式行为清单（约定 §3）：Go 侧实现了 {@code driver.Valuer}/{@code sql.Scanner}
 * （{@code Value()} = json.Marshal，{@code Scan(nil)} 不报错且保持零值）。
 * 但 {@code knowledge_bases.wiki_config} 列在 Java 侧由 {@code KnowledgeBase.wikiConfig}
 * 以 {@code JsonNode} 承载，本类是<b>值类型</b>，另提供 {@link #toJson()} /
 * {@link #fromJson(String)} 两个等价入口供 service 使用。</p>
 *
 * <p>JSON 契约（键名 = Go tag，顺序 = Go 字段声明序）：</p>
 * <ul>
 *   <li>{@code synthesis_model_id} / {@code max_pages_per_ingest} 恒输出（无 omitempty）；</li>
 *   <li>其余字段 omitempty：空串/0 整键省略（{@code @JsonInclude(NON_EMPTY)} 对 String，
 *       {@code NON_DEFAULT} 对 int）。</li>
 * </ul>
 *
 * <p>读路径<b>容忍未知属性</b>（§9）：历史行里会有 {@code enabled} / {@code auto_ingest}
 * 等已退役的键，Go 的 json.Unmarshal 默认忽略它们，Jackson 默认会报错——故本类的
 * {@link #fromJson(String)} 用配了 {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 的 mapper。</p>
 */
@JsonPropertyOrder({
        "synthesis_model_id", "max_pages_per_ingest", "extraction_granularity",
        "content_instructions", "extraction_instructions", "ingest_batch_size",
        "ingest_map_parallel", "ingest_reduce_parallel", "ingest_max_inflight"
})
public class WikiConfig {

    /** 读路径宽容的 mapper（对照 Go json.Unmarshal 的忽略未知字段） */
    private static final ObjectMapper READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 用于 wiki 页面生成与更新的 LLM 模型 ID */
    @JsonProperty("synthesis_model_id")
    private String synthesisModelId = "";

    /** 单次 ingest 允许创建/更新的页面数上限（0 = 不限） */
    @JsonProperty("max_pages_per_ingest")
    private int maxPagesPerIngest;

    /**
     * 控制 Pass 0 每篇文档抽取多少候选 slug。空 / 未知值按 standard 处理
     * （见 {@link WikiExtractionGranularity#normalize(String)}）。
     * 类型刻意保持 String：Go 的零值是 ""，且 omitempty 会把空串整键省略。
     */
    @JsonProperty("extraction_granularity")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String extractionGranularity = "";

    /** 控制生成 summary/entity/index 文案的语气、结构与侧重（引用与合并规则仍归系统所有） */
    @JsonProperty("content_instructions")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String contentInstructions = "";

    /** 告诉候选抽取要强调哪些领域概念，但不替换稳定的 JSON/引用协议 */
    @JsonProperty("extraction_instructions")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String extractionInstructions = "";

    /** 单批（batch）认领并处理的待办数；0 → 硬编码默认 5 */
    @JsonProperty("ingest_batch_size")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int ingestBatchSize;

    /** Map 阶段（逐文档抽取 + 摘要 + chunk 引用）的 errgroup 并发上限；0 → 默认 10 */
    @JsonProperty("ingest_map_parallel")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int ingestMapParallel;

    /** Reduce 阶段（逐 slug 写页面）的 errgroup 并发上限；0 → 默认 10 */
    @JsonProperty("ingest_reduce_parallel")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int ingestReduceParallel;

    /** 本知识库可同时运行的 ingest 批次数上限（共享 worker 池）；0 → 默认 4 */
    @JsonProperty("ingest_max_inflight")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int ingestMaxInflight;

    // ── Go 方法对照：*OrDefault 系列（L581-613）。
    //    Go 里定义在 *WikiConfig 上，nil 接收者返回 fallback；Java 用静态方法承载
    //    "config 可能为 null" 的语义，实例方法做同样的事。
    //    ⚠️ 全部 @JsonIgnore：否则 Jackson 会把它们当属性写进 wiki_config jsonb，
    //    回读触发 UnrecognizedPropertyException（§9 复发率最高的坑）。 ──

    /** 对照 Go (*WikiConfig).IngestBatchSizeOrDefault */
    @JsonIgnore
    public int ingestBatchSizeOrDefault(int fallback) {
        return ingestBatchSize > 0 ? ingestBatchSize : fallback;
    }

    /** 对照 Go (*WikiConfig).IngestMapParallelOrDefault */
    @JsonIgnore
    public int ingestMapParallelOrDefault(int fallback) {
        return ingestMapParallel > 0 ? ingestMapParallel : fallback;
    }

    /** 对照 Go (*WikiConfig).IngestReduceParallelOrDefault */
    @JsonIgnore
    public int ingestReduceParallelOrDefault(int fallback) {
        return ingestReduceParallel > 0 ? ingestReduceParallel : fallback;
    }

    /** 对照 Go (*WikiConfig).IngestMaxInflightOrDefault */
    @JsonIgnore
    public int ingestMaxInflightOrDefault(int fallback) {
        return ingestMaxInflight > 0 ? ingestMaxInflight : fallback;
    }

    /** nil 安全的静态入口（Go 的 nil 接收者语义） */
    public static int ingestBatchSizeOrDefault(WikiConfig c, int fallback) {
        return c == null ? fallback : c.ingestBatchSizeOrDefault(fallback);
    }

    /** nil 安全的静态入口 */
    public static int ingestMapParallelOrDefault(WikiConfig c, int fallback) {
        return c == null ? fallback : c.ingestMapParallelOrDefault(fallback);
    }

    /** nil 安全的静态入口 */
    public static int ingestReduceParallelOrDefault(WikiConfig c, int fallback) {
        return c == null ? fallback : c.ingestReduceParallelOrDefault(fallback);
    }

    /** nil 安全的静态入口 */
    public static int ingestMaxInflightOrDefault(WikiConfig c, int fallback) {
        return c == null ? fallback : c.ingestMaxInflightOrDefault(fallback);
    }

    /** 对照 Go (*WikiConfig).IngestBatchSizeOrDefault 的 0 语义 + Granularity 归一化 */
    @JsonIgnore
    public String normalizedExtractionGranularity() {
        return WikiExtractionGranularity.normalize(extractionGranularity);
    }

    /** 对照 Go Value()：json.Marshal（写侧） */
    public String toJson() {
        try {
            return READER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("marshal wiki config failed", e);
        }
    }

    /** 对照 Go Scan(bytes)：json.Unmarshal，忽略未知字段；空输入返回 null（等价 Scan(nil) 不报错） */
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
