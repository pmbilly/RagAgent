package com.ragagent.wiki.service;

/**
 * 批次执行 / 分块引用 / 去重 / 目录规划四个文件里的常量
 * （对照 Go wiki_ingest_batch.go、wiki_ingest_cite.go、wiki_ingest_dedup.go、
 * wiki_ingest_taxonomy.go，以及 wiki_ingest.go L1894-1919 的 taxonomy 常量块）。
 *
 * <p><b>为什么单独一个常量类而不塞进 {@link WikiIngestConstants}</b>：上一轮已把
 * {@code wiki_ingest.go} L38-261 的常量冻结在 {@link WikiIngestConstants} 里，本轮的
 * 常量来自<b>另外四个文件</b>，单独成类既避免改动已冻结的文件，也让"某个数字出自哪个
 * Go 文件"在 import 上一眼可见。</p>
 */
public final class WikiBatchConstants {

    private WikiBatchConstants() {}

    // ═══════════════════════════════════════════════════════════════
    // wiki_ingest_cite.go（L19-30）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code maxRunesPerCitationBatch}（cite L25）：单个 chunk 引用批次的
     * 码点上限（token 数的快速近似）。小到足以让批次舒服地待在 LLM 的上下文与输出预算里，
     * 大到多数短/中文档一个批次就能分类完。
     */
    public static final int MAX_RUNES_PER_CITATION_BATCH = 12000;

    /**
     * 对照 Go {@code maxCitationBatchConcurrency}（cite L29）：跨批次并行的上限，
     * 免得一篇长文档把合成模型打满。
     */
    public static final int MAX_CITATION_BATCH_CONCURRENCY = 4;

    /** 对照 Go {@code modelcontext.NewHandleTable("c", 3, 0)}（cite L178）：{@code c000}、{@code c001}… */
    public static final String CHUNK_HANDLE_PREFIX = "c";

    /** 句柄编号的零填充宽度（对照 Go 的 {@code width=3}） */
    public static final int CHUNK_HANDLE_WIDTH = 3;

    // ═══════════════════════════════════════════════════════════════
    // wiki_ingest_dedup.go（L28-52）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code dedupCandidateTopK}（dedup L37）：每个新条目的相似度探测返回
     * 多少个 trigram 相似的既有页（对每个查询词 = name + 每个 alias 应用）。
     */
    public static final int DEDUP_CANDIDATE_TOP_K = 5;

    /**
     * 对照 Go {@code dedupCandidateScoreFloor}（dedup L44）：Jaccard 下限。
     * 达到或超过该值的配对<b>无条件</b>进入候选，不受 top-K 上限约束。
     * 调参目标：{@code "城镇登记失业人员"} vs {@code "中华优秀传统文化"}（Jaccard 0）
     * 被排除，而 {@code "Acme Corp"} vs {@code "Acme Corporation"}（≈0.5）稳稳通过。
     */
    public static final double DEDUP_CANDIDATE_SCORE_FLOOR = 0.08;

    /**
     * 对照 Go {@code dedupSmallCorpusBypass}（dedup L51）：既有页语料小到可以直接塞进
     * prompt 时<b>完全跳过</b>预筛。预筛只在大 KB 上划得来；小 KB 上它只会白砍合法匹配。
     */
    public static final int DEDUP_SMALL_CORPUS_BYPASS = 25;

    // ═══════════════════════════════════════════════════════════════
    // wiki_ingest.go L1894-1919（taxonomy 常量块）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code wikiTaxonomyPromptMaxPaths}（wiki_ingest.go L1897）：
     * 渲染进 prompt 的既存目录数上限。
     */
    public static final int TAXONOMY_PROMPT_MAX_PATHS = 150;

    /**
     * 对照 Go {@code wikiTaxonomyFolderPoolMax}（L1902）：从 DB 拉取的既存目录池上限。
     */
    public static final int TAXONOMY_FOLDER_POOL_MAX = 400;

    /**
     * 对照 Go {@code wikiTaxonomyFeedAllMaxFolders}（L1908）：目录数不超过该值时
     * 整个池子原样喂给 planner（完美复用召回、零 embedding 成本）。
     */
    public static final int TAXONOMY_FEED_ALL_MAX_FOLDERS = 60;

    /**
     * 对照 Go {@code wikiTaxonomyRelevantTopK}（L1912）：每个条目按相似度拉近的
     * 深层既有目录数。
     */
    public static final int TAXONOMY_RELEVANT_TOP_K = 3;

    /**
     * 对照 Go {@code wikiTaxonomyPlanChunkSize}（L1917）：单次规划调用最多带多少条目。
     */
    public static final int TAXONOMY_PLAN_CHUNK_SIZE = 60;

    /**
     * 对照 Go {@code wikiTaxonomyEmptyTreeHint}（L1919）：KB 还没有任何目录时
     * 塞进 {@code <existing_taxonomy>} 的占位提示。
     */
    public static final String TAXONOMY_EMPTY_TREE_HINT =
            "(none yet — this knowledge base has no folders, design a fresh directory)";

    // ═══════════════════════════════════════════════════════════════
    // 抽取指令的作用域标签（对照 Go 调用点传的裸字符串）
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code "InstructionScope": "wiki_extraction"}（batch L103、L1635） */
    public static final String INSTRUCTION_SCOPE_EXTRACTION = "wiki_extraction";

    /** 对照 Go {@code "InstructionScope": "wiki_content"}（batch L1334、L2062） */
    public static final String INSTRUCTION_SCOPE_CONTENT = "wiki_content";

    /** 对照 Go {@code "(none — this is a new document)"}（batch L92、L1627） */
    public static final String NO_PREVIOUS_SLUGS_HINT = "(none — this is a new document)";

    // ═══════════════════════════════════════════════════════════════
    // wiki_ingest_batch.go 内联字面量
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code resolveSlugUpdateLanguage} 之外用到的页面类型哨兵（batch L1798） */
    public static final String PAGE_TYPE_SUMMARY_LITERAL = "summary";

    /** 对照 Go {@code pageType == "" → "wiki page"}（batch L2042） */
    public static final String PAGE_TYPE_FALLBACK = "wiki page";

    /** 对照 Go {@code round(t) → time.Millisecond} 的日志取整 */
    public static final java.time.temporal.ChronoUnit LOG_ELAPSED_UNIT =
            java.time.temporal.ChronoUnit.MILLIS;
}
