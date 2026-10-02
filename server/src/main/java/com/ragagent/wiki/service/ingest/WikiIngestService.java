package com.ragagent.wiki.service.ingest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.mapper.TaskDeadLetterRepository;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
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
    final WikiIngestContentSupport contentOps;
    final WikiIngestSettleOps settleOps;
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
        this.contentOps = new WikiIngestContentSupport(this);
        this.settleOps = new WikiIngestSettleOps(this);
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

    /** 队列侧任务级重试耗尽后的槽位收尾（B12）——见 {@code WikiIngestSettleOps#releaseSlotsForAbandonedTask}。 */
    public void releaseSlotsForAbandonedTask(String kbId) {
        settleOps.releaseSlotsForAbandonedTask(kbId);
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
    // 失败结算（实现移至 WikiIngestSettleOps，门面薄委托）
    // ═══════════════════════════════════════════════════════════════

    public void finalizeWikiSubtask(String knowledgeId) {
        settleOps.finalizeWikiSubtask(knowledgeId);
    }

    public List<Exception> requeueFailedOps(WikiIngestPayload payload, List<WikiPendingOp> ops) {
        return settleOps.requeueFailedOps(payload, ops);
    }

    public List<Exception> requeueFailedOpsDetached(WikiIngestPayload payload, List<WikiPendingOp> ops) {
        return settleOps.requeueFailedOpsDetached(payload, ops);
    }

    // ═══════════════════════════════════════════════════════════════
    // 文档存活与正文重建（实现移至 WikiIngestContentSupport，门面薄委托）
    // ═══════════════════════════════════════════════════════════════

    public boolean isKnowledgeGone(String kbId, String knowledgeId) {
        return contentOps.isKnowledgeGone(kbId, knowledgeId);
    }

    public List<SlugUpdate> filterLiveUpdates(String kbId, List<SlugUpdate> updates) {
        return contentOps.filterLiveUpdates(kbId, updates);
    }

    public static String reconstructContent(List<Chunk> chunks) {
        return WikiIngestContentSupport.reconstructContent(chunks);
    }

    public String reconstructEnrichedContent(List<Chunk> chunks, long tenantId) {
        return contentOps.reconstructEnrichedContent(chunks, tenantId);
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




    // CHUNK_TYPE_* 常量留在门面：WikiIngestBatchHandler 按类名直引

    /** 文本类型的 chunk type 取值 */
    public static final String CHUNK_TYPE_TEXT = "text";

    /** 图片 OCR 子 chunk 的 chunk type 取值 */
    public static final String CHUNK_TYPE_IMAGE_OCR = "image_ocr";

    /** 图片 caption 子 chunk 的 chunk type 取值 */
    public static final String CHUNK_TYPE_IMAGE_CAPTION = "image_caption";



    /** 供可观测/测试：当前在飞行的 LLM 请求数 */
    public int inflightLlmRequests() {
        return llmRequests.inflightCount();
    }
}
