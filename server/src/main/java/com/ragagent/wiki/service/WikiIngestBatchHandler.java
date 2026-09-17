package com.ragagent.wiki.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.controller.WikiActivityAudit;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.domain.WikiConfig;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiExtractionGranularity;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
import com.ragagent.wiki.prompt.WikiPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * wiki 生成管线的批次执行体（对照 Go internal/application/service/wiki_ingest_batch.go，
 * 2154 行）：Map（逐文档抽取/摘要/引用）→ Reduce（逐 slug 落页）→ finalize
 * （索引导语重建 / 死链清理 / 交叉链接注入 / 空目录剪枝）。
 *
 * <h2>并发模型（Phase 3，照搬 Go 注释）</h2>
 * <ul>
 *   <li><b>Standard（分布式协调）模式</b>：<b>没有</b>按 KB 的独占锁。同一 KB 的多个
 *       批次可以同时运行，各自通过 {@code claimPendingList} 认领<b>互不相交</b>的行。
 *       这让一个 KB 的积压摊到整个 wiki worker 池上，而不是一次排空 5 篇。
 *       同 slug 的 reduce 安全由 {@code withSlugLock} 提供，而不是批次级大锁。</li>
 *   <li><b>Lite 模式</b>（无 Redis、单进程）：保留进程内的 {@code liteLocks} 守卫，
 *       同一 KB 同一时刻只有一个批次。Lite 面向小规模/本地——"每 KB 串行"最简单，
 *       而认领机制（需要 {@code FOR UPDATE SKIP LOCKED}）在这里买不到任何东西。</li>
 * </ul>
 * <p>Java 侧的判据是 {@link WikiIngestService#isLiteMode()}（见其注释）。</p>
 *
 * <h2>认领的崩溃安全网</h2>
 * <p>Standard 模式下若本批次异常退出（异常、超时、提前返回）而<b>尚未</b>结算它认领的
 * 行（trim + requeueFailedOps），必须释放认领，让下一次触发在几秒内就能重新认领，
 * 而不是干等 {@code CLAIM_STALE_AFTER}（90 分钟）。正常路径上 {@code claimsSettled}
 * 会翻成 true，使释放动作变成 no-op。Go 用脱钩的 cleanup ctx；
 * Java 用 {@link WikiCleanupScope}。</p>
 *
 * <h2>与 Go 的结构差异</h2>
 * <ol>
 *   <li>asynq 的 {@code RetryCount / MaxRetry} 在 Java 的任务对象里没有可读的
 *       "当前第几次尝试"，因此统计日志里不再输出 retry 字段。</li>
 *   <li>span 追踪未实现（约定文档 §9 阶段 4.0 差异 1）；
 *       {@link WikiBatchSupport.WikiSpans} 是 no-op 门面，调用点形状与 Go 一致。</li>
 *   <li>{@code langfuse.InjectTracing} 未实现（同上）。</li>
 * </ol>
 */
@Service
public class WikiIngestBatchHandler implements WikiIngestTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestBatchHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WikiIngestService ingestService;
    private final WikiPageService wikiService;
    private final TaskPendingOpsRepository pendingRepo;
    private final WikiIngestCitePipeline citePipeline;
    private final WikiIngestTaxonomy taxonomy;
    private final WikiIngestDedupService dedupService;
    private final WikiModelResolver modelResolver;
    private final WikiFinalizeLock finalizeLock;
    private final ChunkMapper chunkMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ObjectProvider<WikiActivityAudit> auditProvider;
    private final ObjectProvider<WikiIngestTaskQueue> taskQueueProvider;

    /** 对照 Go 的 {@code s.tracker()}：未实现追踪时是纯 no-op 门面 */
    private final WikiBatchSupport.WikiSpans spans = WikiBatchSupport.WikiSpans.NOOP;

    public WikiIngestBatchHandler(WikiIngestService ingestService,
                                  WikiPageService wikiService,
                                  TaskPendingOpsRepository pendingRepo,
                                  WikiIngestCitePipeline citePipeline,
                                  WikiIngestTaxonomy taxonomy,
                                  WikiIngestDedupService dedupService,
                                  WikiModelResolver modelResolver,
                                  WikiFinalizeLock finalizeLock,
                                  ChunkMapper chunkMapper,
                                  KnowledgeBaseMapper kbMapper,
                                  KnowledgeMapper knowledgeMapper,
                                  ObjectProvider<WikiActivityAudit> auditProvider,
                                  ObjectProvider<WikiIngestTaskQueue> taskQueueProvider) {
        this.ingestService = ingestService;
        this.wikiService = wikiService;
        this.pendingRepo = pendingRepo;
        this.citePipeline = citePipeline;
        this.taxonomy = taxonomy;
        this.dedupService = dedupService;
        this.modelResolver = modelResolver;
        this.finalizeLock = finalizeLock;
        this.chunkMapper = chunkMapper;
        this.kbMapper = kbMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.auditProvider = auditProvider;
        this.taskQueueProvider = taskQueueProvider;
    }

    // ═══════════════════════════════════════════════════════════════
    // 统计载体（对照 Go 的闭包捕获变量 + defer 统计日志）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code ProcessWikiIngest} 开头声明的一批局部计数变量。
     *
     * <p>Go 用闭包 + {@code defer} 让统计日志无论走哪条退出路径都能观测到它们；
     * Java 没有 defer，因此把这些值装进一个可变对象，由 {@code finally} 读取。</p>
     */
    static final class Stats {
        String exitStatus = "success";
        String mode = "standard";
        int pendingOps = 0;
        int ingestOps = 0;
        int retractOps = 0;
        int ingestSucceeded = 0;
        int ingestFailed = 0;
        int retractHandled = 0;
        boolean followUpScheduled = false;
        int totalPagesAffected = 0;
        final List<String> docPreview = new ArrayList<>(6);
        // 可调参数（从 KB.WikiConfig 解析）
        int batchSize = 0;
        int mapParallel = 0;
        int reduceParallel = 0;
        int maxInflight = 0;
    }

    // ═══════════════════════════════════════════════════════════════
    // KB / 知识 / 分块 的批量读取（对照 Go service 层调用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code kbService.GetKnowledgeBaseByIDOnly}（knowledgebase.go L314-330 →
     * repo L31-40）：按 id 取 KB，未找到返回 {@code null}。
     *
     * <p>GORM 的软删作用域自动加 {@code deleted_at IS NULL}，Java 侧显式写出来
     * （约定 §9："soft delete 不用 @TableLogic"）。Go 侧此处<b>不</b>按租户过滤
     * （repo 里只有 {@code Where("id = ?")}），Java 照抄。</p>
     */
    KnowledgeBase getKnowledgeBaseByIDOnly(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            return null;
        }
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .isNull(KnowledgeBase::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
     * 对照 Go {@code knowledgeSvc.GetKnowledgeByIDOnly}（knowledge.go L495-497 →
     * repo L79-88）：按 id 取知识文档，未找到返回 {@code null}。
     */
    Knowledge getKnowledgeByIDOnly(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return null;
        }
        return knowledgeMapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getId, knowledgeId)
                .isNull(Knowledge::getDeletedAt)
                .last("LIMIT 1"));
    }

    /**
     * 对照 Go {@code chunkRepo.ListChunksByKnowledgeID}（chunk.go L150-161）：
     * <b>只取 text 类型</b>、按 {@code chunk_index ASC}。
     *
     * <p>Go 的注释点名这个方法是"按设计只取 text"的；需要 summary / parent_text /
     * image 的调用方走 {@code ListChunksByKnowledgeIDAndTypes}。</p>
     */
    List<Chunk> listTextChunksByKnowledgeID(long tenantId, String knowledgeId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeId, knowledgeId)
                .eq(Chunk::getChunkType, WikiIngestService.CHUNK_TYPE_TEXT)
                .orderByAsc(Chunk::getChunkIndex));
    }

    /** 对照 Go {@code types.WikiConfig} 的 jsonb 反序列化（KB 行的 wiki_config 列） */
    static WikiConfig wikiConfigOf(KnowledgeBase kb) {
        if (kb == null || kb.getWikiConfig() == null || kb.getWikiConfig().isNull()) {
            return null;
        }
        JsonNode node = kb.getWikiConfig();
        return node.isTextual()
                ? WikiConfig.fromJson(node.asText())
                : WikiConfig.fromJson(node.toString());
    }

    // ═══════════════════════════════════════════════════════════════
    // 批次上下文（对照 Go newWikiBatchContext，batch L74-190）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code newWikiBatchContext}（batch L74-190）：构造本次运行使用的
     * <b>懒加载</b> fetcher。
     *
     * <p>这些取代了历史的"批次前 ListAllPages 全量转储"：不再一上来就把约 100MB 的行
     * 拉进内存（然后再遍历好几遍），调用方只为它<b>真正碰过</b>的 slug / knowledge id
     * 付费。缓存命中让单次运行内的重复查询免费。缓存是<b>逐次调用</b>的
     * （用锁保证并发安全），因此每个任务拿到一份全新、隔离的视图。</p>
     */
    public WikiBatchContext newWikiBatchContext(String kbId, WikiConfig wikiConfig) {
        final Object fetchMu = new Object();
        // slug -> title；"" 表示"确认查无此页"
        final Map<String, String> slugTitleCache = new LinkedHashMap<>();
        // kid -> content；"" 表示"确认查无摘要"
        final Map<String, String> summaryKidCache = new LinkedHashMap<>();

        java.util.function.Function<List<String>, Map<String, String>> resolveSlugs = slugs -> {
            // 先过滤掉已缓存的 slug
            List<String> need = new ArrayList<>();
            synchronized (fetchMu) {
                for (String slug : slugs) {
                    if (!slugTitleCache.containsKey(slug)) {
                        need.add(slug);
                    }
                }
            }

            if (!need.isEmpty()) {
                Map<String, WikiPageLite> pages = Map.of();
                try {
                    Map<String, WikiPageLite> fetched = wikiService.listBySlugs(kbId, need);
                    if (fetched != null) {
                        pages = fetched;
                    }
                } catch (Exception e) {
                    log.warn("wiki ingest: ListBySlugs({} slugs) failed: {}", need.size(), e.getMessage());
                }
                synchronized (fetchMu) {
                    for (String slug : need) {
                        WikiPageLite p = pages.get(slug);
                        if (p != null) {
                            if (WikiConstants.STATUS_ARCHIVED.equals(p.getStatus())
                                    || WikiConstants.PAGE_TYPE_INDEX.equals(p.getPageType())) {
                                // 归档 / 系统页在标题解析表里视为"不存在"：
                                // cleanDeadLinks 不该链到它们，也不该把它们当作交叉链接候选。
                                slugTitleCache.put(slug, "");
                                continue;
                            }
                            slugTitleCache.put(slug, p.getTitle());
                        } else {
                            slugTitleCache.put(slug, "");
                        }
                    }
                }
            }

            Map<String, String> out = new LinkedHashMap<>();
            synchronized (fetchMu) {
                for (String slug : slugs) {
                    String title = slugTitleCache.get(slug);
                    if (title != null && !title.isEmpty()) {
                        out.put(slug, title);
                    }
                }
            }
            return out;
        };

        java.util.function.Function<List<String>, Map<String, String>> resolveSummaries = kids -> {
            List<String> need = new ArrayList<>();
            synchronized (fetchMu) {
                for (String kid : kids) {
                    if (!summaryKidCache.containsKey(kid)) {
                        need.add(kid);
                    }
                }
            }

            if (!need.isEmpty()) {
                Map<String, String> contents = Map.of();
                try {
                    Map<String, String> fetched = wikiService.listSummariesByKnowledgeIDs(kbId, need);
                    if (fetched != null) {
                        contents = fetched;
                    }
                } catch (Exception e) {
                    log.warn("wiki ingest: ListSummariesByKnowledgeIDs({} kids) failed: {}",
                            need.size(), e.getMessage());
                }
                synchronized (fetchMu) {
                    for (String kid : need) {
                        String c = contents.get(kid);
                        summaryKidCache.put(kid, c == null ? "" : c);
                    }
                }
            }

            Map<String, String> out = new LinkedHashMap<>();
            synchronized (fetchMu) {
                for (String kid : kids) {
                    String content = summaryKidCache.get(kid);
                    if (content != null && !content.isEmpty()) {
                        out.put(kid, content);
                    }
                }
            }
            return out;
        };

        WikiBatchContext batchCtx = new WikiBatchContext();
        batchCtx.setSlugTitle(slug -> {
            Map<String, String> m = resolveSlugs.apply(List.of(slug));
            return m.getOrDefault(slug, "");
        });
        batchCtx.setSlugTitleMany(resolveSlugs);
        batchCtx.setSummaryContentByKnowledgeId(kid -> {
            Map<String, String> m = resolveSummaries.apply(List.of(kid));
            return m.getOrDefault(kid, "");
        });

        String granularity = WikiExtractionGranularity.STANDARD.value();
        String contentInstructions = "";
        String extractionInstructions = "";
        if (wikiConfig != null) {
            granularity = wikiConfig.normalizedExtractionGranularity();
            contentInstructions = wikiConfig.getContentInstructions();
            extractionInstructions = wikiConfig.getExtractionInstructions();
        }
        batchCtx.setExtractionGranularity(granularityFrom(granularity));
        batchCtx.setContentInstructions(contentInstructions);
        batchCtx.setExtractionInstructions(extractionInstructions);
        return batchCtx;
    }

    /**
     * 对照 Go {@code types.WikiExtractionGranularity} 的取值映射。
     *
     * <p>Java 侧 {@code WikiConfig} 的字段是 String（保留 Go 的 {@code ""} 零值），
     * 而 {@link WikiBatchContext} 的字段是枚举（消费方可以假定它是三个合法值之一，
     * 因为 {@code normalizedExtractionGranularity()} 已经归一化过）。
     * 这里做最后一次显式映射，而不是 {@code valueOf(大写)}——后者会让
     * "归一化漏了一处"表现为 {@code IllegalArgumentException} 而不是静默回落。</p>
     */
    static WikiExtractionGranularity granularityFrom(String normalized) {
        for (WikiExtractionGranularity g : WikiExtractionGranularity.values()) {
            if (g.value().equals(normalized)) {
                return g;
            }
        }
        return WikiExtractionGranularity.STANDARD;
    }

    // ═══════════════════════════════════════════════════════════════
    // 后续调度（对照 Go scheduleFollowUp，batch L41-65）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code scheduleFollowUp}（batch L41-65）：若该 KB 的
     * {@code task_pending_ops} 里还有待办 op，就再排一个触发任务。
     *
     * <p>Phase 3 之后这只是在"批次排空了自己的认领窗口、但还有行剩下、且没有别的触发
     * 在排队"（例如稳定的上传涓流）时兜底。standard 模式已经把 KB 的积压扇到并发的
     * 认领批次上，因此这个短延迟通常只是轻量防抖，而不是在等锁释放。</p>
     *
     * <p>与 Go 一致：<b>不</b>带 {@code asynq.TaskID}，因此重复的后续触发不会被合并
     * ——它们最终都会看到空通道并廉价退出。</p>
     *
     * @param delay 常规场景传 {@link WikiIngestConstants#FOLLOW_UP_DELAY}；
     *              批次撞上上游限流时传 {@link WikiIngestConstants#RATE_LIMIT_BACKOFF}
     * @return 是否排上了后续
     */
    public boolean scheduleFollowUp(WikiIngestPayload payload, Duration delay) {
        if (pendingRepo == null) {
            // 对照 Go L42-44：没有持久化队列就没有待办可查
            return false;
        }
        long count;
        try {
            count = pendingRepo.pendingCount(WikiIngestConstants.TASK_TYPE,
                    WikiIngestConstants.TASK_SCOPE, payload.knowledgeBaseId());
        } catch (Exception e) {
            return false;
        }
        if (count == 0) {
            return false;
        }

        log.info("wiki ingest: {} more documents pending for KB {}, scheduling follow-up in {}",
                count, payload.knowledgeBaseId(), delay);

        WikiIngestTaskQueue queue = taskQueueProvider.getIfAvailable();
        if (queue == null) {
            log.warn("wiki ingest: follow-up enqueue skipped (task queue not wired)");
            return false;
        }
        try {
            queue.enqueue(new WikiIngestTask(
                    WikiIngestTask.TYPE_WIKI_INGEST,
                    toJson(payload),
                    delay,
                    WikiIngestConstants.INGEST_MAX_RETRY,
                    Duration.ofMinutes(60), // 对照 asynq.Timeout(60*time.Minute)
                    ""));
        } catch (Exception e) {
            log.warn("wiki ingest: follow-up enqueue failed: {}", e.getMessage());
            return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // ProcessWikiIngest（对照 Go batch L192-909）
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void processWikiIngest(WikiIngestPayload payload) {
        long taskStartedAt = System.currentTimeMillis();
        Stats stats = new Stats();
        try {
            // 对照 Go 的 context.WithValue(ctx, types.TenantIDContextKey, payload.TenantID)：
            // 批次跑在队列线程上，没有 HTTP Filter 链填过 TenantContext，而模型解析
            // （ModelService.getModelByID 按租户可见性过滤）需要它。
            try (WikiBatchSupport.TenantScope ignored =
                         WikiBatchSupport.enterTenantScope(payload.tenantId())) {
                runIngest(payload, stats);
            }
        } finally {
            // 对照 Go 的 defer 统计日志（batch L219-244）
            log.info("wiki ingest stats: kb={} status={} elapsed={}ms mode={} ops(pending={},ingest={},"
                            + "retract={}) ingest(success={},failed={}) retract_handled={} pages(total={}) "
                            + "followup={} tunables(batch={},map_par={},reduce_par={},max_inflight={}) preview={}",
                    payload == null ? "" : payload.knowledgeBaseId(),
                    stats.exitStatus,
                    System.currentTimeMillis() - taskStartedAt,
                    stats.mode,
                    stats.pendingOps, stats.ingestOps, stats.retractOps,
                    stats.ingestSucceeded, stats.ingestFailed, stats.retractHandled,
                    stats.totalPagesAffected, stats.followUpScheduled,
                    stats.batchSize, stats.mapParallel, stats.reduceParallel, stats.maxInflight,
                    WikiTextUtils.previewStringSlice(stats.docPreview, 6));
        }
    }

    /**
     * 对照 Go {@code ProcessWikiIngest} L257-279：Lite 模式的按 KB 独占。
     *
     * <p>Standard 模式在 Phase 3 已不再取任何按 KB 的独占锁。</p>
     */
    private void runIngest(WikiIngestPayload payload, Stats stats) {
        if (ingestService.isLiteMode()) {
            stats.mode = "lite";
            if (!ingestService.tryAcquireLiteLock(payload.knowledgeBaseId())) {
                stats.exitStatus = "active_lock_conflict";
                log.info("wiki ingest: another batch active for KB {} (lite lock), deferring to retry",
                        payload.knowledgeBaseId());
                throw new WikiIngestConstants.ConcurrentTaskActiveException();
            }
            try {
                runIngestBody(payload, stats);
            } finally {
                ingestService.releaseLiteLock(payload.knowledgeBaseId());
            }
            return;
        }
        runIngestBody(payload, stats);
    }

    /** 对照 Go L281-377：KB 校验、模型解析、可调参数、在途上限、认领 */
    private void runIngestBody(WikiIngestPayload payload, Stats stats) {
        String kbId = payload.knowledgeBaseId();

        KnowledgeBase kb = getKnowledgeBaseByIDOnly(kbId);
        if (kb == null) {
            stats.exitStatus = "kb_deleted";
            ingestService.clearDeletedKnowledgeBasePendingOps(kbId);
            return;
        }
        if (!kb.getIndexingStrategy().isWikiEnabled()) {
            stats.exitStatus = "kb_not_wiki_enabled";
            throw new IllegalStateException("wiki ingest: KB " + kb.getId() + " is not wiki type");
        }

        WikiConfig wikiConfig = wikiConfigOf(kb);
        String synthesisModelId = wikiConfig == null ? "" : nullToEmpty(wikiConfig.getSynthesisModelId());
        if (synthesisModelId.isEmpty()) {
            synthesisModelId = nullToEmpty(kb.getSummaryModelId());
        }
        if (synthesisModelId.isEmpty()) {
            stats.exitStatus = "missing_synthesis_model";
            throw new IllegalStateException(
                    "wiki ingest: no synthesis model configured for KB " + kb.getId());
        }
        LlmChatClient chatModel;
        try {
            chatModel = modelResolver.getChatModel(synthesisModelId);
        } catch (RuntimeException e) {
            stats.exitStatus = "get_chat_model_failed";
            throw new IllegalStateException("wiki ingest: get chat model: " + e.getMessage(), e);
        }

        // 每 KB 的可调参数只解析一次。零值回落到历史默认，让既有 KB 在显式选择加入之前
        // 行为不变。
        stats.batchSize = WikiConfig.ingestBatchSizeOrDefault(
                wikiConfig, WikiIngestConstants.MAX_DOCS_PER_BATCH);
        stats.mapParallel = WikiConfig.ingestMapParallelOrDefault(wikiConfig, 10);
        stats.reduceParallel = WikiConfig.ingestReduceParallelOrDefault(wikiConfig, 10);

        // 每 KB 的在途上限（Phase 4，standard 模式）：别让一个 KB 的批量导入独占总池。
        // 若该 KB 已经到顶，就排一个合并的重试并<b>不认领任何行</b>地退出，
        // 让这些行留给先腾出槽位的那个运行中批次。
        stats.maxInflight = WikiConfig.ingestMaxInflightOrDefault(
                wikiConfig, WikiIngestConstants.INFLIGHT_DEFAULT);
        WikiInflightLimiter.Reservation reservation =
                ingestService.reserveInflightSlot(kbId, stats.maxInflight);
        if (!reservation.granted()) {
            stats.exitStatus = "inflight_cap";
            log.info("wiki ingest: KB {} at in-flight cap ({}), rescheduling",
                    kbId, stats.maxInflight);
            ingestService.scheduleCappedRetry(payload);
            return;
        }
        try {
            runIngestClaimed(payload, kb, wikiConfig, chatModel, stats);
        } finally {
            reservation.releaseQuietly();
        }
    }

    /** 对照 Go L347-404：认领 + 崩溃安全网 */
    private void runIngestClaimed(WikiIngestPayload payload,
                                  KnowledgeBase kb,
                                  WikiConfig wikiConfig,
                                  LlmChatClient chatModel,
                                  Stats stats) {
        String kbId = payload.knowledgeBaseId();

        // standard 模式认领行（盖 claimed_at，并发批次之间互不相交）；
        // Lite 模式在进程内锁下窥视。
        boolean standardMode = !ingestService.isLiteMode();
        WikiIngestService.PendingBatch claimed = standardMode
                ? ingestService.claimPendingList(kbId, stats.batchSize)
                : ingestService.peekPendingList(kbId, stats.batchSize);
        List<WikiPendingOp> pendingOps = claimed.ops();
        List<Long> peekedIds = claimed.peekedIds();

        stats.pendingOps = pendingOps.size();
        if (pendingOps.isEmpty()) {
            stats.exitStatus = "no_pending_ops";
            log.info("wiki ingest: no pending operations for KB {}", kbId);
            // 什么都没认领到，但可能仍有行被<b>新鲜</b>认领持有（并发批次还在跑，或者
            // 中途崩溃留下 claimed_at 戳）。这个 no-op 返回不会链后续，所以没有安全网时
            // 崩溃批次的行会一直无法认领直到 CLAIM_STALE_AFTER，而且此后也永远不会被
            // 重新触发——把 KB 无限期搁浅。因此布一张跨过陈旧阈值的合并重检网，
            // 让那些行一旦合格就被自动回收。
            stats.followUpScheduled = ingestService.scheduleStaleClaimRecheck(payload);
            return;
        }

        log.info("wiki ingest: batch processing {} ops for KB {}", pendingOps.size(), kbId);

        // 崩溃/中止安全网（仅 standard/认领模式）。Lite 模式只窥视不认领，
        // 因此没有任何东西需要释放。
        boolean[] claimsSettled = { false };
        if (standardMode && !peekedIds.isEmpty()) {
            try {
                runIngestPhases(payload, kb, wikiConfig, chatModel, pendingOps, peekedIds, stats);
                claimsSettled[0] = true;
            } finally {
                if (!claimsSettled[0]) {
                    // 用有界的<b>脱钩</b>清理路径：ctx 可能已因超时被取消。
                    try (WikiCleanupScope scope = ingestService.cleanupScope()) {
                        scope.run(() -> pendingRepo.releaseByIds(peekedIds));
                        log.warn("wiki ingest: released {} claimed rows on abnormal exit for KB {} "
                                + "(re-claimable immediately)", peekedIds.size(), kbId);
                    } catch (Exception e) {
                        log.warn("wiki ingest: failed to release {} claims on abnormal exit for KB {}: {}",
                                peekedIds.size(), kbId, e.getMessage());
                    }
                }
            }
            return;
        }
        runIngestPhases(payload, kb, wikiConfig, chatModel, pendingOps, peekedIds, stats);
    }

    /** 对照 Go L406-908：Map → 目录规划 → Reduce → 收尾结算 */
    private void runIngestPhases(WikiIngestPayload payload,
                                 KnowledgeBase kb,
                                 WikiConfig wikiConfig,
                                 LlmChatClient chatModel,
                                 List<WikiPendingOp> pendingOps,
                                 List<Long> peekedIds,
                                 Stats stats) {
        String kbId = payload.knowledgeBaseId();
        String lang = WikiLanguageSupport.languageNameFromContext();

        WikiBatchContext batchCtx = newWikiBatchContext(kbId, wikiConfig);

        // ── 1. MAP 阶段（并行抽取与生成更新） ──
        final Object mapMu = new Object();
        List<WikiPendingOp> failedOps = new ArrayList<>();
        // 注意：这是 map 阶段累积的<b>原始</b> map，remap 会产出新 map
        // （Java 的 lambda 要求被捕获的局部变量 effectively final，因此这里不能原地重赋值）
        final Map<String, List<SlugUpdate>> slugUpdates = new LinkedHashMap<>();
        List<DocIngestResult> docResults = new ArrayList<>();
        List<String> retractFolderIDs = new ArrayList<>();
        // rateLimited 在任何 map/reduce 的 LLM 失败看起来像上游 429/配额触发时翻真。
        // 它把后续调度器掰到更长的 RATE_LIMIT_BACKOFF 上，免得重试继续捶打已经打满的
        // rpm 预算。
        AtomicBoolean rateLimited = new AtomicBoolean(false);

        List<Runnable> mapBodies = new ArrayList<>(pendingOps.size());
        for (WikiPendingOp op : pendingOps) {
            mapBodies.add(() -> {
                if (WikiIngestConstants.OP_RETRACT.equals(op.getOp())) {
                    mapRetractOp(payload, op, mapMu, slugUpdates, retractFolderIDs, stats);
                    return;
                }

                synchronized (mapMu) {
                    stats.ingestOps++;
                }

                log.info("wiki ingest: processing document '{}' ({})",
                        op.getDocTitle(), op.getKnowledgeId());

                DocIngestResult result = null;
                List<SlugUpdate> updates = null;
                RuntimeException failure = null;
                try {
                    MapResult mr = mapOneDocument(chatModel, payload, op, batchCtx);
                    result = mr.result();
                    updates = mr.updates();
                } catch (RuntimeException e) {
                    failure = e;
                }

                if (failure != null) {
                    synchronized (mapMu) {
                        stats.ingestFailed++;
                        failedOps.add(op);
                        if (WikiBatchSupport.isLikelyRateLimitError(failure)) {
                            rateLimited.set(true);
                        }
                    }
                    log.warn("wiki ingest: failed to map knowledge {}: {}",
                            op.getKnowledgeId(), failure.getMessage());
                    return; // 不让整个批次失败
                }

                if (result != null) {
                    synchronized (mapMu) {
                        stats.ingestSucceeded++;
                        docResults.add(result);
                        stats.docPreview.add(
                                "ingest[" + WikiTextUtils.previewText(result.getKnowledgeId(), 24)
                                        + "]: title=" + WikiTextUtils.previewText(result.getDocTitle(), 40)
                                        + " summary=" + WikiTextUtils.previewText(result.getSummary(), 64));
                        if (updates != null) {
                            for (SlugUpdate u : updates) {
                                slugUpdates.computeIfAbsent(u.getSlug(), k -> new ArrayList<>()).add(u);
                            }
                        }
                    }
                    // 无需重置失败计数：成功的 op 会进 peekedIDs，并在 trim 时从
                    // task_pending_ops DELETE 掉，因此没有陈旧的 fail_count 列要清理。
                    //
                    // finalizing 槽位在 reduce + publish 之后的 docResults 循环里才排空，
                    // 因此 "completed" 只在 wiki 完整写出之后才到达。
                } else {
                    // err == nil && result == nil：mapOneDocument 在某个终态、不可重试的
                    // 状态（知识已删 / 无 chunk / 文本不足）跳过了该文档。它既不产出
                    // docResult 也不是 failedOp，因此成功与死信两条排空路径都不会触发。
                    // 在这里释放 finalizing 槽位，免得该行一直挂在 "finalizing" 直到
                    // housekeeping 扫描把它标成失败。对应的 +1 由
                    // KnowledgePostProcess.SetFinalizing 播种。
                    ingestService.finalizeWikiSubtask(op.getKnowledgeId());
                }
            });
        }
        WikiBatchSupport.fanOut(stats.mapParallel, mapBodies);

        // 每个 map worker 都选完 slug 之后重读身份认领，让同一标题的并发罗马化在目录规划
        // 与 Reduce 按 slug 加锁之前收敛。
        Map<String, List<SlugUpdate>> remappedSlugUpdates =
                dedupService.remapSlugUpdatesByIdentity(kbId, slugUpdates, batchCtx);

        // 在 reduce 之前为整批规划一次目录。Reduce 并行写页，自己无法在共享目录上收敛；
        // 这一遍给每个新的 entity/concept slug 分配一个连贯的 category_path，并复用既有
        // 目录。Reduce 随后只把计划应用到<b>尚未归档</b>的页面上（用户策展过的页面永远
        // 不被搅动）。
        batchCtx.setPlannedFolderId(taxonomy.resolvePlannedFolders(kb,
                taxonomy.planBatchTaxonomy(chatModel, kb, remappedSlugUpdates, lang, ingestService)));

        // ── 2. REDUCE 阶段（按 Slug 并行 upsert） ──
        final Object reduceMu = new Object();
        List<String> allPagesAffected = new ArrayList<>();
        // failedAdditionSlugs 收集"页面生成 LLM 调用失败（因此页面从未写出）"的
        // entity/concept slug。reduce 之后的清理步骤用它把同一批次摘要页里指向它们的死
        // [[slug]] 引用剥掉，并在 finalize 处理中排除失败的页面。
        Set<String> failedAdditionSlugs = new LinkedHashSet<>();
        // unappliedSlugKIDs 收集"其更新从未落地"的 slug 所对应的 knowledge_id——要么是
        // 没能拿到 per-slug 锁，要么是 reduce 返回了错误。两种情况下页面都保持原有内容，
        // 因此拥有该文档的一方必须被<b>重新排队</b>而不是被 trim 掉——否则行被删除、
        // 贡献永久静默丢失（finalize 只重建索引/交叉链接，不会重跑 reduce）。
        Set<String> unappliedSlugKIDs = new LinkedHashSet<>();

        List<Runnable> reduceBodies = new ArrayList<>(remappedSlugUpdates.size());
        for (Map.Entry<String, List<SlugUpdate>> entry : remappedSlugUpdates.entrySet()) {
            final String slug = entry.getKey();
            final List<SlugUpdate> updates = entry.getValue();
            reduceBodies.add(() -> {
                Reduced[] outcome = { Reduced.NONE };
                boolean acquired;
                try {
                    acquired = ingestService.withSlugLock(kbId, slug, () -> {
                        ReduceOutcome r = reduceSlugUpdates(chatModel, kbId, slug, updates,
                                payload.tenantId(), batchCtx);
                        outcome[0] = new Reduced(r);
                        if (r.error() != null) {
                            throw r.error();
                        }
                    });
                } catch (RuntimeException lockErr) {
                    // 作用域被取消（批次超时 / 关闭）——安静停下
                    collectUnapplied(reduceMu, unappliedSlugKIDs, updates);
                    return;
                }
                if (!acquired) {
                    // 竞争过久拿不到的 slug。页面保持原有内容，因此喂给它的文档
                    // <b>没有</b>完成：记下它们的 knowledge_id，让 trim 阶段把它们重新
                    // 排队（走 failed-op 重试预算）到稍后一个更空闲的批次，
                    // 而不是删掉它们的行。
                    log.warn("wiki ingest: slug {} busy > {}, deferring update",
                            slug, WikiIngestConstants.SLUG_LOCK_WAIT);
                    collectUnapplied(reduceMu, unappliedSlugKIDs, updates);
                    return;
                }
                ReduceOutcome r = outcome[0].value();
                if (r.error() != null) {
                    log.warn("wiki ingest: reduce failed for slug {}: {}", slug, r.error().getMessage());
                    collectUnapplied(reduceMu, unappliedSlugKIDs, updates);
                    if (WikiBatchSupport.isLikelyRateLimitError(r.error())) {
                        synchronized (reduceMu) {
                            rateLimited.set(true);
                        }
                    }
                }
                if (r.changed()) {
                    synchronized (reduceMu) {
                        allPagesAffected.add(slug);
                    }
                }
                if (r.additionFailed()) {
                    synchronized (reduceMu) {
                        failedAdditionSlugs.add(slug);
                    }
                }
            });
        }
        WikiBatchSupport.fanOut(stats.reduceParallel, reduceBodies);

        // 索引重建之前，先净化本批次产出的文档摘要页。摘要 LLM 在 map 阶段可以自由地注入
        // 它看到的每个 slug 的 [[entity/foo|name]] 链接，但 reduce 可能没能把其中一些 slug
        // 物化成真实页面。把这些死链改写成纯文本，让摘要不再含无法解析的引用。
        if (!failedAdditionSlugs.isEmpty() && !docResults.isEmpty()) {
            ingestService.sanitizeDeadSummaryLinks(kbId, docResults, failedAdditionSlugs, batchCtx);
        }

        stats.totalPagesAffected = allPagesAffected.size();

        // 把一份有界的摘要投影进 KB 活动流。逐文档的详版 Wiki 日志行与那个信息流重复、
        // 且从未被检索消费，因此现在直接写活动记录。
        Map<String, Integer> wikiActivityActions = new LinkedHashMap<>();
        for (WikiPendingOp op : pendingOps) {
            if (WikiIngestConstants.OP_RETRACT.equals(op.getOp())) {
                wikiActivityActions.merge("retract", 1, Integer::sum);
            }
        }
        for (DocIngestResult r : docResults) {
            if (r != null) {
                wikiActivityActions.merge("ingest", 1, Integer::sum);
            }
        }
        WikiActivityAudit audit = auditProvider.getIfAvailable();
        if (audit != null) {
            try (WikiCleanupScope scope = ingestService.cleanupScope()) {
                scope.run(() -> audit.wikiContentChanged(payload.tenantId(), kbId, wikiActivityActions));
            } catch (Exception e) {
                log.warn("wiki ingest: record content activity failed: {}", e.getMessage());
            }
        }

        // 立即发布刚生成的页面（<b>不</b>推迟到 finalize）：用户应当在文档内容写出的第一
        // 时间看到它的 wiki 页面，而不是等到防抖窗口之后。这是一次便宜的状态翻转。
        if (!allPagesAffected.isEmpty()) {
            log.info("wiki ingest: publishing draft pages");
            ingestService.publishDraftPages(kbId, allPagesAffected);
        }

        // 把 KB 级收敛（索引导语重建 + 死链清理 + 交叉链接注入）推迟到一个防抖的 per-KB
        // wiki:finalize 任务，而不是在每个 5 文档批次的尾巴上跑一遍。我们把变更记进
        // finalize 通道并排一个合并触发；N 篇文档的突发因此只重建索引<b>一次</b>。
        //
        // freshTitleBySlug 携带本批次成功写出的 (slug → title) 对（减去 reduce 阶段的
        // 失败项），供 finalize 的交叉链接阶段 linkify 对新页面的提及。
        Map<String, String> freshTitleBySlug = new LinkedHashMap<>();
        for (DocIngestResult dr : docResults) {
            if (dr == null) {
                continue;
            }
            for (DocIngestResult.PageRef p : dr.getPages()) {
                if (p.slug().isEmpty() || p.title().isEmpty()) {
                    continue;
                }
                if (failedAdditionSlugs.contains(p.slug())) {
                    continue;
                }
                freshTitleBySlug.put(p.slug(), p.title());
            }
        }
        if (!allPagesAffected.isEmpty() || !docResults.isEmpty()
                || stats.retractHandled > 0 || !retractFolderIDs.isEmpty()) {
            List<WikiFinalizeChange> changes = new ArrayList<>();
            for (DocIngestResult r : docResults) {
                changes.add(WikiFinalizeChange.added(r.getDocTitle(), r.getSummary()));
            }
            for (WikiPendingOp op : pendingOps) {
                if (WikiIngestConstants.OP_RETRACT.equals(op.getOp())) {
                    changes.add(WikiFinalizeChange.removed(op.getDocTitle(), op.getDocSummary()));
                }
            }
            ingestService.enqueueFinalize(payload, allPagesAffected, freshTitleBySlug,
                    changes, retractFolderIDs);
        }

        // 为每篇成功映射的文档关闭 postprocess.wiki span。span 时长现在覆盖
        // map + reduce + 索引重建 + 清理 + 交叉链接注入 + 发布，与用户心目中
        // "这篇知识的 wiki 处理"的墙钟窗口一致。
        // 逐文档的页面写出结果汇总在 output 里，让 trace 视图能显示该文档抽取的页面里
        // 有多少真的落地（vs. 因 reduce 阶段生成失败而被丢弃）。
        int failedAdditionSlugCount = failedAdditionSlugs.size();
        for (DocIngestResult r : docResults) {
            if (r == null) {
                continue;
            }
            // 成功映射的文档对它的 wiki op 而言已是终态，因此释放该知识在
            // pending_subtasks_count 里的槽位（计数器归零时行晋升为 completed）。
            // 放在下面的 WikiSpan 空值检查之前，让"根本没机会挂 span"的文档也能排空槽位。
            // 对应的 +1 由 KnowledgePostProcess.SetFinalizing 播种。
            //
            // <b>例外</b>：带有未落地 slug（锁竞争或 reduce 报错）的文档——它们会在下面
            // 被重新排队，因此保持 finalizing 槽位不放；重试（或 requeueFailedOps 里的
            // 死信排空）会在 op 真正到达终态时释放它。
            if (!unappliedSlugKIDs.contains(r.getKnowledgeId())) {
                ingestService.finalizeWikiSubtask(r.getKnowledgeId());
            }
            if (r.getWikiSpan() == null) {
                continue;
            }
            List<Map<String, String>> writtenPages = new ArrayList<>(r.getPages().size());
            List<Map<String, String>> droppedPages = new ArrayList<>();
            for (DocIngestResult.PageRef p : r.getPages()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("slug", p.slug());
                entry.put("title", WikiTextUtils.previewText(p.title(), 80));
                if (failedAdditionSlugs.contains(p.slug())) {
                    droppedPages.add(entry);
                    continue;
                }
                writtenPages.add(entry);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("pages_written", writtenPages.size());
            output.put("pages_dropped", droppedPages.size());
            output.put("pages_total", r.getPages().size());
            output.put("failed_slug_writes", failedAdditionSlugCount);
            output.put("pages_written_preview", writtenPages);
            if (!droppedPages.isEmpty()) {
                output.put("pages_dropped_preview", droppedPages);
            }
            if (r.getMapStats() != null) {
                output.putAll(r.getMapStats());
            }
            spans.endSpan(r.getWikiSpan(), output);
        }
        // 失败映射的文档在 mapOneDocument 内部已经调过 FailSpan
        // （failedOps 路径在到达 docResults 之前就返回了）。这里无需额外处理。

        // 把"带未落地 slug"的文档折进 failedOps，让它们既不被 trim 也不被晋升为
        // completed：requeueFailedOps 随后用与 map 阶段失败<b>完全相同</b>的 fail_count
        // 预算处理它们（现在重试，slug 长期热/坏则进死信）。
        // 已经计为 map 失败的文档跳过，避免 fail_count 被加两次。
        if (!unappliedSlugKIDs.isEmpty()) {
            Set<String> failedKIDs = new LinkedHashSet<>();
            for (WikiPendingOp op : failedOps) {
                failedKIDs.add(op.getKnowledgeId());
            }
            for (WikiPendingOp op : pendingOps) {
                if (!unappliedSlugKIDs.contains(op.getKnowledgeId())) {
                    continue;
                }
                if (!failedKIDs.add(op.getKnowledgeId())) {
                    continue;
                }
                failedOps.add(op);
            }
        }

        // 构造 trim 集合：应当从 task_pending_ops 移除的行。从完整的 peekedIDs 出发
        // （我们拉到的每一行，含被 knowledge_id 去重折叠掉的），减去任何失败 op 的
        // dbID——那些必须留着，让 requeueFailedOps 决定重试还是进死信。
        Set<Long> failedIdSet = new LinkedHashSet<>();
        for (WikiPendingOp op : failedOps) {
            if (op.getDbId() != 0) {
                failedIdSet.add(op.getDbId());
            }
        }
        List<Long> trimIds = new ArrayList<>(peekedIds.size());
        for (Long id : peekedIds) {
            if (failedIdSet.contains(id)) {
                continue;
            }
            trimIds.add(id);
        }
        ingestService.trimPendingListDetached(trimIds);

        // 处理失败的 op：fail_count 加一，达到上限就进死信。<b>必须</b>在 trim 之后跑，
        // 这样成功的兄弟行已经从队列里消失——否则后续批次可能重新拾起它们。
        if (!failedOps.isEmpty()) {
            List<Exception> settleErrors = ingestService.requeueFailedOpsDetached(payload, failedOps);
            if (!settleErrors.isEmpty()) {
                stats.exitStatus = "settle_failed";
                throw new IllegalStateException("wiki ingest: settle claimed rows: "
                        + settleErrors.get(0).getMessage(), settleErrors.get(0));
            }
        }

        log.info("wiki ingest: batch completed for KB {}, {} ops, {} pages affected",
                kbId, pendingOps.size(), allPagesAffected.size());

        // 给后续定节奏：限流触发时退避，让 per-minute 窗口有机会重置，而不是立刻重试失败
        // 的文档。
        Duration followUpDelay = WikiIngestConstants.FOLLOW_UP_DELAY;
        if (rateLimited.get()) {
            followUpDelay = WikiIngestConstants.RATE_LIMIT_BACKOFF;
            log.warn("wiki ingest: KB {} hit upstream rate limiting, backing off follow-up to {}",
                    kbId, followUpDelay);
        }
        stats.followUpScheduled = scheduleFollowUp(payload, followUpDelay);
    }

    /** 让 reduce 的闭包能把"可能被 catch 掉的结果"带出来（Java 的 lambda 捕获限制） */
    private record Reduced(ReduceOutcome value) {
        static final Reduced NONE = new Reduced(new ReduceOutcome(false, "", false, null));
    }

    /**
     * 对照 Go map 阶段里 retract 分支（batch L436-507）：在运行期解析权威页面集合。
     *
     * <p>调用方（{@code cleanupWikiOnKnowledgeDelete}）从任务触发<b>之前</b>的 DB 快照
     * 采集 PageSlugs，但存在一个窗口：清理跑在 ingest 之前时快照为空、而并发 ingest
     * 可能已经建出页面；或者上一次 ingest 批次在快照之后建了新页面。
     * 在这里重新查 {@code ListPagesBySourceRef}，把调用方的 slug 与当前任何引用该知识的
     * 页面求并集，从而没有页面会被漏掉。它也让我们支持"故意用空 PageSlugs 入队 retract"
     * 的调用方——即"自己去搞清楚"。</p>
     */
    private void mapRetractOp(WikiIngestPayload payload,
                              WikiPendingOp op,
                              Object mapMu,
                              Map<String, List<SlugUpdate>> slugUpdates,
                              List<String> retractFolderIDs,
                              Stats stats) {
        Set<String> slugSet = new LinkedHashSet<>();
        Set<String> folderSet = new LinkedHashSet<>();
        if (op.getPageSlugs() != null) {
            for (String slug : op.getPageSlugs()) {
                if (slug != null && !slug.isEmpty()) {
                    slugSet.add(slug);
                }
            }
        }
        if (op.getFolderIds() != null) {
            for (String folderId : op.getFolderIds()) {
                if (folderId != null && !folderId.isEmpty()) {
                    folderSet.add(folderId);
                }
            }
        }
        if (!op.getKnowledgeId().isEmpty()) {
            List<WikiPage> livePages = null;
            try {
                livePages = wikiService.listPagesBySourceRef(
                        payload.knowledgeBaseId(), op.getKnowledgeId());
            } catch (Exception e) {
                log.warn("wiki ingest: retract lookup failed for {}: {}",
                        op.getKnowledgeId(), e.getMessage());
            }
            if (livePages != null) {
                for (WikiPage p : livePages) {
                    if (p == null || p.getSlug().isEmpty()) {
                        continue;
                    }
                    // 索引页从不携带真实 source_ref；如果它们以某种方式出现在这里就跳过
                    // ——reduce 阶段本来也会是 no-op。
                    if (WikiConstants.PAGE_TYPE_INDEX.equals(p.getPageType())) {
                        continue;
                    }
                    slugSet.add(p.getSlug());
                    if (!p.getFolderId().isEmpty()) {
                        folderSet.add(p.getFolderId());
                    }
                }
            }
        }

        synchronized (mapMu) {
            stats.retractOps++;
            stats.retractHandled++;
            stats.docPreview.add("retract[" + WikiTextUtils.previewText(op.getKnowledgeId(), 24)
                    + "]: " + WikiTextUtils.previewText(op.getDocTitle(), 48)
                    + " (" + slugSet.size() + " slugs)");

            for (String slug : slugSet) {
                slugUpdates.computeIfAbsent(slug, k -> new ArrayList<>()).add(
                        SlugUpdate.retract(slug, op.getKnowledgeId(), op.getDocTitle(),
                                op.getDocSummary(),
                                WikiLanguageSupport.resolveLanguageName(op.getLanguage())));
            }
            for (String folderId : folderSet) {
                retractFolderIDs.add(folderId);
            }
        }
    }

    /** 对照 Go 的 {@code collectUnapplied} 闭包（batch L604-612） */
    private static void collectUnapplied(Object reduceMu, Set<String> unappliedSlugKIDs,
                                         List<SlugUpdate> updates) {
        synchronized (reduceMu) {
            for (SlugUpdate u : updates) {
                if (!u.getKnowledgeId().isEmpty()) {
                    unappliedSlugKIDs.add(u.getKnowledgeId());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ProcessWikiFinalize（对照 Go batch L916-1154）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code ProcessWikiFinalize}（batch L916-1154）：跑防抖的、按 KB 的
     * KB 级收敛：索引导语重建、死链清理、交叉链接注入。它排空
     * {@code task_pending_ops} 的 finalize 通道（由 {@code ProcessWikiIngest} 经
     * {@code enqueueFinalize} 写入），让 N 篇文档的突发只重建索引<b>一次</b>，
     * 而不是每个 5 文档批次一次。
     */
    @Override
    public void processWikiFinalize(WikiIngestPayload payload) {
        long startedAt = System.currentTimeMillis();
        String kbId = payload.knowledgeBaseId();
        if (pendingRepo == null) {
            return;
        }

        // 按 KB 的 finalize 锁，与 ingest 的 active 锁分离，因此 finalize 与 ingest
        // 批次永不互相阻塞。asynq.TaskID 的合流已经保证每个 KB 最多一个 finalize 待执行；
        // 这道锁守护"重排重叠窗口"里与并发索引页写入的竞争。
        WikiFinalizeLock.AcquireResult acquired = finalizeLock.tryAcquire(kbId);
        if (acquired == WikiFinalizeLock.AcquireResult.FAILED) {
            // fail CLOSED：无锁执行会让两次 finalize 排空同一批 PeekBatch 行并重复重建
            // 索引页。返回错误让任务重试。
            throw new IllegalStateException("wiki finalize: acquire lock failed for KB " + kbId);
        }
        if (acquired == WikiFinalizeLock.AcquireResult.BUSY) {
            // 另一个 finalize 正在跑；它会排空通道并在还有行时重排。安全地 no-op。
            return;
        }
        try {
            // 同 processWikiIngest：队列线程上没有 HTTP Filter 链填过 TenantContext
            try (WikiBatchSupport.TenantScope ignored =
                         WikiBatchSupport.enterTenantScope(payload.tenantId())) {
                runFinalize(payload, startedAt);
            }
        } finally {
            finalizeLock.release(kbId);
        }
    }

    private void runFinalize(WikiIngestPayload payload, long startedAt) {
        String kbId = payload.knowledgeBaseId();
        List<TaskPendingOp> rows;
        try {
            rows = pendingRepo.peekBatch(WikiIngestConstants.FINALIZE_TASK_TYPE,
                    WikiIngestConstants.TASK_SCOPE, kbId, WikiIngestConstants.FINALIZE_MAX_ROWS);
        } catch (Exception e) {
            throw new IllegalStateException("wiki finalize: peek: " + e.getMessage(), e);
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }

        KnowledgeBase kb = getKnowledgeBaseByIDOnly(kbId);
        if (kb == null) {
            ingestService.clearDeletedKnowledgeBasePendingOps(kbId);
            return;
        }

        // 把排空的行聚合为：受影响 slug（去重）、新的交叉链接 ref、索引导语的变更描述。
        // id 先收集起来，这样下面"KB 已停用"的短路分支也能排空通道。
        List<Long> ids = new ArrayList<>(rows.size());
        List<Long> pruneRowIDs = new ArrayList<>();
        Set<String> affectedSet = new LinkedHashSet<>();
        List<String> affectedSlugs = new ArrayList<>();
        List<WikiCrossLinker.LinkRef> freshRefs = new ArrayList<>();
        List<String> folderPruneIDs = new ArrayList<>();
        StringBuilder changeDesc = new StringBuilder();
        for (TaskPendingOp r : rows) {
            ids.add(r.getId());
            if (WikiIngestConstants.FINALIZE_OP_FOLDER_PRUNE.equals(r.getOp())) {
                pruneRowIDs.add(r.getId());
            }
            JsonNode rawPayload = r.getPayload();
            if (rawPayload == null || rawPayload.isNull()) {
                continue;
            }
            WikiFinalizeRow row;
            try {
                row = MAPPER.treeToValue(rawPayload, WikiFinalizeRow.class);
            } catch (Exception e) {
                log.warn("wiki finalize: unmarshal row id={} failed: {}", r.getId(), e.getMessage());
                continue;
            }
            if (row == null) {
                continue;
            }
            if (WikiIngestConstants.FINALIZE_OP_FOLDER_PRUNE.equals(r.getOp())) {
                if (row.folderIds() != null) {
                    folderPruneIDs.addAll(row.folderIds());
                }
                continue;
            }
            if (row.change() != null) {
                if (WikiIngestConstants.FINALIZE_REMOVED.equals(row.change().action())) {
                    changeDesc.append("<document_removed>\n<title>")
                            .append(nullToEmpty(row.change().docTitle()))
                            .append("</title>\n<summary>")
                            .append(nullToEmpty(row.change().docSummary()))
                            .append("</summary>\n</document_removed>\n\n");
                } else {
                    changeDesc.append("<document_added>\n<title>")
                            .append(nullToEmpty(row.change().docTitle()))
                            .append("</title>\n<summary>")
                            .append(nullToEmpty(row.change().docSummary()))
                            .append("</summary>\n</document_added>\n\n");
                }
                continue;
            }
            String slug = row.slug();
            if (slug != null && !slug.isEmpty()) {
                if (affectedSet.add(slug)) {
                    affectedSlugs.add(slug);
                }
                String title = row.title();
                if (title != null && !title.isEmpty()) {
                    freshRefs.add(new WikiCrossLinker.LinkRef(slug, title));
                }
            }
        }

        // KB 已不再是 wiki（被删 / 改类型）——排空通道，避免行堆积，然后停下。
        if (!kb.getIndexingStrategy().isWikiEnabled()) {
            ingestService.trimPendingListDetached(ids);
            return;
        }

        WikiConfig wikiConfig = wikiConfigOf(kb);
        String synthesisModelId = wikiConfig == null ? "" : nullToEmpty(wikiConfig.getSynthesisModelId());
        if (synthesisModelId.isEmpty()) {
            synthesisModelId = nullToEmpty(kb.getSummaryModelId());
        }
        if (synthesisModelId.isEmpty()) {
            // 没有模型可用于重建索引；仍跑纯文本遍历，然后排空。
            // 缺模型是配置缺口，不是瞬时错误。
            log.warn("wiki finalize: no synthesis model for KB {}, skipping index rebuild", kbId);
        }

        WikiBatchContext batchCtx = newWikiBatchContext(kbId, wikiConfig);
        String lang = WikiLanguageSupport.languageNameFromContext();

        boolean indexRebuilt = false;
        if (changeDesc.length() > 0 && !synthesisModelId.isEmpty()) {
            LlmChatClient chatModel = null;
            try {
                chatModel = modelResolver.getChatModel(synthesisModelId);
            } catch (RuntimeException e) {
                log.warn("wiki finalize: get chat model failed: {}", e.getMessage());
            }
            if (chatModel != null) {
                try {
                    ingestService.rebuildIndexPage(chatModel, payload, changeDesc.toString(), lang,
                            batchCtx.getContentInstructions());
                    indexRebuilt = true;
                } catch (Exception e) {
                    log.warn("wiki finalize: rebuild index failed: {}", e.getMessage());
                }
            }
        }

        if (!affectedSlugs.isEmpty()) {
            ingestService.cleanDeadLinks(kbId, affectedSlugs, batchCtx);
            ingestService.injectCrossLinks(kbId, affectedSlugs, freshRefs, batchCtx);
        }

        // 一次 retract 可能留下一个或多个变空的生成目录。在该 KB 还有任何 ingest 行排队
        // 或已认领时<b>不要</b>剪枝：taxonomy 规划会在 reduce 写页面<b>之前</b>创建目录，
        // 因此一个看似空的目录仍可能被在途批次拥有。持久化的 prune 行留在 finalize 通道
        // 里，等 ingest 通道排空后重试。
        boolean pruneDeferred = false;
        int deletedFolders = 0;
        if (!folderPruneIDs.isEmpty()) {
            Long pending = null;
            try {
                pending = pendingRepo.pendingCount(WikiIngestConstants.TASK_TYPE,
                        WikiIngestConstants.TASK_SCOPE, kbId);
            } catch (Exception e) {
                log.warn("wiki finalize: cannot verify ingest drain before folder prune: {}",
                        e.getMessage());
            }
            if (pending == null || pending > 0) {
                pruneDeferred = true;
            } else {
                try {
                    List<String> deleted = wikiService.pruneEmptyFolderChains(
                            kbId, WikiIngestService.uniqueWikiFolderIDs(folderPruneIDs));
                    deletedFolders = deleted == null ? 0 : deleted.size();
                } catch (Exception pruneErr) {
                    log.warn("wiki finalize: prune empty folders failed: {}", pruneErr.getMessage());
                    pruneDeferred = true;
                }
            }
        }

        // 排空处理过的行。尽力而为的收敛对应历史批内行为：索引重建失败只记日志（不重试），
        // 因此无论成败都删除，免得永远重跑整遍。
        List<Long> idsToTrim = ids;
        if (pruneDeferred && !pruneRowIDs.isEmpty()) {
            Set<Long> deferred = new LinkedHashSet<>(pruneRowIDs);
            idsToTrim = new ArrayList<>(ids.size());
            for (Long id : ids) {
                if (!deferred.contains(id)) {
                    idsToTrim.add(id);
                }
            }
        }
        ingestService.trimPendingListDetached(idsToTrim);

        // 如果在我们干活期间又有 finalize 行落进来，就重排，让它们得到自己的收敛遍。
        boolean rescheduled = false;
        if (pruneDeferred) {
            ingestService.scheduleFinalizeRetry(payload);
            rescheduled = true;
        }
        long remaining;
        try {
            remaining = pendingRepo.pendingCount(WikiIngestConstants.FINALIZE_TASK_TYPE,
                    WikiIngestConstants.TASK_SCOPE, kbId);
        } catch (Exception e) {
            remaining = 0;
        }
        if (remaining > 0) {
            if (!pruneDeferred) {
                ingestService.scheduleFinalize(payload);
            }
            rescheduled = true;
        }

        log.info("wiki finalize: kb={} rows={} affected_slugs={} deleted_folders={} "
                        + "folder_prune_deferred={} index_rebuilt={} rescheduled={} elapsed={}ms",
                kbId, rows.size(), affectedSlugs.size(), deletedFolders, pruneDeferred,
                indexRebuilt, rescheduled, System.currentTimeMillis() - startedAt);
    }

    // ═══════════════════════════════════════════════════════════════
    // mapOneDocument（对照 Go batch L1156-1601）
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code mapOneDocument} 的 {@code (*docIngestResult, []SlugUpdate, error)} 返回 */
    public record MapResult(DocIngestResult result, List<SlugUpdate> updates) { }

    /**
     * 对照 Go {@code mapOneDocument}（batch L1156-1601）：对单篇文档跑完整 Map 阶段
     * ——取消守卫、分块重建、Pass 0 抽取（含旧版回落）、摘要与引用并行、引用回填、
     * 身份重认领、新旧页面调和。
     *
     * @return {@code result == null && updates == null} 表示该文档在某个终态、不可重试的
     *         状态下被<b>跳过</b>（知识已删 / 无 chunk / 文本不足）
     * @throws RuntimeException 可重试的失败（调用方记入 failedOps）
     */
    public MapResult mapOneDocument(LlmChatClient chatModel,
                                    WikiIngestPayload payload,
                                    WikiPendingOp op,
                                    WikiBatchContext batchCtx) {
        long docStartedAt = System.currentTimeMillis();
        String knowledgeID = op.getKnowledgeId();
        String lang = WikiLanguageSupport.resolveLanguageName(op.getLanguage());
        String kbId = payload.knowledgeBaseId();

        // 对照 Go 的 beginWikiSubspan：Java 侧追踪未实现，恒返回 null
        Object wikiSpan = spans.beginSubSpan(null, "postprocess.wiki",
                Map.of("language", lang, "knowledge_base_id", kbId));

        // 守卫 ingest/delete 竞争：用户在任务排队期间（wikiIngestDelay = 30 秒）或更早
        // 阶段在途期间删掉了文档，我们<b>绝不</b>能继续做 LLM 抽取——那会建出 source_refs
        // 指向幽灵 knowledge ID 的 wiki 页面，永远无法经 wiki_read_source_doc 到达。
        if (ingestService.isKnowledgeGone(kbId, knowledgeID)) {
            log.info("wiki ingest: knowledge {} has been deleted, skip map", knowledgeID);
            spans.skipSpan(wikiSpan, "knowledge_deleted");
            return new MapResult(null, null);
        }

        List<Chunk> chunks;
        try {
            chunks = listTextChunksByKnowledgeID(payload.tenantId(), knowledgeID);
        } catch (Exception e) {
            spans.failSpan(wikiSpan, "LIST_CHUNKS_FAILED", e.getMessage(), e);
            throw new IllegalStateException("get chunks: " + e.getMessage(), e);
        }
        if (chunks.isEmpty()) {
            log.info("wiki ingest: document {} has no chunks, skip", knowledgeID);
            spans.skipSpan(wikiSpan, "no_chunks");
            return new MapResult(null, null);
        }

        String content = ingestService.reconstructEnrichedContent(chunks, payload.tenantId());
        int rawRuneCount = content.codePointCount(0, content.length());
        if (rawRuneCount > WikiIngestConstants.MAX_CONTENT_FOR_WIKI) {
            content = content.substring(0,
                    content.offsetByCodePoints(0, WikiIngestConstants.MAX_CONTENT_FOR_WIKI));
        }
        log.info("wiki ingest: doc {} chunks={} content_len(raw={},truncated={})",
                knowledgeID, chunks.size(), rawRuneCount, content.codePointCount(0, content.length()));

        // 文档没有真实文本时拒绝跑 LLM 抽取——例如扫描版 PDF 的页面被转成图片、但 VLM OCR
        // 什么都没产出。没有这道守卫，LLM 只剩图片标记，会兴高采烈地编造实体与概念。
        if (!WikiImageMarkup.hasSufficientTextContent(content)) {
            log.warn("wiki ingest: doc {} has insufficient text content after stripping image markup "
                    + "(raw_len={}), skipping LLM extraction", knowledgeID, rawRuneCount);
            spans.skipSpan(wikiSpan, "insufficient_text_content");
            return new MapResult(null, null);
        }

        String docTitle = resolveDocTitle(knowledgeID, chunks);

        // 引用来源引用。<b>刻意</b>只用 knowledge ID（不用 docTitle，后者通常是上传文件名），
        // 这样文件名不会泄漏进下游 LLM prompt 可能读到的引用字符串里。
        String sourceRef = knowledgeID;
        Set<String> oldPageSlugs = ingestService.getExistingPageSlugsForKnowledge(kbId, knowledgeID);

        // Pass 0：轻量候选 slug 抽取（只有骨架）。失败时回落到旧版单次抽取器，让文档仍能
        // 被摄取，只是没有 chunk 级引用。
        List<ExtractedItem> extractedEntities;
        List<ExtractedItem> extractedConcepts;
        Map<String, ExtractedItem> slugItems;
        boolean pass0Failed = false;
        log.info("wiki ingest: pass 0 — extracting candidate slugs for {}", knowledgeID);
        Map<String, Object> extractInput = new LinkedHashMap<>();
        extractInput.put("content_chars", content.codePointCount(0, content.length()));
        extractInput.put("old_pages", oldPageSlugs == null ? 0 : oldPageSlugs.size());
        Object extractSpan = spans.beginSubSpan(wikiSpan, "postprocess.wiki.extract", extractInput);
        try {
            WikiIngestCitePipeline.CandidateSlugs candidates = citePipeline.extractCandidateSlugs(
                    chatModel, kbId, content, lang, oldPageSlugs, batchCtx);
            extractedEntities = candidates.entities();
            extractedConcepts = candidates.concepts();
            slugItems = candidates.slugItems();
        } catch (RuntimeException e) {
            log.warn("wiki ingest: pass 0 failed for {} ({}) — falling back to legacy extractor",
                    knowledgeID, e.getMessage());
            pass0Failed = true;
            try {
                WikiIngestCitePipeline.CandidateSlugs fallback =
                        citePipeline.extractEntitiesAndConceptsNoUpsert(
                                chatModel, kbId, content, lang, oldPageSlugs, batchCtx);
                extractedEntities = fallback.entities();
                extractedConcepts = fallback.concepts();
                slugItems = fallback.slugItems();
            } catch (RuntimeException e2) {
                log.warn("wiki ingest: legacy fallback also failed for {}: {}",
                        knowledgeID, e2.getMessage());
                spans.failSpan(extractSpan, "EXTRACT_FAILED", e2.getMessage(), e2);
                spans.failSpan(wikiSpan, "EXTRACT_FAILED", e2.getMessage(), e2);
                throw e2;
            }
        }
        Map<String, Object> extractOut = new LinkedHashMap<>();
        extractOut.put("entities", extractedEntities.size());
        extractOut.put("concepts", extractedConcepts.size());
        extractOut.put("pass0_fallback", pass0Failed);
        extractOut.put("entities_preview", WikiIngestPreviews.previewExtractedItems(extractedEntities, 8));
        extractOut.put("concepts_preview", WikiIngestPreviews.previewExtractedItems(extractedConcepts, 8));
        spans.endSpan(extractSpan, extractOut);

        // 为 Summary 的 wiki-link 输入构造 slug 列表。
        List<String> summaryExtractedPages = new ArrayList<>(slugItems.keySet());
        // Wiki 摘要 slug 由 knowledge ID 派生，而<b>不是</b> docTitle（通常是上传文件名）。
        // 像 "summary/mx5280-pdf" 这样的文件名式 slug 会在下游 LLM prompt 读取的交叉链接
        // 语境里暴露文件名；UUID 式 slug 更丑，但对幻觉安全。
        String summarySlug = "summary/" + WikiTextUtils.slugify(knowledgeID);
        StringBuilder slugListing = new StringBuilder();
        for (String slug : summaryExtractedPages) {
            ExtractedItem item = slugItems.get(slug);
            if (item != null) {
                String aliases = "";
                if (!item.getAliases().isEmpty()) {
                    aliases = " (Aliases: " + String.join(", ", item.getAliases()) + ")";
                }
                slugListing.append("- [[").append(slug).append("]] = ")
                        .append(item.getName()).append(aliases).append('\n');
            } else {
                slugListing.append("- [[").append(slug).append("]]\n");
            }
        }

        // 摘要与分块分类在 Pass 0 输出给定的前提下互相独立——并行跑。
        // 摘要负责 wiki-link 注入；分类给每个候选 slug 挂上具体的 chunk ID。
        final String[] summaryContentHolder = { null };
        final RuntimeException[] summaryErrHolder = { null };
        @SuppressWarnings("unchecked")
        final Map<String, List<String>>[] citationsHolder = new Map[] { Map.of() };
        @SuppressWarnings("unchecked")
        final List<NewSlugFromCitation>[] newSlugsHolder = new List[] { List.of() };
        final int[] batchCountHolder = { 0 };

        Map<String, Object> summaryInput = new LinkedHashMap<>();
        summaryInput.put("content_chars", content.codePointCount(0, content.length()));
        summaryInput.put("extracted_slugs", summaryExtractedPages.size());
        Object summarySpan = spans.beginSubSpan(wikiSpan, "postprocess.wiki.summary", summaryInput);
        Map<String, Object> classifyInput = new LinkedHashMap<>();
        classifyInput.put("chunks", chunks.size());
        classifyInput.put("candidates", extractedEntities.size() + extractedConcepts.size());
        // 两条调用在同一个 wikiSpan 父节点下并行跑——它们的子 span 在 trace 视图里会视觉
        // 重叠，这正确反映了它们的墙钟并发。
        Object classifySpan = pass0Failed
                ? null
                : spans.beginSubSpan(wikiSpan, "postprocess.wiki.classify", classifyInput);

        final boolean pass0FailedFinal = pass0Failed;
        final String contentFinal = content;
        final List<ExtractedItem> entitiesFinal = extractedEntities;
        final List<ExtractedItem> conceptsFinal = extractedConcepts;
        final String slugListingFinal = slugListing.toString();
        final String summarySlugFinal = summarySlug;
        List<Runnable> parallel = new ArrayList<>(2);
        parallel.add(() -> {
            try {
                String generated = ingestService.generateWithTemplate(chatModel,
                        WikiPrompts.WIKI_SUMMARY_PROMPT,
                        Map.of(
                                "Content", contentFinal,
                                "Language", lang == null ? "" : lang,
                                "ExtractedSlugs", slugListingFinal,
                                "CustomInstructions",
                                batchCtx == null ? "" : batchCtx.getContentInstructions(),
                                "InstructionScope", WikiBatchConstants.INSTRUCTION_SCOPE_CONTENT));
                summaryContentHolder[0] = generated;
                WikiTextUtils.SummaryLine parts = WikiTextUtils.splitSummaryLine(generated);
                Map<String, Object> summaryOut = new LinkedHashMap<>();
                summaryOut.put("chars", generated.codePointCount(0, generated.length()));
                summaryOut.put("summary_line", WikiTextUtils.previewText(parts.summary(), 160));
                summaryOut.put("body_preview", WikiTextUtils.previewText(parts.content(), 320));
                spans.endSpan(summarySpan, summaryOut);
            } catch (RuntimeException e) {
                summaryErrHolder[0] = e;
                spans.failSpan(summarySpan, "SUMMARY_FAILED", e.getMessage(), e);
            }
        });
        parallel.add(() -> {
            // Pass 0 回落到旧版路径时跳过引用阶段——旧版输出本身已包含改写过的 Details，
            // 再做 chunk 引用是多余的，只会白花 LLM 调用。
            if (pass0FailedFinal) {
                citationsHolder[0] = new LinkedHashMap<>();
                return;
            }
            String candidatesXml = WikiIngestCitePipeline.renderCandidateSlugsXML(
                    entitiesFinal, conceptsFinal);
            WikiIngestCitePipeline.CitationResult citationResult = citePipeline.classifyChunkCitations(
                    chatModel, candidatesXml, chunks, lang, batchCtx);
            citationsHolder[0] = citationResult.citations();
            newSlugsHolder[0] = citationResult.newSlugs();
            batchCountHolder[0] = citationResult.batchCount();
            Map<String, Object> classifyOut = new LinkedHashMap<>();
            classifyOut.put("cited_slugs", citationResult.citations().size());
            classifyOut.put("new_slugs", citationResult.newSlugs().size());
            classifyOut.put("batches", citationResult.batchCount());
            classifyOut.put("top_cited",
                    WikiIngestPreviews.topCitedSlugs(citationResult.citations(), 8));
            classifyOut.put("new_slugs_sample",
                    WikiIngestPreviews.previewNewSlugs(citationResult.newSlugs(), 8));
            spans.endSpan(classifySpan, classifyOut);
        });
        WikiBatchSupport.fanOut(2, parallel);

        // 把引用合并回条目结构（不失败；没有引用的条目简单保留 Description+Details 回落）。
        WikiIngestCitePipeline.MergedCitations merged = WikiIngestCitePipeline.mergeCitationsIntoItems(
                extractedEntities, extractedConcepts, citationsHolder[0], newSlugsHolder[0]);
        extractedEntities = merged.entities();
        extractedConcepts = merged.concepts();
        int uncited = merged.uncited();
        WikiIngestDedupService.Identities reclaimed = dedupService.reclaimExtractedIdentities(
                kbId, extractedEntities, extractedConcepts, batchCtx);
        extractedEntities = reclaimed.entities();
        extractedConcepts = reclaimed.concepts();

        // 重建 slugItems，让"没能挺过合并的陈旧条目"与"引用遍发现的崭新 slug"都反映到
        // summaryExtractedPages 的追踪里。
        slugItems = new LinkedHashMap<>();
        for (ExtractedItem item : extractedEntities) {
            if (!item.getSlug().isEmpty() && !item.getName().isEmpty()) {
                slugItems.put(item.getSlug(), item);
            }
        }
        for (ExtractedItem item : extractedConcepts) {
            if (!item.getSlug().isEmpty() && !item.getName().isEmpty()) {
                slugItems.put(item.getSlug(), item);
            }
        }

        // extractedPages 记录本文件物化出的每一个 wiki 页面（entities、concepts，加上下面
        // 追加的摘要页）。slug 用于链接/撤回记账；title 保留给 trace 输出与 finalize 处理用。
        List<DocIngestResult.PageRef> extractedPages = new ArrayList<>(slugItems.size() + 1);
        for (Map.Entry<String, ExtractedItem> e : slugItems.entrySet()) {
            String title = e.getValue().getName();
            if (title.isEmpty()) {
                title = e.getKey();
            }
            extractedPages.add(new DocIngestResult.PageRef(e.getKey(), title));
        }

        // 统计所有 slug 引用到的不同 chunk 数（用于日志）。
        Set<String> citedChunkSet = WikiIngestCitePipeline.citedChunkSet(citationsHolder[0]);

        List<SlugUpdate> updates = new ArrayList<>();
        // docSummaryLine 是用于简短日志/审计预览与 retract prompt 里 <document_added> 块的
        // 一句话标题。docSummary 是挂到每个 entity/concept 更新上的完整摘要正文，
        // 让编辑模型在 <source_context> 里拿到丰富的框定信息。
        String docSummaryLine;
        String docSummary;

        RuntimeException summaryErr = summaryErrHolder[0];
        if (summaryErr != null) {
            // 摘要是被摄取文档的头号产物——没有摘要页的文档只算半摄取，而且会让
            // entity/concept 更新悬空、没有根可以链回索引。历史上这里只记日志就走，
            // 意味着一发瞬时 504 会永久丢掉该文档的摘要页。
            //
            // 这里返回错误会把该 op 送进 failedOps（见 ProcessWikiIngest 的 map 阶段循环），
            // requeueFailedOps 随后把它追加回待办列表，让下一批次重试。
            // generateWithTemplate 内部的退避重试已经在我们放弃之前耗尽了 LLM 自己的瞬时
            // 错误预算。
            log.error("wiki ingest: generate summary failed for {}, will requeue: {}",
                    knowledgeID, summaryErr.getMessage());
            spans.failSpan(wikiSpan, "SUMMARY_FAILED", summaryErr.getMessage(), summaryErr);
            throw new IllegalStateException("generate summary: " + summaryErr.getMessage(), summaryErr);
        }
        String summaryContent = summaryContentHolder[0] == null ? "" : summaryContentHolder[0];
        WikiTextUtils.SummaryLine parts = WikiTextUtils.splitSummaryLine(summaryContent);
        String sumLine = parts.summary();
        String sumBody = parts.content();
        if (sumBody.isEmpty()) {
            sumBody = summaryContent;
        }
        if (sumLine.isEmpty()) {
            sumLine = docTitle;
        }
        docSummaryLine = sumLine;
        docSummary = sumBody;
        if (docSummary.trim().isEmpty()) {
            docSummary = sumLine;
        }

        SlugUpdate summaryUpdate = new SlugUpdate(summarySlugFinal, SlugUpdate.TYPE_SUMMARY);
        summaryUpdate.setDocTitle(docTitle);
        summaryUpdate.setKnowledgeId(knowledgeID);
        summaryUpdate.setSourceRef(sourceRef);
        summaryUpdate.setLanguage(lang);
        summaryUpdate.setSummaryLine(sumLine);
        summaryUpdate.setSummaryBody(sumBody);
        updates.add(summaryUpdate);
        extractedPages.add(new DocIngestResult.PageRef(summarySlugFinal, docTitle));

        // Entities
        for (ExtractedItem item : extractedEntities) {
            if (item.getSlug().isEmpty()) {
                continue;
            }
            SlugUpdate u = new SlugUpdate(item.getSlug(), SlugUpdate.TYPE_ENTITY);
            u.setItem(item);
            u.setDocTitle(docTitle);
            u.setKnowledgeId(knowledgeID);
            u.setSourceRef(sourceRef);
            u.setLanguage(lang);
            u.setSourceChunks(item.getSourceChunks());
            u.setDocSummary(docSummary);
            updates.add(u);
        }

        // Concepts
        for (ExtractedItem item : extractedConcepts) {
            if (item.getSlug().isEmpty()) {
                continue;
            }
            SlugUpdate u = new SlugUpdate(item.getSlug(), SlugUpdate.TYPE_CONCEPT);
            u.setItem(item);
            u.setDocTitle(docTitle);
            u.setKnowledgeId(knowledgeID);
            u.setSourceRef(sourceRef);
            u.setLanguage(lang);
            u.setSourceChunks(item.getSourceChunks());
            u.setDocSummary(docSummary);
            updates.add(u);
        }

        // 调和旧页面集合与新的抽取结果。（三种情形见 Go 注释 batch L1495-1523）
        //   (a) oldSlug ∉ new  → "retractStale"：文档不再提及该主题，剥掉它的引用
        //       （若这是唯一来源则可能删除页面）。用<b>新</b>正文作为撤回语境——如果 LLM
        //       找到匹配的事实就裁掉它们，否则这次撤回近似 no-op，这没问题。
        //   (b) oldSlug ∈ new 且是 entity/concept → reparse 换血：同时发出 "retract"
        //       （携带文档<b>上一版</b>摘要正文作为旧版信号）与常规新增。reduce 阶段看到
        //       HasAdditions=1 + HasRetractions=1，WikiPageModifyUserPrompt 正确地告诉编辑
        //       模型一次性"删掉旧 K 段、加上新 K 段"——给出替换语义而不是"在旧 K 上追加新 K"。
        //   (c) oldSlug ∈ new 且是 summary 页 → 什么都不做（reduce 的 summary 分支会整体
        //       覆盖，多发一次 retract 只会是死重）。
        //
        // priorContribution 是文档的<b>最后一份</b>摘要正文，在此处懒取
        // （而不是预先装进批次上下文）。首次摄取时为空——那时 oldPageSlugs 也为空，
        // 因此永远不会咨询它。
        String priorContribution = batchCtx == null
                ? "" : batchCtx.summaryContentByKnowledgeId(knowledgeID);

        Set<String> newSlugSet = new LinkedHashSet<>();
        for (DocIngestResult.PageRef ref : extractedPages) {
            newSlugSet.add(ref.slug());
        }

        int reparseOverlap = 0;
        int staleCount = 0;
        if (oldPageSlugs != null) {
            for (String oldSlug : oldPageSlugs) {
                if (newSlugSet.contains(oldSlug)) {
                    // 跳过 summary slug——它们会被 summary 更新整体覆盖，多发一次 retract
                    // 在下游只会被丢弃。
                    if (oldSlug.startsWith("summary/")) {
                        continue;
                    }
                    reparseOverlap++;
                    updates.add(SlugUpdate.retract(oldSlug, knowledgeID, docTitle,
                            priorContribution, lang));
                    continue;
                }
                staleCount++;
                SlugUpdate u = new SlugUpdate(oldSlug, SlugUpdate.TYPE_RETRACT_STALE);
                u.setRetractDocContent(content);
                u.setDocTitle(docTitle);
                u.setKnowledgeId(knowledgeID);
                u.setLanguage(lang);
                updates.add(u);
            }
        }

        log.info("wiki ingest: mapped knowledge {} title={} candidates={} chunks={} batches={} "
                        + "cited_chunks={} uncited_slugs={} new_slugs={} updates={} reparse_slugs={} "
                        + "stale_slugs={} pass0_fallback={} elapsed={}ms",
                knowledgeID, WikiTextUtils.previewText(docTitle, 80),
                slugItems.size(), chunks.size(), batchCountHolder[0], citedChunkSet.size(),
                uncited, newSlugsHolder[0].size(), updates.size(), reparseOverlap, staleCount,
                pass0Failed, System.currentTimeMillis() - docStartedAt);

        // Map 阶段的指标挂到 postprocess.wiki span 的 output 上，但<b>不</b>在这里 EndSpan
        // ——批次驱动方会让这个 span 一直开到 reduce + 索引重建 + 交叉链接注入 + 页面发布
        // 全部结束，再在文档页面全部写出后关闭它。
        Map<String, Object> mapStats = new LinkedHashMap<>();
        mapStats.put("doc_title", WikiTextUtils.previewText(docTitle, 120));
        mapStats.put("chunks", chunks.size());
        mapStats.put("candidate_slugs", slugItems.size());
        mapStats.put("cited_chunks", citedChunkSet.size());
        mapStats.put("uncited_slugs", uncited);
        mapStats.put("new_slugs", newSlugsHolder[0].size());
        mapStats.put("updates", updates.size());
        mapStats.put("reparse_slugs", reparseOverlap);
        mapStats.put("stale_slugs", staleCount);
        mapStats.put("extracted_pages", extractedPages.size());
        mapStats.put("summary_chars", docSummary.codePointCount(0, docSummary.length()));
        mapStats.put("pass0_fallback", pass0Failed);
        mapStats.put("classify_batches", batchCountHolder[0]);
        mapStats.put("summary_preview", WikiTextUtils.previewText(docSummaryLine, 160));

        DocIngestResult result = new DocIngestResult(knowledgeID);
        result.setDocTitle(docTitle);
        result.setSummary(docSummaryLine);
        result.setPages(extractedPages);
        result.setMapStats(mapStats);
        result.setWikiSpan(wikiSpan);
        return new MapResult(result, updates);
    }

    /**
     * 对照 Go {@code mapOneDocument} L1219-1232：文档标题优先取知识行，取不到就回落到
     * 第一个非空 chunk 的首行（短于 200 字节时），并裁掉 markdown 的 {@code "# "} 前缀。
     */
    private String resolveDocTitle(String knowledgeID, List<Chunk> chunks) {
        Knowledge kn = getKnowledgeByIDOnly(knowledgeID);
        if (kn != null && !kn.getTitle().isEmpty()) {
            return kn.getTitle();
        }
        for (Chunk ch : chunks) {
            if (ch.getContent() == null || ch.getContent().isEmpty()) {
                continue;
            }
            int idx = ch.getContent().indexOf('\n');
            String firstLine = idx < 0 ? ch.getContent() : ch.getContent().substring(0, idx);
            if (!firstLine.isEmpty() && firstLine.length() < 200) {
                String trimmed = firstLine.trim();
                if (trimmed.startsWith("# ")) {
                    trimmed = trimmed.substring(2);
                }
                return trimmed;
            }
        }
        return knowledgeID;
    }

    // ═══════════════════════════════════════════════════════════════
    // reduceSlugUpdates（对照 Go batch L1702-2123）
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code reduceSlugUpdates} 的 {@code (changed, affectedType, additionFailed, err)} 返回 */
    public record ReduceOutcome(boolean changed, String affectedType,
                                boolean additionFailed, RuntimeException error) { }

    /**
     * 对照 Go {@code reduceSlugUpdates}（batch L1702-2123）：把一个 slug 的全部更新
     * 读-改-写成一个页面。
     *
     * <ul>
     *   <li>{@code changed}：页面是否被创建或更新；</li>
     *   <li>{@code affectedType}：{@code "ingest"} 或 {@code "retract"}——驱动下游记账；</li>
     *   <li>{@code additionFailed}：该 slug 有 entity/concept 新增排队<b>且</b>
     *       {@code WikiPageModifyUserPrompt} 的 LLM 调用失败，因此没有页面存在/被刷新。
     *       调用方据此净化别处（例如该文档摘要页里）的死 {@code [[slug]]} 链接，并把该
     *       slug 从 wiki 日志流里去掉，免得用户看到一个点了 404 的条目；</li>
     *   <li>{@code error}：持久化 upsert 的传输/仓储错误。</li>
     * </ul>
     *
     * <p><b>span 归属（Go 注释）</b>：单个 slug 可以收到同一批次多个文档的贡献
     * （entity/concept 页面跨来源聚合）。Go 把 {@code postprocess.wiki.page[slug]} 子 span
     * 挂到 updates 列表里<b>第一个</b>贡献文档的 wikiSpan 下——span 树的拓扑只允许一个父节点。
     * Java 侧未实现追踪，因此这段拓扑逻辑无对应副作用，但 <b>contributors 的收集语义</b>
     * （按首次出现去重）被保留，供将来接线。</p>
     */
    public ReduceOutcome reduceSlugUpdates(LlmChatClient chatModel,
                                           String kbId,
                                           String slug,
                                           List<SlugUpdate> updates,
                                           long tenantId,
                                           WikiBatchContext batchCtx) {
        // ingest/delete 竞争的最终安全网：Map（已查过 isKnowledgeGone）与 Reduce 之间有一次
        // 很长的 LLM 调用，源文档可能在此期间被删。丢弃源知识已不存在的新增/摘要更新，
        // 免得复活一个幽灵 source_ref。retract 更新被保留——它们主动移除引用，正是文档消失
        // 时我们想要的。
        updates = ingestService.filterLiveUpdates(kbId, updates);
        if (updates == null || updates.isEmpty()) {
            return new ReduceOutcome(false, "", false, null);
        }

        List<String> contributors = new ArrayList<>();
        {
            Set<String> seen = new LinkedHashSet<>();
            for (SlugUpdate u : updates) {
                String kid = u.getKnowledgeId();
                if (kid.isEmpty() || !seen.add(kid)) {
                    continue;
                }
                contributors.add(kid);
            }
        }

        try {
            WikiPage page = wikiService.getPageBySlug(kbId, slug);
            boolean exists = page != null;

            if (!exists) {
                boolean hasAdditions = false;
                for (SlugUpdate u : updates) {
                    if (SlugUpdate.TYPE_ENTITY.equals(u.getType())
                            || SlugUpdate.TYPE_CONCEPT.equals(u.getType())
                            || SlugUpdate.TYPE_SUMMARY.equals(u.getType())) {
                        hasAdditions = true;
                        break;
                    }
                }
                if (!hasAdditions) {
                    return new ReduceOutcome(false, "", false, null);
                }

                page = new WikiPage();
                page.setId(UUID.randomUUID().toString());
                page.setTenantId(tenantId);
                page.setKnowledgeBaseId(kbId);
                page.setSlug(slug);
                page.setStatus(WikiConstants.STATUS_DRAFT);
                page.setSourceRefs(new ArrayList<>());
                page.setAliases(new ArrayList<>());
            }

            String affectedType = "ingest";

            SlugUpdate summaryUpdate = null;
            List<SlugUpdate> retracts = new ArrayList<>();
            List<SlugUpdate> additions = new ArrayList<>();

            for (SlugUpdate u : updates) {
                if (SlugUpdate.TYPE_SUMMARY.equals(u.getType())) {
                    summaryUpdate = u;
                } else if (u.isRetractType()) {
                    retracts.add(u);
                    affectedType = "retract";
                } else if (SlugUpdate.TYPE_ENTITY.equals(u.getType())
                        || SlugUpdate.TYPE_CONCEPT.equals(u.getType())) {
                    additions.add(u);
                    affectedType = "ingest"; // 新增覆盖 retract 的类型判定
                }
            }

            if (summaryUpdate != null) {
                page.setTitle(summaryUpdate.getDocTitle() + " - Summary");
                page.setContent(summaryUpdate.getSummaryBody());
                page.setSummary(summaryUpdate.getSummaryLine());
                page.setPageType(WikiConstants.PAGE_TYPE_SUMMARY);
                page.setSourceRefs(appendUnique(page.getSourceRefs(), summaryUpdate.getSourceRef()));
                // 摘要页不携带 chunk 级引用（它们是从整篇正文生成的文档级概要）。
                // 清理该 slug 曾经是 entity 页并被改造成摘要页时可能残留的陈旧 chunk ref。
                page.setChunkRefs(new ArrayList<>());
                if (exists) {
                    wikiService.updatePage(page);
                } else {
                    wikiService.createPage(page);
                }
                return new ReduceOutcome(true, affectedType, false, null);
            }

            StringBuilder remainingSourcesContent = new StringBuilder();
            StringBuilder deletedContent = new StringBuilder();
            StringBuilder relatedSlugs = new StringBuilder();
            StringBuilder newContentBuilder = new StringBuilder();
            StringBuilder sharedSourceContexts = new StringBuilder();

            String language = WikiLanguageSupport.resolveSlugUpdateLanguage(updates);

            if (!retracts.isEmpty()) {
                for (SlugUpdate r : retracts) {
                    deletedContent.append("<document>\n<title>").append(r.getDocTitle())
                            .append("</title>\n<content>\n").append(r.getRetractDocContent())
                            .append("\n</content>\n</document>\n\n");
                }

                Set<String> retractKIDs = new LinkedHashSet<>();
                for (SlugUpdate r : retracts) {
                    retractKIDs.add(r.getKnowledgeId());
                }

                for (String ref : page.getSourceRefs()) {
                    int pipeIdx = ref.indexOf('|');
                    String refKnowledgeID;
                    String refTitle;
                    if (pipeIdx > 0) {
                        refKnowledgeID = ref.substring(0, pipeIdx);
                        refTitle = ref.substring(pipeIdx + 1);
                    } else {
                        refKnowledgeID = ref;
                        refTitle = ref;
                    }

                    if (retractKIDs.contains(refKnowledgeID)) {
                        continue;
                    }

                    String refContent = batchCtx == null
                            ? "" : batchCtx.summaryContentByKnowledgeId(refKnowledgeID);
                    if (!refContent.isEmpty()) {
                        remainingSourcesContent.append("<document>\n<title>").append(refTitle)
                                .append("</title>\n<content>\n").append(refContent)
                                .append("\n</content>\n</document>\n\n");
                    } else {
                        remainingSourcesContent.append("<document>\n<title>").append(refTitle)
                                .append("</title>\n<content>\n(summary not available)\n</content>\n")
                                .append("</document>\n\n");
                    }
                }
                if (remainingSourcesContent.length() == 0) {
                    remainingSourcesContent.append("(no remaining sources)");
                }

                List<String> newRefs = new ArrayList<>();
                for (String ref : page.getSourceRefs()) {
                    int pipeIdx = ref.indexOf('|');
                    String refKnowledgeID = pipeIdx > 0 ? ref.substring(0, pipeIdx) : ref;
                    if (!retractKIDs.contains(refKnowledgeID)) {
                        newRefs.add(ref);
                    }
                }
                page.setSourceRefs(newRefs);
            }

            if (!additions.isEmpty()) {
                // 把 SourceChunks 解析成 chunk 正文：按 knowledge ID 一批查询，让
                // <new_information> 块可以逐字引用 chunk，而不是依赖简短的 Details 改写。
                Map<String, String> chunkContentByID =
                        citePipeline.resolveCitedChunks(tenantId, additions);
                // 一份文档摘要被该文档派生的每个页面共享。在任何页面专属元数据之前渲染一个
                // 确定性的、去重的块，让 provider 的前缀缓存能在并行的 reduce 调用之间复用。
                Map<String, String> sourceContextByRef = new LinkedHashMap<>();

                for (SlugUpdate add : additions) {
                    ExtractedItem item = add.getItem() == null ? new ExtractedItem() : add.getItem();
                    String cited = WikiIngestCitePipeline.collectCitedChunkContent(
                            add.sourceChunksOrEmpty(), chunkContentByID);
                    // 用文档级摘要正文把 chunk 框起来，让编辑模型既知道文档讲什么，也知道它
                    // 是哪一类文档（简历 vs 公告 vs 产品页 vs 日程）。只有一句话的标题对较长、
                    // 多主题的源文档来说太薄了。
                    String sourceCtx = add.getDocSummary().trim();
                    if (!sourceCtx.isEmpty()) {
                        String contextKey = add.getSourceRef();
                        if (contextKey.isEmpty()) {
                            contextKey = add.getKnowledgeId() + "\u0000" + add.getDocTitle();
                        }
                        sourceContextByRef.put(contextKey, "<document>\n<title>" + add.getDocTitle()
                                + "</title>\n<context>\n" + sourceCtx + "\n</context>\n</document>\n");
                    }
                    // 回落：没有引用可用（旧版路径 / 引用遍失败 / 坏的 chunk ID 被过滤掉）
                    // 时，沿用简短的 Details 摘要，让页面仍得到真实文本。
                    String body = cited.isEmpty() ? item.getDetails() : cited;
                    newContentBuilder.append("<document>\n<title>").append(add.getDocTitle())
                            .append("</title>\n<content>\n**").append(item.getName())
                            .append("**: ").append(item.getDescription())
                            .append("\n\n").append(body)
                            .append("\n</content>\n</document>\n\n");

                    for (String alias : item.getAliases()) {
                        page.setAliases(appendUnique(page.getAliases(), alias));
                    }
                    page.setSourceRefs(appendUnique(page.getSourceRefs(), add.getSourceRef()));

                    if (page.getTitle().isEmpty()) {
                        page.setTitle(item.getName());
                    }
                    if (page.getPageType().isEmpty()) {
                        page.setPageType(add.getType());
                    }
                }

                List<String> contextKeys = new ArrayList<>(sourceContextByRef.keySet());
                contextKeys.sort(GoStrings::compareByCodePoints);
                for (String key : contextKeys) {
                    sharedSourceContexts.append(sourceContextByRef.get(key));
                }
            }

            boolean changed = false;
            boolean additionFailed = false;

            if (!additions.isEmpty() || !retracts.isEmpty()) {
                Map<String, String> titles = batchCtx == null
                        ? Map.of() : batchCtx.slugTitleMany(new ArrayList<>(page.getOutLinks()));

                // slugHandles 把高熵 slug 藏到短引用句柄（ref-1、ref-2…）后面给编辑 LLM 用，
                // 生成之后再翻译回真实 slug（见下面的 decodeContent）。
                WikiSlugHandles slugHandles = new WikiSlugHandles();

                // 把每条出链 slug 藏到请求局部句柄后面，编辑模型因此永远不用重打真实 slug
                // ——UUID 式摘要 slug（summary/<uuid>）就是这样被弄花成 404 链接的。
                // <valid_wiki_links> 清单与 <existing_page_content> 里已有的 [[...]] 引用用的
                // 是<b>同一张</b>句柄表，因此模型看到一个一致、可安全复制的标识空间；
                // 我们在生成之后把句柄翻回真实 slug。
                Set<String> known = new LinkedHashSet<>(page.getOutLinks());
                for (String outSlug : page.getOutLinks()) {
                    slugHandles.handle(outSlug); // 预分配，让顺序稳定
                }
                for (String outSlug : page.getOutLinks()) {
                    String title = titles.get(outSlug);
                    if (title != null && !title.isEmpty()) {
                        relatedSlugs.append("- ").append(slugHandles.handle(outSlug))
                                .append(" (").append(title).append(")\n");
                    }
                }

                // 较早生成的页面可能仍含 [c003] 这类短 chunk 别名。它们是内部摄取元数据；
                // 保持编辑语境干净，让后续更新不会把它们复制进重写的正文。
                String existingContent = WikiBatchSupport.stripInlineChunkCitations(page.getContent());
                existingContent = slugHandles.encodeContent(existingContent, known);
                if (!exists || existingContent.isEmpty()) {
                    existingContent = "(New page)";
                }

                String hasAdditionsStr = additions.isEmpty() ? "" : "1";
                String hasRetractionsStr = retracts.isEmpty() ? "" : "1";

                // title/type 仍未设置时优雅回落（对良构更新不该发生——两者都在上面的
                // additions 循环里填好，而纯 retract 路径要求页面已存在——但保持防御性，
                // 免得给 LLM 喂一个空的身份块）。
                String pageTitle = page.getTitle().isEmpty() ? slug : page.getTitle();
                String pageType = page.getPageType().isEmpty()
                        ? WikiBatchConstants.PAGE_TYPE_FALLBACK : page.getPageType();
                String pageAliases = String.join(", ", page.getAliases());

                try {
                    String updatedContent = ingestService.generateWithTemplate(chatModel,
                            WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT,
                            Map.ofEntries(
                                    Map.entry("HasAdditions", hasAdditionsStr),
                                    Map.entry("HasRetractions", hasRetractionsStr),
                                    Map.entry("PageSlug", slug),
                                    Map.entry("PageTitle", pageTitle),
                                    Map.entry("PageType", pageType),
                                    Map.entry("PageAliases", pageAliases),
                                    Map.entry("ExistingContent", existingContent),
                                    Map.entry("SharedSourceContexts", sharedSourceContexts.toString()),
                                    Map.entry("NewContent", newContentBuilder.toString()),
                                    Map.entry("DeletedContent", deletedContent.toString()),
                                    Map.entry("RemainingSourcesContent", remainingSourcesContent.toString()),
                                    Map.entry("AvailableSlugs", relatedSlugs.toString()),
                                    Map.entry("Language", language == null ? "" : language),
                                    Map.entry("CustomInstructions",
                                            batchCtx == null ? "" : batchCtx.getContentInstructions()),
                                    Map.entry("InstructionScope", WikiBatchConstants.INSTRUCTION_SCOPE_CONTENT)));

                    if (updatedContent != null && !updatedContent.isEmpty()) {
                        // 在正文被解析/存储<b>之前</b>，把模型从编码语境里抄来的请求局部句柄
                        // （ref-N）翻回真实 slug，让 out_links 重新反映真实页面。
                        updatedContent = slugHandles.decodeContent(updatedContent);
                        WikiTextUtils.SummaryLine updated = WikiTextUtils.splitSummaryLine(updatedContent);
                        page.setContent(updated.content().isEmpty() ? updatedContent : updated.content());
                        if (!updated.summary().isEmpty()) {
                            page.setSummary(updated.summary());
                        }
                        changed = true;
                    }
                } catch (RuntimeException genErr) {
                    log.warn("wiki ingest: update/retract failed for slug {}: {}",
                            slug, genErr.getMessage());
                    // 标记新增失败，让批次能净化该文档摘要页里陈旧的 [[slug]] 引用，并把缺失
                    // 的页面排除出 finalize 处理。纯 retract 的失败不会毒化任何东西
                    // （页面保持原样），因此不标记。
                    if (!additions.isEmpty()) {
                        additionFailed = true;
                    }
                    // <b>不</b>把 LLM 错误向上传播：它已经记过日志，而 eg.Go 的调用方否则会
                    // 再记一次 "reduce failed for slug"。
                }
            }

            // 应用批次的 taxonomy 计划，但只对尚未归档的页面——新页面因此得到连贯的目录，
            // 而先前已归档或用户移动过的页面保持原位（手工编辑是权威的）。
            // 页面的 category_path 缓存由 CreatePage/UpdatePage 从 folder_id 派生，
            // 因此这里只赋 folder_id 就够了。
            if (page.getFolderId().isEmpty() && batchCtx != null) {
                String fid = batchCtx.getPlannedFolderId().get(slug);
                if (fid != null && !fid.isEmpty()) {
                    page.setFolderId(fid);
                }
            }

            if (changed) {
                // 就地刷新 chunk ref，让它们随行的其余部分一起持久化。纯 retract 更新
                // （无新增）保留既有 refs；新增轮次把新引用的 chunk 追加到已有之上（去重）。
                page.setChunkRefs(mergeChunkRefs(page.getChunkRefs(), additions));
                if (exists) {
                    wikiService.updatePage(page);
                } else {
                    wikiService.createPage(page);
                }
                return new ReduceOutcome(true, affectedType, additionFailed, null);
            }

            return new ReduceOutcome(false, "", additionFailed, null);
        } catch (RuntimeException e) {
            return new ReduceOutcome(false, "", false, e);
        }
    }

    /**
     * 对照 Go {@code mergeChunkRefs}（batch L2134-2154）：把页面当前的 chunk ID 与本批次
     * additions 引用的 chunk ID 求并集，保留插入顺序并丢弃重复项。空串被过滤掉，免得畸形
     * source_chunks 数组在列里留下垃圾。
     *
     * <p>没有 additions 的 retract 轮次保持当前 refs 不变——纯 retract 路径不携带 chunk ID
     * （只有 knowledge ID），没有那个信息我们无法做精细过滤。下次该 slug 经 additions 重新
     * 物化时，新的 chunk 会覆盖上去。</p>
     */
    public static List<String> mergeChunkRefs(List<String> current, List<SlugUpdate> additions) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (current != null) {
            for (String id : current) {
                if (id == null || id.isEmpty() || !seen.add(id)) {
                    continue;
                }
                out.add(id);
            }
        }
        if (additions != null) {
            for (SlugUpdate add : additions) {
                for (String chunkID : add.sourceChunksOrEmpty()) {
                    if (chunkID == null || chunkID.isEmpty() || !seen.add(chunkID)) {
                        continue;
                    }
                    out.add(chunkID);
                }
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    // 小工具
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code appendUnique}（wiki_ingest.go L2947-2955） */
    private static List<String> appendUnique(List<String> arr, String s) {
        return WikiTextUtils.appendUnique(arr == null ? new ArrayList<>() : arr, s);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize wiki payload", e);
        }
    }

    /** 暴露给测试：把 docPreview 列表折叠成日志片段（对照 Go previewStringSlice 的调用点） */
    static String preview(Stats stats, int limit) {
        return WikiTextUtils.previewStringSlice(new ArrayList<>(stats.docPreview), limit);
    }
}
