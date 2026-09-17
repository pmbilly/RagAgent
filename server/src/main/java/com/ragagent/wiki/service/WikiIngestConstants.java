package com.ragagent.wiki.service;

import java.time.Duration;

/**
 * wiki ingest 的全部常量与可调参数（对照 Go internal/application/service/wiki_ingest.go
 * 的 const 块 L38-261）。
 *
 * <p>命名与数值<b>逐条对照</b>，括号里给出 Go 行号。这些数字不是拍脑袋的：注释里
 * 保留 Go 原文给出的推导理由（尤其是"必须大于 X，否则 Y 会被偷走"这类约束），
 * 改动前请先读完理由。</p>
 */
public final class WikiIngestConstants {

    private WikiIngestConstants() {}

    // ── 内容长度 ──

    /** 对照 Go {@code maxContentForWiki}（L40）：送进 LLM 生成 wiki 的文档正文上限。 */
    public static final int MAX_CONTENT_FOR_WIKI = 32768;

    // ── Phase 3：按 KB 的并发批次（standard / Redis 模式） ──

    /**
     * 对照 Go {@code wikiClaimStaleAfter}（L56）：被认领但未消费的 ingest 行，
     * 多久之后允许被另一个 worker 重新认领。
     *
     * <p><b>必须大于</b> wiki:ingest 的 asynq 任务超时（60 分钟），这样仍在运行的
     * 批次的行绝不会被中途偷走——只有真正崩溃/被遗弃的认领才会被回收。它保持了
     * 在引入认领之前的崩溃语义：死批次的未删除行会被下一次触发重新 peek 到。</p>
     */
    public static final Duration CLAIM_STALE_AFTER = Duration.ofMinutes(90);

    /** 对照 Go {@code wikiSlugLockPrefix}（L61） */
    public static final String SLUG_LOCK_PREFIX = "wiki:slug:";

    /** 对照 Go {@code wikiIdentityClaimPrefix}（L69） */
    public static final String IDENTITY_CLAIM_PREFIX = "wiki:identity:";

    /** 对照 Go {@code wikiIdentityClaimTTL}（L70） */
    public static final Duration IDENTITY_CLAIM_TTL = Duration.ofHours(2);

    /** 对照 Go {@code wikiSlugLockTTL}（L95）：单 slug 锁上限，防崩溃的 reducer 永久卡住热页。 */
    public static final Duration SLUG_LOCK_TTL = Duration.ofMinutes(5);

    /** 对照 Go {@code wikiSlugLockWait}（L99） */
    public static final Duration SLUG_LOCK_WAIT = Duration.ofMinutes(2);

    /** 对照 Go {@code wikiSlugLockPoll}（L100） */
    public static final Duration SLUG_LOCK_POLL = Duration.ofMillis(50);

    // ── Phase 4：按 KB 的在途上限（standard / Redis 模式） ──

    /** 对照 Go {@code wikiInflightPrefix}（L111） */
    public static final String INFLIGHT_PREFIX = "wiki:inflight:";

    /**
     * 对照 Go {@code wikiInflightDefault}（L115）：WikiConfig.IngestMaxInflight 未设时的兜底。
     * 4 给默认 8 worker 的 wiki 池留一半给其它 KB，同时仍给单个 KB 实打实的并行度。
     */
    public static final int INFLIGHT_DEFAULT = 4;

    /**
     * 对照 Go {@code wikiInflightTTL}（L119）：预留槽位不续期能活多久。
     * <b>必须</b>比 {@link #INFLIGHT_RENEW} 宽裕，否则一次续期丢失（GC 停顿、Redis 抖动）
     * 就会把活跃批次的槽位丢掉。
     */
    public static final Duration INFLIGHT_TTL = Duration.ofSeconds(90);

    /** 对照 Go {@code wikiInflightRenew}（L121） */
    public static final Duration INFLIGHT_RENEW = Duration.ofSeconds(30);

    /** 对照 Go {@code wikiInflightBackoff}（L124）：被上限挡回时的后续触发延迟。 */
    public static final Duration INFLIGHT_BACKOFF = Duration.ofSeconds(10);

    // ── 调度延迟 ──

    /** 对照 Go {@code wikiIngestDelay}（L128）：文档入库后等多久才触发批次——给连续上传做防抖。 */
    public static final Duration INGEST_DELAY = Duration.ofSeconds(30);

    /** 对照 Go {@code wikiFollowUpDelay}（L134）：后续批次排空剩余行前的常规轻量防抖。 */
    public static final Duration FOLLOW_UP_DELAY = Duration.ofSeconds(5);

    /**
     * 对照 Go {@code wikiRateLimitBackoff}（L143）：失败原因是被上游限流
     * （HTTP 429 / 配额）时用的<b>长得多的</b>后续延迟。按常规 5 秒节奏重试一个被限流的
     * 文档，只会往已经打满的 rpm 预算上再扔请求——每次重试都会重新发起
     * extract + classify + summary 三个调用——让限流器一直处于触发态，把整个 KB 的
     * 摄取拖长。退避给 per-minute 窗口时间重置。
     */
    public static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(60);

    // ── 批次大小与重试预算 ──

    /** 对照 Go {@code wikiMaxDocsPerBatch}（L148）：单批次最多处理多少文档。 */
    public static final int MAX_DOCS_PER_BATCH = 5;

    /**
     * 对照 Go {@code wikiMaxFailRetries}（L156）：单个文档 op 经
     * {@code requeueFailedOps} 最多被重试多少次，超过即永久归档到 task_dead_letters。
     * 5 次 ≈ 五个完整批次周期（每个约 30 秒延迟），给瞬时 LLM 错误公平的恢复机会，
     * 又不让持续损坏的文档无限堵塞队列。
     */
    public static final int MAX_FAIL_RETRIES = 5;

    /**
     * 对照 Go {@code wikiIngestMaxRetry}（L161）：wiki:ingest 任务的 asynq 重试预算。
     * 保持适中：锁冲突已经通过 {@code asynqRetryDelayFunc} 每 15 秒重试一次，
     * 且 follow-up / retract 路径触发很快。
     */
    public static final int INGEST_MAX_RETRY = 10;

    // ── 删除墓碑 ──

    /** 对照 Go {@code wikiDeletedKeyPrefix}（L169） */
    public static final String DELETED_KEY_PREFIX = "wiki:deleted:";

    /**
     * 对照 Go {@code wikiDeletedTTL}（L173）：记住一次删除多久。
     * 必须宽裕地超过最长可能的 ingest 运行时间（LLM 抽取 + reduce）。
     */
    public static final Duration DELETED_TTL = Duration.ofHours(1);

    // ── LLM 调用预算 ──

    /**
     * 对照 Go {@code wikiLLMMaxAttempts}（L179）：经 {@code generateWithTemplate}
     * 的每次 LLM 调用的总尝试次数（首次 + 重试）。3 足以吸收上游网关的瞬时
     * 504/超时，又不会在远端真挂时显著拖长任务。
     */
    public static final int LLM_MAX_ATTEMPTS = 3;

    /**
     * 对照 Go {@code wikiLLMMaxTokens}（L190）：经 {@code generateWithTemplate} 的
     * 每次 LLM 调用的补全 token 预算。
     *
     * <p>合并式 wiki 抽取会吐出<b>一整个大 JSON</b>（entities + concepts + details）。
     * MaxTokens 留 0 时，OpenAI 兼容客户端会省略 max_tokens，DeepSeek 这类供应商
     * 便套用 8192 默认值——长抽取会在 JSON 中途被截断，{@code finish_reason=length}，
     * 解析报 "unexpected end of JSON input"（EXTRACT_FAILED）。提到 32768 与
     * 大型中文政策文档实测完整的输出相符；短回复仍靠 {@code finish_reason=stop} 提前结束。
     * 见 #2604。</p>
     */
    public static final int LLM_MAX_TOKENS = 32768;

    /**
     * 对照 Go {@code wikiLLMBackoffBase}（L195）：重试之间指数退避的基数。
     * 第 n 次重试等 {@code base << (n-1)}——2 秒基数即 2s、4s、8s。
     */
    public static final Duration LLM_BACKOFF_BASE = Duration.ofSeconds(2);

    /** 对照 Go {@code wikiLLMMaxAttempts} 的退避序列：{@code base << (attempt-1)} */
    public static Duration llmBackoff(int attempt) {
        // attempt 从 1 起；Go 是 time.Duration 位移，Java 侧显式乘 2^(attempt-1)
        return LLM_BACKOFF_BASE.multipliedBy(1L << Math.max(0, attempt - 1));
    }

    // ── 任务标识 ──

    /** 对照 Go {@code wikiTaskType}（L200）：task_pending_ops / task_dead_letters 里的 task_type 戳记。 */
    public static final String TASK_TYPE = "wiki:ingest";

    /** 对照 Go {@code wikiTaskScope}（L204）= {@code types.TaskScopeKnowledgeBase}。 */
    public static final String TASK_SCOPE = "knowledge_base";

    // ── Finalize 通道（Phase 1：防抖的 KB 级收敛） ──

    /**
     * 对照 Go {@code wikiFinalizeTaskType}（L220）：finalize 工作在 task_pending_ops
     * 的独立通道。与 ingest 通道同为 (task_type, scope, scope_id) 键，但 task_type
     * 不同，PeekBatch 因此绝不会把两者混在一起。
     */
    public static final String FINALIZE_TASK_TYPE = "wiki:finalize";

    /** 对照 Go {@code wikiFinalizeOpSlug}（L223）：一行一个受影响页面 slug（本批次写过则带新 title）。 */
    public static final String FINALIZE_OP_SLUG = "slug";

    /** 对照 Go {@code wikiFinalizeOpChange}（L227）：一行一条文档级增/删变更（供索引导语的变更描述）。 */
    public static final String FINALIZE_OP_CHANGE = "change";

    /**
     * 对照 Go {@code wikiFinalizeOpFolderPrune}（L234）：文档撤回后可能变空的文件夹。
     * 放进持久化的 finalize 通道，是为了等这个 KB 的所有 ingest op 都落定再删目录
     * ——taxonomy 规划会在 reduce 写页面<b>之前</b>建目录，过早剪枝会让在途的
     * 目录分配失效。
     */
    public static final String FINALIZE_OP_FOLDER_PRUNE = "folder_prune";

    /** 对照 Go {@code wikiFinalizeAdded}（L236） */
    public static final String FINALIZE_ADDED = "added";

    /** 对照 Go {@code wikiFinalizeRemoved}（L237） */
    public static final String FINALIZE_REMOVED = "removed";

    /**
     * 对照 Go {@code wikiFinalizeDelay}（L241）：防抖 finalize 触发，让窗口内的多个
     * ingest 批次合并成一次索引重建。
     */
    public static final Duration FINALIZE_DELAY = Duration.ofSeconds(20);

    /** 对照 Go {@code wikiFinalizeMaxRows}（L245）：单次 finalize 最多排空多少行；触顶则自我重排处理余下部分。 */
    public static final int FINALIZE_MAX_ROWS = 5000;

    /**
     * 对照 Go {@code wikiFinalizeLockTTL} / {@code wikiFinalizeLockRenew}（L250-251）：
     * 防止同一 KB 的两个 finalize 运行并发写索引页（在 asynq.TaskID 合并之上再加一道保险）。
     */
    public static final Duration FINALIZE_LOCK_TTL = Duration.ofSeconds(60);

    /** 对照 Go {@code wikiFinalizeLockRenew}（L251） */
    public static final Duration FINALIZE_LOCK_RENEW = Duration.ofSeconds(20);

    /** 对照 Go {@code wikiFinalizeLockPrefix}（wiki_ingest_batch.go L935） */
    public static final String FINALIZE_LOCK_PREFIX = "wiki:finalize:active:";

    /**
     * 对照 Go {@code wikiFolderPruneRetryDelay}（L256）：目录清理是维护性工作，
     * 不是阻塞用户的工作。ingest 仍在进行时慢慢重试，避免剪枝和主 wiki 管线抢 worker。
     */
    public static final Duration FOLDER_PRUNE_RETRY_DELAY = Duration.ofMinutes(1);

    /**
     * 对照 Go {@code wikiIngestCleanupTimeout}（L260）：asynq 任务上下文被取消或
     * 超时之后，脱钩的收尾清理的时间上限。
     *
     * <p>见 {@link WikiIngestService#cleanupContext}——Java 侧的等价物是
     * "不受调用方中断影响的独立执行路径"。</p>
     */
    public static final Duration INGEST_CLEANUP_TIMEOUT = Duration.ofSeconds(10);

    // ── 任务操作 ──

    /** 对照 Go {@code WikiOpIngest}（L315） */
    public static final String OP_INGEST = "ingest";

    /** 对照 Go {@code WikiOpRetract}（L316） */
    public static final String OP_RETRACT = "retract";

    // ── 索引导语 ──

    /**
     * 对照 Go {@code indexIntroSummaryCap}（L2088）：从零生成 wiki 索引导语时，
     * 最多喂多少条摘要页。
     *
     * <p>4 万文档的 KB 否则每批都会撑爆上下文窗口；而导语是"定调"性质的产物，
     * 最近被触碰的文档本来就带更多信号。取最近更新的 top-N 摘要，并在 prompt 里
     * 加一句 "showing N of M" 提示，模型才能对自己的采样诚实。</p>
     */
    public static final int INDEX_INTRO_SUMMARY_CAP = 200;

    // ── 错误哨兵 ──

    /**
     * 对照 Go {@code ErrWikiIngestConcurrent}（L29-36）：Lite 模式下同一 KB 已有
     * 另一个批次在跑时返回。asynq 的 {@code RetryDelayFunc} 用 {@code errors.Is}
     * 认出这个哨兵，套用<b>短且固定</b>的重试延迟而不是指数退避，让被推迟的批次
     * 在活跃批次释放后尽快重试。standard（Redis）模式在 Phase 3 已不再取独占的
     * 按 KB 锁，因此永远不会返回它。
     */
    public static final String ERR_CONCURRENT_TASK_ACTIVE = "concurrent wiki task active";

    /**
     * 对照 Go 的 {@code error} 哨兵语义：Java 侧用这个专用异常类型让调用方
     * {@code catch} / {@code instanceof} 判定，等价于 Go 的 {@code errors.Is}。
     */
    public static final class ConcurrentTaskActiveException extends RuntimeException {
        public ConcurrentTaskActiveException() {
            super(ERR_CONCURRENT_TASK_ACTIVE);
        }
    }

    /** 对照 Go {@code WikiDeletedTombstoneKey}（L285-287）：{@code wiki:deleted:{kbID}:{knowledgeID}} */
    public static String deletedTombstoneKey(String kbId, String knowledgeId) {
        return DELETED_KEY_PREFIX + kbId + ":" + knowledgeId;
    }

    /** 对照 Go {@code wikiSlugLockPrefix + kbID + ":" + slug}（L916） */
    public static String slugLockKey(String kbId, String slug) {
        return SLUG_LOCK_PREFIX + kbId + ":" + slug;
    }

    /** 对照 Go {@code wikiInflightPrefix + kbID}（L973） */
    public static String inflightKey(String kbId) {
        return INFLIGHT_PREFIX + kbId;
    }

    /** 对照 Go {@code wikiIdentityClaimPrefix + kbID + ":" + pageType + ":" + identity}（dedup 侧） */
    public static String identityClaimKey(String kbId, String pageType, String identity) {
        return IDENTITY_CLAIM_PREFIX + kbId + ":" + pageType + ":" + identity;
    }

    /** 对照 Go {@code asynq.TaskID("wiki-finalize-" + kbID)}（L812） */
    public static String finalizeTaskId(String kbId) {
        return "wiki-finalize-" + kbId;
    }

    /** 对照 Go {@code asynq.TaskID("wiki-ingest-capped-" + kbID)}（L1027） */
    public static String cappedRetryTaskId(String kbId) {
        return "wiki-ingest-capped-" + kbId;
    }

    /** 对照 Go {@code asynq.TaskID("wiki-ingest-recheck-" + kbID)}（L1069） */
    public static String staleClaimRecheckTaskId(String kbId) {
        return "wiki-ingest-recheck-" + kbId;
    }
}
