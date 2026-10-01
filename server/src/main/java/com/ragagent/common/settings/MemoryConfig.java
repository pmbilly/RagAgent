package com.ragagent.common.settings;


/**
 * 工作区级记忆开关，作为 JSONB 存在 {@code tenants} 上
 * （对照 Go {@code types.MemoryConfig}（2026-09-30 由 memory/domain 下沉至此：它是 tenants 表的 jsonb 载荷，
 * auth 读写自己表的列时不该反向依赖 memory 域），internal/types/memory.go:333-381）。
 *
 * <h2>零值取舍</h2>
 * <p><b>本类型所有字段都恒输出</b>（契约 §1.6「禁止条件键」）：字符串写 {@code ""}、
 * 计数写 {@code 0}、两个指针写 {@code null}。键名＝Java 字段名（camelCase，§14.9k M2）：</p>
 * <pre>
 *   MemoryConfig{} → {"enabled":false,"writeMode":"","extractModelId":"","maxItems":0,
 *     "extractDelaySeconds":0,"extractMinIntervalSeconds":0,"extractInstructions":"",
 *     "interestThreshold":0,"embeddingModelId":"","vectorRecall":null,
 *     "retrievalConditioning":null}
 * </pre>
 *
 * <p>⚠️ <b>它是落库载荷</b>（{@code tenants.memory_config} jsonb，由 auth 的 KV 端点读写）：
 * 改过键名后的存量行必须跑迁移 SQL（HANDOFF §14.9k M2）。旧键读进来会被静默忽略
 * → 工作区配置"看起来被重置"，不会报错。</p>
 *
 * <h2>⚠️ 两个 {@code *bool} 必须是可空 {@link Boolean}</h2>
 * <p>Go 用指针表达**三态**："没配"（null，走默认）与"显式配成 false"是两回事。
 * 直译成 {@code boolean} 会把 null 压成 false，等于替工作区管理员做了决定。
 * 同理它们**不加** {@code NON_NULL}——{@code null} 是要写出去的。</p>
 */
public class MemoryConfig {

    /**
     * 默认 **false**：记忆会跨会话保留用户说过的话，
     * 所以工作区管理员必须显式打开。
     */
    private boolean enabled;

    /** {@link #WRITE_MODE_EXPLICIT_ONLY} 或 {@link #WRITE_MODE_AUTO}。 */
    private String writeMode = "";

    /**
     * 后台抽取任务用的模型。**空串表示"用对话本身用的那个模型"**——
     * 这正是设置界面承诺的行为，所以抽取任务绝不能仅因为这里是空就失败。
     */
    private String extractModelId = "";

    /** 每个 subject 的活跃条目上限。0 表示 {@link #DEFAULT_MAX_ITEMS}。 */
    private int maxItems;

    /** 一轮结束后等多久才做蒸馏。0 表示默认值。 */
    private int extractDelaySeconds;

    /** 同一人两次蒸馏之间的最小间隔，纯粹用来约束成本。0 表示默认值。 */
    private int extractMinIntervalSeconds;

    /** 追加到蒸馏提示词的工作区专属规则。 */
    private String extractInstructions = "";

    /** 一个话题要出现在几个不同会话里才算"兴趣"。0 表示默认值。 */
    private int interestThreshold;

    /**
     * 给记忆打分用的**唯一**模型，按工作区钉死。
     *
     * <p>知识库各有各的 embedding 模型，随手抓一个会把不可比的向量空间混在一起。
     * 留空表示关闭语义召回、只用字面匹配。</p>
     */
    private String embeddingModelId = "";

    /**
     * 是否在召回里加入语义相似度。<b>三态</b>：{@code null} = 有可用的 embedding 模型时开启。
     */
    private Boolean vectorRecall;

    /**
     * 是否让记忆参与**检索**（查询改写、按文档排序），而不只是拼进回答的提示词。
     * <b>三态</b>，同 {@link #vectorRecall}。
     */
    private Boolean retrievalConditioning;

    // ── 常量（对照 Go internal/types/memory.go 的 const 块） ────────────────

    /** 显式写入模式：只有用户明确要求才记。 */
    public static final String WRITE_MODE_EXPLICIT_ONLY = "explicit_only";
    /** 自动写入模式。 */
    public static final String WRITE_MODE_AUTO = "auto";

    /** {@code interestThreshold} 的默认值。 */
    public static final int DEFAULT_MEMORY_INTEREST_THRESHOLD = 3;
    /** {@code interestThreshold} 的上限。 */
    public static final int MAX_MEMORY_INTEREST_THRESHOLD = 20;

    /** {@code maxItems} 的默认值（等同于 {@link MemoryKinds#DEFAULT_MAX_ITEMS}）。 */
    public static final int DEFAULT_MAX_ITEMS = MemoryKinds.DEFAULT_MAX_ITEMS;
    /** {@code maxItems} 的上限；{@link #normalize()} 会把更大的值夹回来。 */
    public static final int MAX_ITEMS_CAP = 2000;
    /** {@code extractInstructions} 的 rune 上限。 */
    public static final int MAX_EXTRACT_INSTRUCTIONS_RUNES = MemoryKinds.MAX_EXTRACT_INSTRUCTIONS_RUNES;

    // ── 蒸馏计时器的边界（对照 Go L487-493） ──────────────────────────────
    //
    // 延迟的下界不是安全护栏而是成本护栏：接近零的延迟会把一串消息
    // 变成"每条消息一次模型调用"。

    /** {@code extractDelaySeconds} 的默认值。 */
    public static final int DEFAULT_EXTRACT_DELAY_SECONDS = 90;
    /** {@code extractDelaySeconds} 的下界。 */
    public static final int MIN_EXTRACT_DELAY_SECONDS = 5;
    /** {@code extractDelaySeconds} 的上界。 */
    public static final int MAX_EXTRACT_DELAY_SECONDS = 3600;
    /** {@code extractMinIntervalSeconds} 的默认值。 */
    public static final int DEFAULT_EXTRACT_MIN_INTERVAL_SECONDS = 300;
    /** {@code extract_min_interval_seconds} 的上界。 */
    public static final int MAX_EXTRACT_MIN_INTERVAL_SECONDS = 86400;

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

    // ── 业务方法（对照 Go internal/types/memory.go L394-541） ──────────────
    //
    // ⚠️ 这些方法名**刻意**都不带 get/is 前缀：一旦叫 `isVectorRecallEnabled()`，
    // Jackson 会多吐一个 `vector_recall_enabled` 键（§7.5 第 2 条，复发率最高的坑）。
    // 本类已经是响应体/落库 jsonb 的形状，多一个键就是契约偏差。

    /**
     * 对照 Go {@code VectorRecallEnabled}：召回是否可以用语义相似度。
     *
     * <p>{@code vectorRecall} 为 {@code null} 表示"有可用的 embedding 模型时就开"，
     * 所以这里回 true——真正的"有没有模型"判断在 service 层。</p>
     */
    public boolean vectorRecallEnabled() {
        if (!enabled) {
            return false;
        }
        return vectorRecall == null || vectorRecall;
    }

    /** 对照 Go {@code RetrievalConditioningEnabled}：{@code null} 表示开。 */
    public boolean retrievalConditioningEnabled() {
        if (!enabled) {
            return false;
        }
        return retrievalConditioning == null || retrievalConditioning;
    }

    /** 对照 Go {@code EffectiveInterestThreshold}：配置为 nil 或非正数时回默认值，超过上限时夹住。 */
    public int effectiveInterestThreshold() {
        if (interestThreshold <= 0) {
            return DEFAULT_MEMORY_INTEREST_THRESHOLD;
        }
        if (interestThreshold > MAX_MEMORY_INTEREST_THRESHOLD) {
            return MAX_MEMORY_INTEREST_THRESHOLD;
        }
        return interestThreshold;
    }

    /** 对照 Go {@code EffectiveMaxItems}：配置为 nil 或非正数时回默认值。 */
    public int effectiveMaxItems() {
        if (maxItems <= 0) {
            return DEFAULT_MAX_ITEMS;
        }
        return maxItems;
    }

    /** 对照 Go {@code AutoExtractEnabled}：nil 或未启用、或不是 auto 模式，都不抽。 */
    public boolean autoExtractEnabled() {
        return enabled && WRITE_MODE_AUTO.equals(writeMode);
    }

    /** 对照 Go {@code MemoryEnabled}：工作区开关是否打开。 */
    public boolean memoryEnabled() {
        return enabled;
    }

    /**
     * 对照 Go {@code ExtractDelay}：一轮结束后等多久才蒸馏（nil/非正数回默认 90s）。
     *
     * <p>返回 {@link java.time.Duration} 而不是毫秒数——调用方要拿它做时间运算。</p>
     */
    public java.time.Duration extractDelay() {
        if (extractDelaySeconds <= 0) {
            return java.time.Duration.ofSeconds(DEFAULT_EXTRACT_DELAY_SECONDS);
        }
        return java.time.Duration.ofSeconds(extractDelaySeconds);
    }

    /** 对照 Go {@code ExtractMinInterval}：同一人两次运行之间的下界。 */
    public java.time.Duration extractMinInterval() {
        if (extractMinIntervalSeconds <= 0) {
            return java.time.Duration.ofSeconds(DEFAULT_EXTRACT_MIN_INTERVAL_SECONDS);
        }
        return java.time.Duration.ofSeconds(extractMinIntervalSeconds);
    }

    /**
     * 对照 Go {@code Normalize}：套默认值，并把未知的 write mode 打回
     * {@link #WRITE_MODE_EXPLICIT_ONLY}。
     *
     * <p>逐字段照抄，包括三处容易漏的：{@code extract_model_id} 与
     * {@code embedding_model_id} 去空白、{@code extract_instructions} 去空白后按
     * **rune** 截断到 1000、以及 {@code max_items} 的上下界
     * （小于等于 0 → 200，大于 2000 → 2000）。</p>
     *
     * <p>Go 的接收者是 {@code *MemoryConfig} 且 nil 时直接返回；Java 侧没有
     * "nil 配置"这回事——调用方传 {@code null} 时**不要**调本方法，
     * 直接用那些 {@code effective*} / {@code *Enabled()} 的 nil 语义。</p>
     */
    public void normalize() {
        if (!WRITE_MODE_AUTO.equals(writeMode)) {
            writeMode = WRITE_MODE_EXPLICIT_ONLY;
        }
        extractModelId = trim(extractModelId);
        embeddingModelId = trim(embeddingModelId);
        if (maxItems <= 0) {
            maxItems = DEFAULT_MAX_ITEMS;
        }
        if (maxItems > MAX_ITEMS_CAP) {
            maxItems = MAX_ITEMS_CAP;
        }
        extractDelaySeconds = clampSeconds(
                extractDelaySeconds, DEFAULT_EXTRACT_DELAY_SECONDS,
                MIN_EXTRACT_DELAY_SECONDS, MAX_EXTRACT_DELAY_SECONDS);
        extractMinIntervalSeconds = clampSeconds(
                extractMinIntervalSeconds, DEFAULT_EXTRACT_MIN_INTERVAL_SECONDS,
                0, MAX_EXTRACT_MIN_INTERVAL_SECONDS);
        if (interestThreshold <= 0) {
            interestThreshold = DEFAULT_MEMORY_INTEREST_THRESHOLD;
        }
        if (interestThreshold > MAX_MEMORY_INTEREST_THRESHOLD) {
            interestThreshold = MAX_MEMORY_INTEREST_THRESHOLD;
        }
        extractInstructions = trim(extractInstructions);
        if (MemoryKeys.runeLength(extractInstructions) > MAX_EXTRACT_INSTRUCTIONS_RUNES) {
            extractInstructions = trim(
                    MemoryKeys.runeSlice(extractInstructions, MAX_EXTRACT_INSTRUCTIONS_RUNES));
        }
    }

    /** 对照 Go 的包级 {@code clampSeconds}（L495-506）。 */
    private static int clampSeconds(int value, int fallback, int minimum, int maximum) {
        int v = value;
        if (v <= 0) {
            v = fallback;
        }
        if (v < minimum) {
            v = minimum;
        }
        if (v > maximum) {
            v = maximum;
        }
        return v;
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }
}
