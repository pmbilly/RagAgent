package com.ragagent.wiki.service;

/**
 * 批次执行 / 分块引用 / 去重 / 目录规划各子流程的常量。
 *
 * <p><b>为什么单独一个常量类而不塞进 {@link WikiIngestConstants}</b>：那边已冻结
 * wiki ingest 主流程的常量，本类承载各子流程的常量，职责分开、互不牵动。</p>
 */
public final class WikiBatchConstants {

    private WikiBatchConstants() {}

    // ═══════════════════════════════════════════════════════════════
    // chunk 引用批次
    // ═══════════════════════════════════════════════════════════════

    /**
     * 单个 chunk 引用批次的
     * 码点上限（token 数的快速近似）。小到足以让批次舒服地待在 LLM 的上下文与输出预算里，
     * 大到多数短/中文档一个批次就能分类完。
     */
    public static final int MAX_RUNES_PER_CITATION_BATCH = 12000;

    /**
     * 跨批次并行的上限，
     * 免得一篇长文档把合成模型打满。
     */
    public static final int MAX_CITATION_BATCH_CONCURRENCY = 4;

    /** chunk 句柄前缀：{@code c000}、{@code c001}… */
    public static final String CHUNK_HANDLE_PREFIX = "c";

    /** 句柄编号的零填充宽度 */
    public static final int CHUNK_HANDLE_WIDTH = 3;

    // ═══════════════════════════════════════════════════════════════
    // 去重预筛
    // ═══════════════════════════════════════════════════════════════

    /**
     * 每个新条目的相似度探测返回
     * 多少个 trigram 相似的既有页（对每个查询词 = name + 每个 alias 应用）。
     */
    public static final int DEDUP_CANDIDATE_TOP_K = 5;

    /**
     * Jaccard 下限。
     * 达到或超过该值的配对<b>无条件</b>进入候选，不受 top-K 上限约束。
     * 调参目标：{@code "城镇登记失业人员"} vs {@code "中华优秀传统文化"}（Jaccard 0）
     * 被排除，而 {@code "Acme Corp"} vs {@code "Acme Corporation"}（≈0.5）稳稳通过。
     */
    public static final double DEDUP_CANDIDATE_SCORE_FLOOR = 0.08;

    /**
     * 既有页语料小到可以直接塞进
     * prompt 时<b>完全跳过</b>预筛。预筛只在大 KB 上划得来；小 KB 上它只会白砍合法匹配。
     */
    public static final int DEDUP_SMALL_CORPUS_BYPASS = 25;

    // ═══════════════════════════════════════════════════════════════
    // 目录规划
    // ═══════════════════════════════════════════════════════════════

    /**
     * 渲染进 prompt 的既存目录数上限。
     */
    public static final int TAXONOMY_PROMPT_MAX_PATHS = 150;

    /**
     * 从 DB 拉取的既存目录池上限。
     */
    public static final int TAXONOMY_FOLDER_POOL_MAX = 400;

    /**
     * 目录数不超过该值时
     * 整个池子原样喂给 planner（完美复用召回、零 embedding 成本）。
     */
    public static final int TAXONOMY_FEED_ALL_MAX_FOLDERS = 60;

    /**
     * 每个条目按相似度拉近的
     * 深层既有目录数。
     */
    public static final int TAXONOMY_RELEVANT_TOP_K = 3;

    /**
     * 单次规划调用最多带多少条目。
     */
    public static final int TAXONOMY_PLAN_CHUNK_SIZE = 60;

    /**
     * KB 还没有任何目录时
     * 塞进 {@code <existing_taxonomy>} 的占位提示。
     */
    public static final String TAXONOMY_EMPTY_TREE_HINT =
            "(none yet — this knowledge base has no folders, design a fresh directory)";

    // ═══════════════════════════════════════════════════════════════
    // 抽取指令的作用域标签
    // ═══════════════════════════════════════════════════════════════

    /** 抽取指令的作用域标签值 */
    public static final String INSTRUCTION_SCOPE_EXTRACTION = "wiki_extraction";

    /** 内容指令的作用域标签值 */
    public static final String INSTRUCTION_SCOPE_CONTENT = "wiki_content";

    /** 无既有 slug 时给 prompt 的占位提示 */
    public static final String NO_PREVIOUS_SLUGS_HINT = "(none — this is a new document)";

    // ═══════════════════════════════════════════════════════════════
    // 批次执行内联字面量
    // ═══════════════════════════════════════════════════════════════

    /** 页面类型哨兵字面量（summary） */
    public static final String PAGE_TYPE_SUMMARY_LITERAL = "summary";

    /** 页面类型为空时的展示回落值 */
    public static final String PAGE_TYPE_FALLBACK = "wiki page";

    /** 耗时日志的时间取整单位 */
    public static final java.time.temporal.ChronoUnit LOG_ELAPSED_UNIT =
            java.time.temporal.ChronoUnit.MILLIS;
}
