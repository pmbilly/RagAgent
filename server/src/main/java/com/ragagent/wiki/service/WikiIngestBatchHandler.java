package com.ragagent.wiki.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.knowledge.service.SpanTracker;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.domain.WikiActivityAudit;
import com.ragagent.wiki.domain.WikiConfig;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiExtractionGranularity;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.ragagent.common.wiki.SlugUpdate;

/**
 * wiki 生成管线的批次执行体：Map（逐文档抽取/摘要/引用）→ Reduce（逐 slug 落页）→
 * finalize（索引导语重建 / 死链清理 / 交叉链接注入 / 空目录剪枝）。
 *
 * <h2>并发模型</h2>
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
 * 会翻成 true，使释放动作变成 no-op；释放走 {@link WikiCleanupScope} 的脱钩清理。</p>
 *
 * <h2>已知取舍</h2>
 * <ol>
 *   <li>任务对象没有可读的"当前第几次尝试"，因此统计日志里不输出 retry 字段。</li>
 *   <li>{@code langfuse.InjectTracing}（HTTP 入口侧的 trace 注入）未接入；
 *       任务侧观测见 {@code InProcessWikiIngestTaskQueue}。</li>
 * </ol>
 */
@Service
public class WikiIngestBatchHandler implements WikiIngestTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestBatchHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    final WikiIngestService ingestService;
    final WikiPageService wikiService;
    final TaskPendingOpsRepository pendingRepo;
    final WikiIngestCitePipeline citePipeline;
    final WikiIngestTaxonomy taxonomy;
    final WikiIngestDedupService dedupService;
    final WikiModelResolver modelResolver;
    final WikiFinalizeLock finalizeLock;
    final ChunkMapper chunkMapper;
    final KnowledgeBaseMapper kbMapper;
    final KnowledgeMapper knowledgeMapper;
    final ObjectProvider<WikiActivityAudit> auditProvider;
    final ObjectProvider<WikiIngestTaskQueue> taskQueueProvider;

    /** wiki 批次 span 门面（接 SpanTracker 后真实上报，缺席时 no-op）。 */
    final WikiBatchSupport.WikiSpans spans;

    /** 摄取阶段协作者(构造期装配;只存 handler 引用,调用期才解引)。 */
    final WikiIngestRunSupport run;
    final WikiIngestMapPhase map;
    final WikiIngestReducePhase reduce;

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
                                  ObjectProvider<WikiIngestTaskQueue> taskQueueProvider,
                                  ObjectProvider<com.ragagent.knowledge.service.SpanTracker>
                                          spanTrackerProvider) {
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
        // tracker 缺席（测试/裁剪装配）→ no-op 门面
        this.spans = new WikiBatchSupport.WikiSpans(spanTrackerProvider.getIfAvailable());
        this.run = new WikiIngestRunSupport(this);
        this.map = new WikiIngestMapPhase(this);
        this.reduce = new WikiIngestReducePhase(this);
    }

    // ═══════════════════════════════════════════════════════════════
    // 统计载体
    // ═══════════════════════════════════════════════════════════════

    /**
     * 一次批处理的全部计数变量。
     *
     * <p>装进一个可变对象，由 {@code finally} 读取——统计日志无论走哪条退出路径
     * 都能观测到它们。</p>
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
    // KB / 知识 / 分块 的批量读取
    // ═══════════════════════════════════════════════════════════════

    /**
     * 按 id 取 KB，未找到返回 {@code null}。
     *
     * <p>软删过滤显式写 {@code deleted_at IS NULL}（约定 §9："soft delete 不用
     * {@code @TableLogic}"）。此处<b>不</b>按租户过滤（沿用既有查询语义）。</p>
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
     * 按 id 取知识文档，未找到返回 {@code null}。
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
     * <b>只取 text 类型</b>、按 {@code chunk_index ASC}。
     *
     * <p>本方法按设计只取 text；需要 summary / parent_text /
     * image 的调用方走 {@code ChunkRepository#listChunksByKnowledgeIDAndTypes}。</p>
     */
    List<Chunk> listTextChunksByKnowledgeID(long tenantId, String knowledgeId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getTenantId, tenantId)
                .eq(Chunk::getKnowledgeId, knowledgeId)
                .eq(Chunk::getChunkType, WikiIngestService.CHUNK_TYPE_TEXT)
                .orderByAsc(Chunk::getChunkIndex));
    }

    /** 解析 KB 行的 wiki_config 列（jsonb）为 {@link WikiConfig} */
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
    // 批次上下文
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构造本次运行使用的
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
     * String → 枚举的取值映射。
     *
     * <p>{@code WikiConfig} 的字段是 String（空串表示未配置），
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
    // 后续调度
    // ═══════════════════════════════════════════════════════════════

    /**
     * 若该 KB 的
     * {@code task_pending_ops} 里还有待办 op，就再排一个触发任务。
     *
     * <p>这只是在"批次排空了自己的认领窗口、但还有行剩下、且没有别的触发
     * 在排队"（例如稳定的上传涓流）时兜底。standard 模式已经把 KB 的积压扇到并发的
     * 认领批次上，因此这个短延迟通常只是轻量防抖，而不是在等锁释放。</p>
     *
     * <p>后续触发<b>不</b>带 TaskID，因此重复的后续触发不会被合并
     * ——它们最终都会看到空队列并廉价退出。</p>
     *
     * @param delay 常规场景传 {@link WikiIngestConstants#FOLLOW_UP_DELAY}；
     *              批次撞上上游限流时传 {@link WikiIngestConstants#RATE_LIMIT_BACKOFF}
     * @return 是否排上了后续
     */
    public boolean scheduleFollowUp(WikiIngestPayload payload, Duration delay) {
        if (pendingRepo == null) {
            // 没有持久化队列就没有待办可查
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
                    Duration.ofMinutes(60), // 任务超时：60 分钟
                    ""));
        } catch (Exception e) {
            log.warn("wiki ingest: follow-up enqueue failed: {}", e.getMessage());
            return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // ProcessWikiIngest 主流程
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void processWikiIngest(WikiIngestPayload payload) {
        long taskStartedAt = System.currentTimeMillis();
        Stats stats = new Stats();
        try {
            // 批次跑在队列线程上，没有 HTTP Filter 链填过 TenantContext，而模型解析
            // （ModelService.getModelByID 按租户可见性过滤）需要它——这里显式进入租户作用域。
            try (WikiBatchSupport.TenantScope ignored =
                         WikiBatchSupport.enterTenantScope(payload.tenantId())) {
                run.runIngest(payload, stats);
            }
        } finally {
            // 统计日志：无论哪条退出路径都要输出
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

    @Override
    public void processWikiFinalize(WikiIngestPayload payload) {
        run.finalize.processWikiFinalize(payload);
    }

    // ═══════════════════════════════════════════════════════════════
    // 小工具
    // ═══════════════════════════════════════════════════════════════

/** {@code reduceSlugUpdates} 的多值返回载体。 */
    public record ReduceOutcome(boolean changed, String affectedType,
                                boolean additionFailed, RuntimeException error) { }

/** 单文档 map 的结果载体：抽取结果 + 待落页的 slug 更新。 */
    public record MapResult(DocIngestResult result, List<SlugUpdate> updates) { }

    /** 去重追加 */
    static List<String> appendUnique(List<String> arr, String s) {
        return WikiTextUtils.appendUnique(arr == null ? new ArrayList<>() : arr, s);
    }

    /** 测试/跨协作者共用:chunk refs 合并(实现在 {@link WikiIngestReducePhase})。 */
    static List<String> mergeChunkRefs(List<String> current, List<SlugUpdate> additions) {
        return WikiIngestReducePhase.mergeChunkRefs(current, additions);
    }

    /** 包内 seam:Reduce 阶段委托(测试直调)。 */
    ReduceOutcome reduceSlugUpdates(LlmChatClient chatModel, String kbId,
            String slug, List<SlugUpdate> updates, long tenantId, WikiBatchContext batchCtx,
            Map<String, SpanTracker.SpanHandle> pageSpans) {
        return reduce.reduceSlugUpdates(chatModel, kbId, slug, updates, tenantId, batchCtx, pageSpans);
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize wiki payload", e);
        }
    }

    /** 暴露给测试：把 docPreview 列表折叠成日志片段 */
    static String preview(Stats stats, int limit) {
        return WikiTextUtils.previewStringSlice(new ArrayList<>(stats.docPreview), limit);
    }
}
