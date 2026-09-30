package com.ragagent.wiki.service.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.domain.TaskDeadLetter;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.mapper.TaskDeadLetterRepository;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.ragagent.common.wiki.WikiIngestPort;
import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.common.wiki.SlugUpdate;
import com.ragagent.wiki.service.WikiImageEnricher;
import com.ragagent.wiki.service.WikiKnowledgeFinalizer;
import com.ragagent.wiki.service.WikiLlmCallMetadata;
import com.ragagent.wiki.service.page.WikiCrossLinker;
import com.ragagent.wiki.service.page.WikiPageService;
import com.ragagent.wiki.service.page.WikiSlugLock;

/**
 * wiki 生成管线的主入口。
 *
 * <h2>持久化状态在哪</h2>
 * <ul>
 *   <li>{@code task_pending_ops}（{@code task_type="wiki:ingest"}，
 *       {@code scope="knowledge_base"}）：逐文档的 op 队列。取代了历史的
 *       Redis {@code wiki:pending:<kbID>} 列表——后者在 4 万文档规模下会被
 *       24 小时 TTL 驱逐。</li>
 *   <li>{@code task_dead_letters}：批内失败且耗尽 {@code MAX_FAIL_RETRIES} 的 op 落这里。</li>
 * </ul>
 *
 * <h2>三处结构性替换</h2>
 * <ol>
 *   <li><b>任务队列 → 进程内虚拟线程队列</b>（{@link InProcessWikiIngestTaskQueue}）：
 *       与 {@code KnowledgeProcessWorker} 同模式。延迟、TaskID 合并、
 *       重试预算与超时语义保留；多副本下的协调缺失见该实现注释。</li>
 *   <li><b>Redis → 可插拔端口</b>：{@link WikiSlugLock}（已有）、
 *       {@link WikiInflightLimiter}、{@link WikiDeletedTombstoneStore}。
 *       默认都是进程内实现（单 JVM 语义）。</li>
 *   <li><b>context.Context → 显式传参 + TenantContext</b>：取消传播改用线程中断
 *       （见 {@link WikiCleanupScope}）；LLM 记账元数据改用 {@link WikiLlmCallMetadata}。</li>
 * </ol>
 *
 * <h2>接缝</h2>
 * <ul>
 *   <li>{@link WikiIngestTaskHandler} —— 批次执行（Map → Reduce → finalize）的落点；</li>
 *   <li>{@link WikiDedupSupport} —— 抽取去重辅助函数的落点；</li>
 *   <li>{@code NewSlugFromCitation} / {@code ExtractedItem} 等共享类型 —— cite 侧复用；</li>
 *   <li>{@code WikiBatchContext} / {@code SlugUpdate} / {@code DocIngestResult} ——
 *       Map/Reduce 阶段的数据载体。</li>
 * </ul>
 */
@Service
public class WikiIngestService implements WikiIngestPort {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestService.class);

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 旧版默认索引导语；现有导语为空或等于它时按"首次生成"处理。 */
    static final String LEGACY_INDEX_PLACEHOLDER = "Wiki index - table of contents";

    // ═══════════════════════════════════════════════════════════════
    // 依赖
    // ═══════════════════════════════════════════════════════════════

    final WikiPageService wikiService;
    final TaskPendingOpsRepository pendingRepo;
    final ObjectProvider<TaskDeadLetterRepository> deadLetterRepo;
    final ObjectProvider<KnowledgeMapper> knowledgeMapper;
    final WikiSlugLock slugLock;
    final WikiInflightLimiter inflightLimiter;
    final ObjectProvider<WikiDeletedTombstoneStore> tombstoneStore;
    final ObjectProvider<WikiIngestTaskQueue> taskQueue;
    final ObjectProvider<WikiCrossLinker> crossLinker;
    final ObjectProvider<WikiDedupSupport> dedupSupport;
    final ObjectProvider<WikiKnowledgeFinalizer> knowledgeFinalizer;
    final ObjectProvider<WikiImageEnricher> imageEnricher;
    final ObjectProvider<WikiIngestTaskHandler> taskHandler;

    /**
     * Lite 模式下的按 KB 互斥容器。
     *
     * <p>Lite 判据见 {@link #isLiteMode()}（在途限流器为进程内实现）。
     * 由 {@link WikiIngestTaskHandler} 的实现在 {@code processWikiIngest} 里使用，
     * 本类提供容器与判据。</p>
     */
    final Set<String> liteLocks = ConcurrentHashMap.newKeySet();

    /**
     * Lite 模式下 finalize 活跃标记（{@code wiki:finalize:active:<kbID>}）的
     * 进程内对应物。
     */
    final Set<String> liteFinalizeLocks = ConcurrentHashMap.newKeySet();

    /**
     * 合并进程内<b>字节完全相同</b>的并发 prompt。
     */
    final SingleFlight llmRequests = new SingleFlight();

    /**
     * 只串行化同一个可复用 Wiki 页面
     * 前缀的<b>首个</b>请求；其它前缀与已经预热过的同类保持并行。
     */
    final ConcurrentHashMap<String, PromptWarmup> promptWarmups = new ConcurrentHashMap<>();

    /** 预热标记的回收器：预热完成后延迟数分钟移除标记（覆盖并行 reduce 突发，又不常驻缓存）。 */
    final java.util.concurrent.ScheduledExecutorService warmupReaper =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wiki-prompt-warmup-reaper");
                t.setDaemon(true);
                return t;
            });

    /** 一次预热的完成标记（done 完成信号 + closed 幂等关闭位） */
    static final class PromptWarmup {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean(false);
    }

    // ── seam 委托:实现随协作者(测试与 CitePipeline/Taxonomy 直引) ──

    public void sanitizeDeadSummaryLinks(String kbId, java.util.List<DocIngestResult> docResults,
            java.util.Set<String> failedAdditionSlugs, WikiBatchContext batchCtx) {
        pageOps.sanitizeDeadSummaryLinks(kbId, docResults, failedAdditionSlugs, batchCtx);
    }

    public void cleanDeadLinks(String kbId, java.util.List<String> affectedSlugs, WikiBatchContext batchCtx) {
        pageOps.cleanDeadLinks(kbId, affectedSlugs, batchCtx);
    }

    public void injectCrossLinks(String kbId, java.util.List<String> affectedSlugs,
            java.util.List<WikiCrossLinker.LinkRef> freshRefs, WikiBatchContext batchCtx) {
        pageOps.injectCrossLinks(kbId, affectedSlugs, freshRefs, batchCtx);
    }

    public com.ragagent.wiki.service.ingest.WikiIngestExtractDedup.ExtractedProjection deduplicateExtractedBatch(
            LlmChatClient chatModel, String kbId, java.util.List<ExtractedItem> entities,
            java.util.List<ExtractedItem> concepts, WikiBatchContext batchCtx) {
        return extractDedup.deduplicateExtractedBatch(chatModel, kbId, entities, concepts, batchCtx);
    }

    public static String formatExistingTaxonomyForPrompt(java.util.List<java.util.List<String>> paths) {
        return WikiIngestIndexOps.formatExistingTaxonomyForPrompt(paths);
    }

    public void publishDraftPages(String kbId, java.util.List<String> slugs) {
        indexOps.publishDraftPages(kbId, slugs);
    }

    public int promptWarmupCount() {
        return llm.promptWarmupCount();
    }

    static String goQuote(String s) {
        return WikiIngestExtractDedup.goQuote(s);
    }

    public Runnable awaitWikiPromptWarmup(String key) throws InterruptedException {
        return llm.awaitWikiPromptWarmup(key);
    }

    public java.util.Set<String> getExistingPageSlugsForKnowledge(String kbId, String knowledgeId) {
        return indexOps.getExistingPageSlugsForKnowledge(kbId, knowledgeId);
    }

    public String generateWithTemplate(LlmChatClient chatModel, String promptTpl,
            java.util.Map<String, String> vars) {
        return llm.generateWithTemplate(chatModel, promptTpl, vars);
    }

    public void rebuildIndexPage(LlmChatClient chatModel, WikiIngestPayload payload,
            String changeDesc, String lang, String customInstructions) {
        indexOps.rebuildIndexPage(chatModel, payload, changeDesc, lang, customInstructions);
    }

    /** 摄取阶段协作者(构造期装配)。 */
    final WikiIngestQueueOps queueOps;
    final WikiIngestEnqueueOps enqueueOps;
    final WikiIngestPageOps pageOps;
    final WikiIngestIndexOps indexOps;
    final WikiIngestExtractDedup extractDedup;
    final WikiIngestLlmSupport llm;

    public WikiIngestService(WikiPageService wikiService,
                             TaskPendingOpsRepository pendingRepo,
                             ObjectProvider<TaskDeadLetterRepository> deadLetterRepo,
                             ObjectProvider<KnowledgeMapper> knowledgeMapper,
                             WikiSlugLock slugLock,
                             WikiInflightLimiter inflightLimiter,
                             ObjectProvider<WikiDeletedTombstoneStore> tombstoneStore,
                             ObjectProvider<WikiIngestTaskQueue> taskQueue,
                             ObjectProvider<WikiCrossLinker> crossLinker,
                             ObjectProvider<WikiDedupSupport> dedupSupport,
                             ObjectProvider<WikiKnowledgeFinalizer> knowledgeFinalizer,
                             ObjectProvider<WikiImageEnricher> imageEnricher,
                             ObjectProvider<WikiIngestTaskHandler> taskHandler) {
        this.wikiService = wikiService;
        this.pendingRepo = pendingRepo;
        this.deadLetterRepo = deadLetterRepo;
        this.knowledgeMapper = knowledgeMapper;
        this.slugLock = slugLock;
        this.inflightLimiter = inflightLimiter;
        this.tombstoneStore = tombstoneStore;
        this.taskQueue = taskQueue;
        this.crossLinker = crossLinker;
        this.dedupSupport = dedupSupport;
        this.knowledgeFinalizer = knowledgeFinalizer;
        this.imageEnricher = imageEnricher;
        this.taskHandler = taskHandler;
        this.queueOps = new WikiIngestQueueOps(this);
        this.enqueueOps = new WikiIngestEnqueueOps(this);
        this.pageOps = new WikiIngestPageOps(this);
        this.indexOps = new WikiIngestIndexOps(this);
        this.extractDedup = new WikiIngestExtractDedup(this);
        this.llm = new WikiIngestLlmSupport(this);
    }

    // ═══════════════════════════════════════════════════════════════
    // 模式判定 / Lite 锁
    // ═══════════════════════════════════════════════════════════════

    /**
     * 是否处于 "Lite 模式"（没有跨进程协调）。
     *
     * <p>以"在途限流器是否为进程内实现"为判据——进程内实现意味着
     * 没有 Redis 级别的共享协调。</p>
     */
    public boolean isLiteMode() {
        return inflightLimiter instanceof InProcessWikiInflightLimiter;
    }

    /**
     * 尝试取得该 KB 的 Lite 模式独占许可。
     *
     * @return true = 取得（调用方必须配对调用 {@link #releaseLiteLock}）；
     *         false = 已有批次在跑（调用方返回
     *         {@link WikiIngestConstants.ConcurrentTaskActiveException}，
     *         让队列按 ErrWikiIngestConcurrent 的短延迟重试）
     */
    public boolean tryAcquireLiteLock(String kbId) {
        return liteLocks.add(kbId);
    }

    /** 释放该 KB 的 Lite 独占许可（批次退出时必须配对调用）。 */
    public void releaseLiteLock(String kbId) {
        liteLocks.remove(kbId);
    }

    /** finalize 的进程内互斥入口。 */
    public boolean tryAcquireLiteFinalizeLock(String kbId) {
        return liteFinalizeLocks.add(kbId);
    }

    /** 释放 finalize 的进程内锁。 */
    public void releaseLiteFinalizeLock(String kbId) {
        liteFinalizeLocks.remove(kbId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 任务分派
    // ═══════════════════════════════════════════════════════════════

    /**
     * 按任务类型分派。
     *
     * <p>队列（
     * {@link InProcessWikiIngestTaskQueue}）在投递时自己做同样的分派；
     * 本方法保留为显式的分派入口，供手工投递（测试、运维重放）使用。</p>
     *
     * @param taskType    {@link WikiIngestTask#TYPE_WIKI_INGEST} 或 {@code _FINALIZE}
     * @param payloadJson {@link WikiIngestPayload} 的 JSON
     */
    public void handle(String taskType, String payloadJson) {
        WikiIngestTaskHandler handler = taskHandler.getIfAvailable();
        if (handler == null) {
            log.warn("wiki ingest: no WikiIngestTaskHandler registered, dropping {} task", taskType);
            return;
        }
        WikiIngestPayload payload;
        try {
            payload = MAPPER.readValue(payloadJson, WikiIngestPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("wiki ingest: unmarshal payload: " + e.getMessage(), e);
        }
        if (WikiIngestTask.TYPE_WIKI_FINALIZE.equals(taskType)) {
            handler.processWikiFinalize(payload);
        } else {
            handler.processWikiIngest(payload);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 清理作用域
    // ═══════════════════════════════════════════════════════════════

    /**
     * 开一个脱钩的清理作用域。
     *
     * <p>用法：{@code try (var scope = cleanupScope()) { scope.run(...); }}</p>
     */
    public WikiCleanupScope cleanupScope() {
        return WikiCleanupScope.open();
    }

    /**
     * KB 已被删除时清掉它名下的全部待办 op。
     */
    public void clearDeletedKnowledgeBasePendingOps(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            return;
        }
        try (WikiCleanupScope scope = cleanupScope()) {
            scope.run(() -> pendingRepo.deleteByScope(WikiIngestConstants.TASK_SCOPE, kbId));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 投递 / Finalize 通道（实现移至 WikiIngestEnqueueOps，门面薄委托）
    // ═══════════════════════════════════════════════════════════════

    public boolean enqueueWikiPendingOp(TaskPendingOp op) {
        return enqueueOps.enqueueWikiPendingOp(op);
    }

    public WikiIngestPort.EnqueueResult enqueueWikiIngest(long tenantId, String kbId, String knowledgeId) {
        return enqueueOps.enqueueWikiIngest(tenantId, kbId, knowledgeId);
    }

    public TaskPendingOp newWikiIngestPendingOp(long tenantId, String kbId, String knowledgeId) throws Exception {
        return enqueueOps.newWikiIngestPendingOp(tenantId, kbId, knowledgeId);
    }

    public void enqueueWikiIngestTrigger(long tenantId, String kbId) {
        enqueueOps.enqueueWikiIngestTrigger(tenantId, kbId);
    }

    public void enqueueWikiRetract(WikiRetractPayload payload) {
        enqueueOps.enqueueWikiRetract(payload);
    }

    public void enqueueFinalize(WikiIngestPayload payload,
                                List<String> affectedSlugs,
                                Map<String, String> freshTitleBySlug,
                                List<WikiFinalizeChange> changes,
                                List<String> folderIds) {
        enqueueOps.enqueueFinalize(payload, affectedSlugs, freshTitleBySlug, changes, folderIds);
    }

    public static List<String> uniqueWikiFolderIDs(List<String> values) {
        return WikiIngestEnqueueOps.uniqueWikiFolderIDs(values);
    }

    public void scheduleFinalize(WikiIngestPayload payload) {
        enqueueOps.scheduleFinalize(payload);
    }

    public void scheduleFinalizeRetry(WikiIngestPayload payload) {
        enqueueOps.scheduleFinalizeRetry(payload);
    }

    public void scheduleCappedRetry(WikiIngestPayload payload) {
        enqueueOps.scheduleCappedRetry(payload);
    }

    public boolean scheduleStaleClaimRecheck(WikiIngestPayload payload) {
        return enqueueOps.scheduleStaleClaimRecheck(payload);
    }

    // ═══════════════════════════════════════════════════════════════
    // 队列消费（实现移至 WikiIngestQueueOps，门面薄委托）
    // ═══════════════════════════════════════════════════════════════

    public PendingBatch peekPendingList(String kbId, int limit) {
        return queueOps.peekPendingList(kbId, limit);
    }

    public PendingBatch claimPendingList(String kbId, int limit) {
        return queueOps.claimPendingList(kbId, limit);
    }

    public PendingBatch decodePendingRows(List<TaskPendingOp> rows) {
        return queueOps.decodePendingRows(rows);
    }

    public void trimPendingList(List<Long> ids) {
        queueOps.trimPendingList(ids);
    }

    public void trimPendingListDetached(List<Long> ids) {
        queueOps.trimPendingListDetached(ids);
    }



    // ═══════════════════════════════════════════════════════════════
    // 队列消费
    // ═══════════════════════════════════════════════════════════════

    /** 窥视/认领结果：解码后的 op 列表 + 被触及的原始行 id。 */
    public record PendingBatch(List<WikiPendingOp> ops, List<Long> peekedIds) {}

    // ═══════════════════════════════════════════════════════════════
    // 锁与限流
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把对<b>同一个共享 wiki 页面</b>的
     * 读-改-写串行化。
     *
     * <p>同一 KB 没有独占批次锁，两个批次可能并发，因此可能同时为同一个
     * 共享 entity/concept slug 产出更新；没有这把锁，它们的
     * {@code GetPageBySlug → UpdatePage} 循环会竞争并丢掉一份贡献。</p>
     *
     * <p><b>失败语义</b>：等待超时返回 false（调用方把该 slug 当作
     * 一次尽力而为的 reduce miss）；<b>协调层故障则 fail-open</b>（不加锁直接执行）
     * ——共享页面上罕见的一次丢失更新会被 finalize / 死链清理兜住，
     * 而静默丢弃更新严格来说更糟。</p>
     *
     * @return true = 已在锁内执行完 {@code fn}；false = 等待超时，<b>fn 未执行</b>
     */
    public boolean withSlugLock(String kbId, String slug, Runnable fn) {
        return slugLock.withSlugLock(kbId, slug, fn);
    }

    /**
     * 占用该 KB 的一个并发批次槽位。
     *
     * <p>{@code granted == false} 时调用方应调度 cap 重试并放弃本批次；
     * {@code granted == true} 时<b>必须</b>在批次结束时释放。</p>
     */
    public WikiInflightLimiter.Reservation reserveInflightSlot(String kbId, int maxInflight) {
        return inflightLimiter.reserve(kbId, maxInflight);
    }

    // ═══════════════════════════════════════════════════════════════
    // 失败结算
    // ═══════════════════════════════════════════════════════════════

    // 失败结算
    // ═══════════════════════════════════════════════════════════════

    /**
     * 该文档的 wiki op 到达终态
     * （成功映射或已进死信）时，释放它在 finalizing 计数里的槽位。
     *
     * <p>对应的 +1 是由 {@code KnowledgeProcessWorker} 在确定要生成 wiki 时
     * 晋升 finalizing 时播种的。<b>只能对 ingest op 调用</b>——
     * retract op 针对的是已删除的知识，没有计数器需要排空。</p>
     *
     * <p>对已完成、或计数已为 0 的行调用是安全的 no-op（递减与晋升都带条件守卫）。
     * 使用<b>脱钩的执行路径</b>：wiki 批次 worker
     * 可能正在关闭或父作用域已被取消，吞掉失败会把父文档永久留在 "finalizing"。</p>
     */
    public void finalizeWikiSubtask(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return;
        }
        WikiKnowledgeFinalizer finalizer = knowledgeFinalizer.getIfAvailable();
        if (finalizer == null) {
            // finalizer 未接线：跳过（测试/裁剪装配）
            log.debug("wiki ingest: knowledge finalizer not wired, skipping subtask finalize for {}",
                    knowledgeId);
            return;
        }
        try (WikiCleanupScope scope = cleanupScope()) {
            scope.run(() -> finalizer.finalizeWikiSubtask(knowledgeId));
        }
    }

    /**
     * 记录批内失败。
     *
     * <p>对每个失败的 op：</p>
     * <ul>
     *   <li>对源行 {@code IncrFailCount}。仓储返回新总数，因此一次往返同时完成记账与
     *       重试预算判定。</li>
     *   <li>计数 {@code <= MAX_FAIL_RETRIES}：把行留在原处。下一个后续批次的
     *       PeekBatch 会自然拾起它（行按 id ASC 排序，我们从没动过它），
     *       并释放认领让它立即可再次认领。</li>
     *   <li>计数超限：把 op 归档到 {@code task_dead_letters} 并 {@code DeleteByIDs}
     *       把它从队列里移除。</li>
     * </ul>
     *
     * <p><b>返回结算错误列表</b>（返回列表比只留第一个更能暴露问题）。
     * 调用方在列表非空时不应把认领标记为"已结算"——行还在被认领或未被删除。</p>
     */
    public List<Exception> requeueFailedOps(WikiIngestPayload payload, List<WikiPendingOp> ops) {
        List<Exception> settleErrors = new ArrayList<>();
        if (ops == null || ops.isEmpty()) {
            return settleErrors;
        }
        TaskDeadLetterRepository deadLetters = deadLetterRepo.getIfAvailable();

        for (WikiPendingOp op : ops) {
            if (op.getDbId() == 0) {
                // op 从未被持久化（合成 / 测试）—— 没有可重试的对象
                continue;
            }
            int count;
            try {
                count = pendingRepo.incrFailCount(op.getDbId());
            } catch (Exception e) {
                log.warn("wiki ingest: failed to increment fail count for {} (id={}): {}",
                        op.getKnowledgeId(), op.getDbId(), e.getMessage());
                settleErrors.add(new IllegalStateException(
                        "increment fail count id=" + op.getDbId() + ": " + e.getMessage(), e));
                // 拿不到新计数就无法判断该不该丢弃。保守处理：把行留在原处，
                // 下一次 PeekBatch 还会看到它，我们再试一次。
                continue;
            }
            if (count <= WikiIngestConstants.MAX_FAIL_RETRIES) {
                // 释放认领，让该行立刻可被下一个触发的 ClaimBatch 认领，
                // 而不必等 CLAIM_STALE_AFTER 过去。Lite 模式下是 no-op
                // （行只被窥视、从未被认领）。ReleaseByIDs 保留 fail_count，
                // 因此重试预算仍在递减。
                try {
                    pendingRepo.releaseByIds(List.of(op.getDbId()));
                } catch (Exception e) {
                    log.warn("wiki ingest: failed to release claim for retry id={}: {}",
                            op.getDbId(), e.getMessage());
                    settleErrors.add(new IllegalStateException(
                            "release retry claim id=" + op.getDbId() + ": " + e.getMessage(), e));
                }
                log.info("wiki ingest: re-queued failed op {} ({}) for retry (attempt {}/{})",
                        op.getKnowledgeId(), op.docTitleOrEmpty(),
                        count, WikiIngestConstants.MAX_FAIL_RETRIES);
                continue;
            }

            // 批内重试已耗尽 —— 归档并移除。这是该 op 的终态失败点，
            // 因此释放它在文档 finalizing 计数里的槽位（只对 ingest op；
            // retract 针对的是已删除的知识，没有计数器要排空）。
            if (op.isIngest()) {
                finalizeWikiSubtask(op.getKnowledgeId());
            }
            log.warn("wiki ingest: dropping op {} ({}) after {} failures (limit {})",
                    op.getKnowledgeId(), op.docTitleOrEmpty(),
                    count, WikiIngestConstants.MAX_FAIL_RETRIES);
            if (deadLetters != null) {
                try {
                    TaskDeadLetter dl = new TaskDeadLetter();
                    dl.setTenantId(payload.tenantId());
                    dl.setTaskType(WikiIngestConstants.TASK_TYPE);
                    dl.setScope(WikiIngestConstants.TASK_SCOPE);
                    dl.setScopeId(payload.knowledgeBaseId());
                    dl.setRelatedId(op.getKnowledgeId());
                    dl.setPayload(MAPPER.valueToTree(op));
                    dl.setLastError("exceeded wikiMaxFailRetries="
                            + WikiIngestConstants.MAX_FAIL_RETRIES + " (in-batch retries)");
                    dl.setFailCount(count);
                    deadLetters.insert(dl);
                } catch (Exception e) {
                    log.warn("wiki ingest: failed to archive op {} to dead letters: {}",
                            op.getKnowledgeId(), e.getMessage());
                    settleErrors.add(new IllegalStateException(
                            "archive dead letter id=" + op.getDbId() + ": " + e.getMessage(), e));
                }
            }
            try {
                pendingRepo.deleteByIds(List.of(op.getDbId()));
            } catch (Exception e) {
                log.warn("wiki ingest: failed to drop dead-lettered row id={}: {}",
                        op.getDbId(), e.getMessage());
                settleErrors.add(new IllegalStateException(
                        "drop dead-lettered row id=" + op.getDbId() + ": " + e.getMessage(), e));
            }
        }
        return settleErrors;
    }

    /**
     * 在脱钩路径上结算失败——批次超时/被取消时
     * 仍必须把失败记账与归档做完。
     */
    public List<Exception> requeueFailedOpsDetached(WikiIngestPayload payload, List<WikiPendingOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return List.of();
        }
        java.util.concurrent.atomic.AtomicReference<List<Exception>> holder =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        try (WikiCleanupScope scope = cleanupScope()) {
            scope.run(() -> holder.set(requeueFailedOps(payload, ops)));
        }
        return holder.get();
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    /**
     * 知识文档是否已删除、或正在删除中。
     *
     * <p>先查墓碑快路径，再回落数据库。{@code getKnowledgeByIDOnly} 返回 null 同样算
     * "已消失"：仓储层查询会过滤软删行，因此软删的知识在这里表现为
     * "查不到"——正是我们要的。</p>
     */
    public boolean isKnowledgeGone(String kbId, String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return true;
        }
        WikiDeletedTombstoneStore tombstones = tombstoneStore.getIfAvailable();
        if (tombstones != null && tombstones.exists(kbId, knowledgeId)) {
            return true;
        }
        KnowledgeMapper mapper = knowledgeMapper.getIfAvailable();
        if (mapper == null) {
            // 无知识仓储可查（装配裁剪）—— 无法判定，保守地当作"还在"
            return false;
        }
        Knowledge kn;
        try {
            kn = mapper.selectOne(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getId, knowledgeId)
                    .isNull(Knowledge::getDeletedAt));
        } catch (Exception e) {
            return true;
        }
        if (kn == null) {
            return true;
        }
        return Knowledge.PARSE_DELETING.equals(kn.getParseStatus())
                || Knowledge.PARSE_CANCELLED.equals(kn.getParseStatus());
    }

    /**
     * 丢弃那些源知识在 Map 阶段结束后
     * 已被删除的新增 / 摘要更新。<b>retract 更新被保留</b>，页面因此仍能得到清理。
     * 按知识缓存判定结果，避免单个 reduce slug 携带同一文档的多个更新时反复打库。
     */
    public List<SlugUpdate> filterLiveUpdates(String kbId, List<SlugUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return updates;
        }
        Map<String, Boolean> goneCache = new java.util.HashMap<>();
        List<SlugUpdate> filtered = new ArrayList<>(updates.size());
        int dropped = 0;
        for (SlugUpdate u : updates) {
            if (u.isRetractType()) {
                filtered.add(u);
                continue;
            }
            String kid = u.getKnowledgeId();
            boolean gone = false;
            if (!kid.isEmpty()) {
                gone = goneCache.computeIfAbsent(kid, k -> isKnowledgeGone(kbId, k));
            }
            if (gone) {
                dropped++;
                continue;
            }
            filtered.add(u);
        }
        if (dropped > 0) {
            log.info("wiki ingest: reduce dropped {} updates for deleted knowledge(s)", dropped);
        }
        return filtered;
    }

    /**
     * 从 chunk 重建文档正文。
     *
     * <p><b>只拼接文本类型的 chunk</b>——图片 OCR / caption 信息存在
     * {@code image_ocr} / {@code image_caption} 子 chunk 上，不在父文本 chunk 的
     * ImageInfo 字段里。需要完整富化正文（内联 OCR / caption）的调用方应改用
     * {@link #reconstructEnrichedContent}。</p>
     *
     * <p>重叠去重与排序统一交给 {@link WikiChunkMerge}（按文本匹配，
     * 兼容补写表头 / HTML 实体）。</p>
     */
    public static String reconstructContent(List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        List<Chunk> textChunks = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            if (c == null) {
                continue;
            }
            String type = c.getChunkType();
            if (CHUNK_TYPE_TEXT.equals(type) || type == null || type.isEmpty()) {
                textChunks.add(c);
            }
        }
        return WikiChunkMerge.mergeTextChunks(textChunks, "\n");
    }

    /** 文本类型的 chunk type 取值 */
    public static final String CHUNK_TYPE_TEXT = "text";

    /** 图片 OCR 子 chunk 的 chunk type 取值 */
    public static final String CHUNK_TYPE_IMAGE_OCR = "image_ocr";

    /** 图片 caption 子 chunk 的 chunk type 取值 */
    public static final String CHUNK_TYPE_IMAGE_CAPTION = "image_caption";

    /**
     * 重建正文并把
     * 图片的 OCR / caption 文本内联进来。
     *
     * <p>没有图片信息时返回纯文本重建结果——与富化器缺席时的退化路径一致。</p>
     */
    public String reconstructEnrichedContent(List<Chunk> chunks, long tenantId) {
        String content = reconstructContent(chunks);
        if (chunks == null || chunks.isEmpty() || content.isEmpty()) {
            return content;
        }
        List<Chunk> textChunks = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            if (c == null) {
                continue;
            }
            String type = c.getChunkType();
            if (CHUNK_TYPE_TEXT.equals(type) || type == null || type.isEmpty()) {
                textChunks.add(c);
            }
        }
        if (textChunks.isEmpty()) {
            return content;
        }
        WikiImageEnricher enricher = imageEnricher.getIfAvailable(WikiImageEnricher::identity);
        return enricher.enrich(content, textChunks, tenantId);
    }

    /**
     * 为该文档在知识追踪树下开一个
     * {@code postprocess.wiki} 子 span。
     *
     * <p>已接线（2026-09-24）：真实实现在 {@link WikiBatchSupport.WikiSpans#beginWikiSubspan}，
     * batch 通过注入的 {@link com.ragagent.knowledge.service.SpanTracker} 上报；
     * 本方法保留仅为兼容可能的旧调用点（当前无调用者）。</p>
     */
    public Object beginWikiSubspan(String knowledgeId, Map<String, Object> input) {
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════════════


    /** 供可观测/测试：当前在飞行的 LLM 请求数 */
    public int inflightLlmRequests() {
        return llmRequests.inflightCount();
    }
}
