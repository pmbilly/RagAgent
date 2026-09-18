package com.ragagent.memory.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 工作区级记忆开关，作为 JSONB 存在 {@code tenants} 上
 * （对照 Go {@code types.MemoryConfig}，internal/types/memory.go:333-381）。
 *
 * <h2>零值取舍（逐字段对照 json tag）</h2>
 * <p><b>本类型所有字段都没有 omitempty</b>，所以恒输出：字符串写 {@code ""}、
 * 计数写 {@code 0}、两个指针写 {@code null}。实测确认：</p>
 * <pre>
 *   MemoryConfig{} → {"enabled":false,"write_mode":"","extract_model_id":"","max_items":0,
 *     "extract_delay_seconds":0,"extract_min_interval_seconds":0,"extract_instructions":"",
 *     "interest_threshold":0,"embedding_model_id":"","vector_recall":null,
 *     "retrieval_conditioning":null}
 * </pre>
 *
 * <h2>⚠️ 两个 {@code *bool} 必须是可空 {@link Boolean}</h2>
 * <p>Go 用指针表达**三态**："没配"（null，走默认）与"显式配成 false"是两回事。
 * 直译成 {@code boolean} 会把 null 压成 false，等于替工作区管理员做了决定。
 * 同理它们**不加** {@code NON_NULL}——{@code null} 是要写出去的。</p>
 */
@JsonPropertyOrder({
        "enabled", "write_mode", "extract_model_id", "max_items",
        "extract_delay_seconds", "extract_min_interval_seconds", "extract_instructions",
        "interest_threshold", "embedding_model_id", "vector_recall", "retrieval_conditioning"
})
public class MemoryConfig {

    /**
     * 默认 **false**：记忆会跨会话保留用户说过的话，
     * 所以工作区管理员必须显式打开。
     */
    @JsonProperty("enabled")
    private boolean enabled;

    /** {@link #WRITE_MODE_EXPLICIT_ONLY} 或 {@link #WRITE_MODE_AUTO}。 */
    @JsonProperty("write_mode")
    private String writeMode = "";

    /**
     * 后台抽取任务用的模型。**空串表示"用对话本身用的那个模型"**——
     * 这正是设置界面承诺的行为，所以抽取任务绝不能仅因为这里是空就失败。
     */
    @JsonProperty("extract_model_id")
    private String extractModelId = "";

    /** 每个 subject 的活跃条目上限。0 表示 {@link #DEFAULT_MAX_ITEMS}。 */
    @JsonProperty("max_items")
    private int maxItems;

    /** 一轮结束后等多久才做蒸馏。0 表示默认值。 */
    @JsonProperty("extract_delay_seconds")
    private int extractDelaySeconds;

    /** 同一人两次蒸馏之间的最小间隔，纯粹用来约束成本。0 表示默认值。 */
    @JsonProperty("extract_min_interval_seconds")
    private int extractMinIntervalSeconds;

    /** 追加到蒸馏提示词的工作区专属规则。 */
    @JsonProperty("extract_instructions")
    private String extractInstructions = "";

    /** 一个话题要出现在几个不同会话里才算"兴趣"。0 表示默认值。 */
    @JsonProperty("interest_threshold")
    private int interestThreshold;

    /**
     * 给记忆打分用的**唯一**模型，按工作区钉死。
     *
     * <p>知识库各有各的 embedding 模型，随手抓一个会把不可比的向量空间混在一起。
     * 留空表示关闭语义召回、只用字面匹配。</p>
     */
    @JsonProperty("embedding_model_id")
    private String embeddingModelId = "";

    /**
     * 是否在召回里加入语义相似度。<b>三态</b>：{@code null} = 有可用的 embedding 模型时开启。
     */
    @JsonProperty("vector_recall")
    private Boolean vectorRecall;

    /**
     * 是否让记忆参与**检索**（查询改写、按文档排序），而不只是拼进回答的提示词。
     * <b>三态</b>，同 {@link #vectorRecall}。
     */
    @JsonProperty("retrieval_conditioning")
    private Boolean retrievalConditioning;

    // ── 常量（对照 Go internal/types/memory.go 的 const 块） ────────────────

    /** 显式写入模式：只有用户明确要求才记。 */
    public static final String WRITE_MODE_EXPLICIT_ONLY = "explicit_only";
    /** 自动写入模式。 */
    public static final String WRITE_MODE_AUTO = "auto";

    /** {@code interest_threshold} 的默认值。 */
    public static final int DEFAULT_MEMORY_INTEREST_THRESHOLD = 3;
    /** {@code interest_threshold} 的上限。 */
    public static final int MAX_MEMORY_INTEREST_THRESHOLD = 20;

    /**
     * 一个文档要在回答里出现几次才算"习惯"：一次引用是噪声，两次才是模式。
     * 改写器、重排器、记忆管理列表、Wiki 高亮共用这同一个下限。
     */
    public static final int MEMORY_DOC_AFFINITY_MIN_HITS = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }

    public String getWriteMode() { return writeMode; }
    public void setWriteMode(String v) { writeMode = v == null ? "" : v; }

    public String getExtractModelId() { return extractModelId; }
    public void setExtractModelId(String v) { extractModelId = v == null ? "" : v; }

    public int getMaxItems() { return maxItems; }
    public void setMaxItems(int v) { maxItems = v; }

    public int getExtractDelaySeconds() { return extractDelaySeconds; }
    public void setExtractDelaySeconds(int v) { extractDelaySeconds = v; }

    public int getExtractMinIntervalSeconds() { return extractMinIntervalSeconds; }
    public void setExtractMinIntervalSeconds(int v) { extractMinIntervalSeconds = v; }

    public String getExtractInstructions() { return extractInstructions; }
    public void setExtractInstructions(String v) { extractInstructions = v == null ? "" : v; }

    public int getInterestThreshold() { return interestThreshold; }
    public void setInterestThreshold(int v) { interestThreshold = v; }

    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { embeddingModelId = v == null ? "" : v; }

    public Boolean getVectorRecall() { return vectorRecall; }
    public void setVectorRecall(Boolean v) { vectorRecall = v; }

    public Boolean getRetrievalConditioning() { return retrievalConditioning; }
    public void setRetrievalConditioning(Boolean v) { retrievalConditioning = v; }
}
