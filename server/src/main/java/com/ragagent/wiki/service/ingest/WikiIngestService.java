package com.ragagent.wiki.service.ingest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.ragagent.common.wiki.GoStrings;
import com.ragagent.common.wiki.WikiLanguageSupport;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    // 投递：ingest / retract
    // ═══════════════════════════════════════════════════════════════

    // 投递：ingest / retract
    // ═══════════════════════════════════════════════════════════════

    /** 入队结果：accepted = 待办 op 是否已持久化。 */

    /**
     * 把 op 持久化到
     * {@code task_pending_ops}。
     *
     * <p>设计上有原子守卫（"KB 仍活跃才入队"），但该守卫尚未接线
     * （它属于 knowledge 模块的删除路径），因此当前是普通入队。</p>
     */
    public boolean enqueueWikiPendingOp(TaskPendingOp op) {
        if (pendingRepo == null) {
            // pendingRepo 缺席：直接视为已接受
            return true;
        }
        pendingRepo.enqueue(op);
        return true;
    }

    /**
     * 把一篇文档排进 wiki 队列，
     * 并调度一个防抖的触发任务。
     *
     * <p>架构：每次上传往 {@code task_pending_ops} 插一行
     * （{@code task_type="wiki:ingest"}, {@code scope="knowledge_base"},
     * {@code scope_id=kbID}, {@code dedup_key=knowledgeID}），然后调度一个防抖的
     * 触发任务。触发落地时 worker 从 {@code task_pending_ops} 窥视一批、处理、
     * 删除已消费的行，若还有剩余则再排一次后续。窗口内（30 秒）的多个防抖触发
     * 全部合并：第一个拿到按 KB 许可的排空批次，后面的看到空队列即退出。</p>
     *
     * @return {@code accepted} = 待办 op 已持久化；{@code error} = 触发调度错误。
     *         <b>触发错误可能与 accepted=true 同时返回</b>，
     *         调用方可以只重试 KB 级的触发而不追加重复的操作。
     */
    public WikiIngestPort.EnqueueResult enqueueWikiIngest(long tenantId, String kbId, String knowledgeId) {
        TaskPendingOp op;
        try {
            op = newWikiIngestPendingOp(tenantId, kbId, knowledgeId);
        } catch (Exception e) {
            log.warn("wiki ingest: failed to marshal pending op for {}: {}", knowledgeId, e.getMessage());
            return new WikiIngestPort.EnqueueResult(false, e);
        }
        boolean accepted;
        try {
            accepted = enqueueWikiPendingOp(op);
        } catch (Exception e) {
            log.warn("wiki ingest: failed to enqueue pending op for {}: {}", knowledgeId, e.getMessage());
            return new WikiIngestPort.EnqueueResult(false, e);
        }
        if (!accepted) {
            log.info("wiki ingest: skip enqueue for deleted KB {}", kbId);
            return new WikiIngestPort.EnqueueResult(false, null);
        }
        try {
            enqueueWikiIngestTrigger(tenantId, kbId);
        } catch (Exception e) {
            return new WikiIngestPort.EnqueueResult(true, e);
        }
        return new WikiIngestPort.EnqueueResult(true, null);
    }

    /**
     * 构造 ingest 待办行。
     *
     * <p><b>语言必须在这里落定</b>（有回归测试钉住）：
     * wiki 工作会从后台路径（克隆/移动、重解析、内部重试）入队，而那些路径<b>从不</b>
     * 经过 HTTP 语言中间件。在那里持久化一个空 locale 会让整篇文档的语言丢失，
     * 因为 worker 是从排队的 op 解析 prompt 语言的。</p>
     */
    public TaskPendingOp newWikiIngestPendingOp(long tenantId, String kbId, String knowledgeId) throws Exception {
        WikiPendingOp op = new WikiPendingOp(WikiIngestConstants.OP_INGEST, knowledgeId);
        op.setLanguage(WikiLanguageSupport.languageFromContextOrDefault());

        TaskPendingOp row = new TaskPendingOp();
        row.setTenantId(tenantId);
        row.setTaskType(WikiIngestConstants.TASK_TYPE);
        row.setScope(WikiIngestConstants.TASK_SCOPE);
        row.setScopeId(kbId);
        row.setOp(WikiIngestConstants.OP_INGEST);
        row.setDedupKey(knowledgeId);
        row.setPayload(MAPPER.valueToTree(op));
        return row;
    }

    /**
     * 调度防抖的批次触发。
     *
     * <p>任务参数：MaxRetry 10、Timeout 60 分钟、ProcessIn 30 秒
     * （{@link WikiIngestConstants#INGEST_DELAY}）。</p>
     */
    public void enqueueWikiIngestTrigger(long tenantId, String kbId) {
        // 入队侧注入：把当前
        // 请求的 traceparent 打进负载，worker 侧续接同一棵树
        WikiIngestPayload trigger = WikiIngestPayload.withTracing(
                tenantId, kbId, WikiLanguageSupport.languageFromContextOrDefault(),
                com.ragagent.tracing.langfuse.LangfuseTracing.inject());
        WikiIngestTaskQueue queue = taskQueue.getIfAvailable();
        if (queue == null) {
            throw new IllegalStateException("enqueue wiki ingest trigger: task queue is not wired");
        }
        boolean accepted = queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_INGEST,
                toJson(trigger),
                WikiIngestConstants.INGEST_DELAY,
                WikiIngestConstants.INGEST_MAX_RETRY,
                Duration.ofMinutes(60),
                ""));
        if (!accepted) {
            log.debug("wiki ingest: trigger coalesced for KB {}", kbId);
        }
    }

    /**
     * 排一次撤回（删除清理）。
     *
     * <p>持久化模型与 {@code EnqueueWikiIngest} 完全相同——op 坐在
     * {@code task_pending_ops} 里，一个触发稍后处理该批次。撤回用的 ProcessIn 稍短
     * （5 秒）：删除没有"用户上传成波到来"的模式需要防抖，它只发生一次，我们希望清理
     * 尽快落地。</p>
     */
    public void enqueueWikiRetract(WikiRetractPayload payload) {
        try {
            enqueueWikiRetractInternal(payload);
        } catch (Exception e) {
            log.warn("wiki retract: enqueue failed", e);
        }
    }

    /** 撤回入队实现。 */
    private void enqueueWikiRetractInternal(WikiRetractPayload payload) throws Exception {
        WikiPendingOp op = new WikiPendingOp(WikiIngestConstants.OP_RETRACT, payload.knowledgeId());
        op.setDocTitle(payload.docTitle());
        op.setDocSummary(payload.docSummary());
        op.setPageSlugs(payload.pageSlugs());
        op.setFolderIds(payload.folderIds());
        op.setLanguage(payload.language());

        TaskPendingOp row = new TaskPendingOp();
        row.setTenantId(payload.tenantId());
        row.setTaskType(WikiIngestConstants.TASK_TYPE);
        row.setScope(WikiIngestConstants.TASK_SCOPE);
        row.setScopeId(payload.knowledgeBaseId());
        row.setOp(WikiIngestConstants.OP_RETRACT);
        row.setDedupKey(payload.knowledgeId());
        row.setPayload(MAPPER.valueToTree(op));

        boolean accepted = enqueueWikiPendingOp(row);
        if (!accepted) {
            log.info("wiki retract: skip enqueue for deleted KB {}", payload.knowledgeBaseId());
            return;
        }

        WikiIngestPayload trigger = WikiIngestPayload.withTracing(
                payload.tenantId(), payload.knowledgeBaseId(), payload.language(),
                com.ragagent.tracing.langfuse.LangfuseTracing.inject());
        WikiIngestTaskQueue queue = taskQueue.getIfAvailable();
        if (queue == null) {
            throw new IllegalStateException("wiki retract: task queue is not wired");
        }
        queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_INGEST,
                toJson(trigger),
                Duration.ofSeconds(5), // 撤回可以很快触发批次
                WikiIngestConstants.INGEST_MAX_RETRY,
                Duration.ofMinutes(60),
                ""));
    }

    // ═══════════════════════════════════════════════════════════════
    // Finalize 通道
    // ═══════════════════════════════════════════════════════════════

    /** 单行 finalize 入队，失败只记 WARN。 */
    private boolean enqueueFinalizeRow(TaskPendingOp op) {
        try {
            return enqueueWikiPendingOp(op);
        } catch (Exception e) {
            log.warn("wiki finalize: enqueue {} row failed: {}", op.getOp(), e.getMessage());
            return false;
        }
    }

    /**
     * 把本批次的 KB 级收敛工作持久化进
     * finalize 通道，并调度一个防抖触发。
     *
     * <p>每个受影响页面一行 {@code "slug"}（本批次写过则带上新 title，供交叉链接用），
     * 每个增/删文档一行 {@code "change"}（供索引导语的变更描述）。</p>
     */
    public void enqueueFinalize(WikiIngestPayload payload,
                                List<String> affectedSlugs,
                                Map<String, String> freshTitleBySlug,
                                List<WikiFinalizeChange> changes,
                                List<String> folderIds) {
        if (pendingRepo == null) {
            // 没有持久化队列就没有 finalize 工作可记
            return;
        }
        boolean acceptedAny = false;

        if (affectedSlugs != null) {
            for (String slug : affectedSlugs) {
                WikiFinalizeRow row = WikiFinalizeRow.slug(
                        slug, freshTitleBySlug == null ? null : freshTitleBySlug.get(slug));
                acceptedAny |= enqueueFinalizeRow(finalizeRow(
                        payload, WikiIngestConstants.FINALIZE_OP_SLUG, slug, row));
            }
        }
        if (changes != null) {
            for (WikiFinalizeChange change : changes) {
                WikiFinalizeRow row = WikiFinalizeRow.change(change);
                acceptedAny |= enqueueFinalizeRow(finalizeRow(
                        payload, WikiIngestConstants.FINALIZE_OP_CHANGE, "", row));
            }
        }
        if (folderIds != null && !folderIds.isEmpty()) {
            WikiFinalizeRow row = WikiFinalizeRow.folderIds(uniqueWikiFolderIDs(folderIds));
            acceptedAny |= enqueueFinalizeRow(finalizeRow(
                    payload, WikiIngestConstants.FINALIZE_OP_FOLDER_PRUNE, "", row));
        }
        if (!acceptedAny) {
            return;
        }
        scheduleFinalize(payload);
    }

    private TaskPendingOp finalizeRow(WikiIngestPayload payload, String op, String dedupKey,
                                      WikiFinalizeRow row) {
        TaskPendingOp entity = new TaskPendingOp();
        entity.setTenantId(payload.tenantId());
        entity.setTaskType(WikiIngestConstants.FINALIZE_TASK_TYPE);
        entity.setScope(WikiIngestConstants.TASK_SCOPE);
        entity.setScopeId(payload.knowledgeBaseId());
        entity.setOp(op);
        entity.setDedupKey(dedupKey == null ? "" : dedupKey);
        entity.setPayload(MAPPER.valueToTree(row));
        return entity;
    }

    /**
     * 去空白、去重、保序。
     */
    public static List<String> uniqueWikiFolderIDs(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            String trimmed = GoStrings.trimSpace(value == null ? "" : value);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!seen.add(trimmed)) {
                continue;
            }
            out.add(trimmed);
        }
        return out;
    }

    /**
     * 调度一个防抖、可合并的
     * KB 级 finalize 触发。
     *
     * <p>稳定 TaskID（{@code wiki-finalize-<kbID>}）让防抖窗口内的并发调度坍缩成
     * 一个待执行任务；<b>冲突不是失败，而是预期的合并信号</b>。Lite 模式下
     * TaskID 被忽略，因此 finalize 每批次跑一次
     * ——在 Lite 面向的小规模下可以接受。</p>
     */
    public void scheduleFinalize(WikiIngestPayload payload) {
        WikiIngestTaskQueue queue = taskQueue.getIfAvailable();
        if (queue == null) {
            return;
        }
        boolean accepted = queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_FINALIZE,
                toJson(payload),
                WikiIngestConstants.FINALIZE_DELAY,
                WikiIngestConstants.INGEST_MAX_RETRY,
                Duration.ofMinutes(30),
                WikiIngestConstants.finalizeTaskId(payload.knowledgeBaseId())));
        if (!accepted) {
            return; // 该 KB 已有 finalize 在排队/运行 —— 已合并
        }
    }

    /**
     * 目录剪枝还在等 ingest 行排空
     * 时使用。<b>刻意不带稳定的 TaskID</b>：当前正在跑的 finalize 任务仍占着那个 ID，
     * 这里复用它会把唯一的重试合并掉。重复的重试是无害的——持久化的 prune 行只会被
     * 删除一次，而空的通道是 no-op。
     */
    public void scheduleFinalizeRetry(WikiIngestPayload payload) {
        WikiIngestTaskQueue queue = taskQueue.getIfAvailable();
        if (queue == null) {
            return;
        }
        queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_FINALIZE,
                toJson(payload),
                WikiIngestConstants.FOLDER_PRUNE_RETRY_DELAY,
                WikiIngestConstants.INGEST_MAX_RETRY,
                Duration.ofMinutes(30),
                ""));
    }

    /**
     * 批次被在途上限挡回后，
     * 排一个<b>合并的</b>后续触发。
     *
     * <p>TaskID 把某个 KB 所有被挡回的触发坍缩成单个待执行重试（无惊群），
     * 而持槽位的运行中批次在完成时也会链上它们自己的后续，因此被挡回的行
     * 保证会在槽位释放后得到处理。</p>
     */
    public void scheduleCappedRetry(WikiIngestPayload payload) {
        WikiIngestTaskQueue queue = taskQueue.getIfAvailable();
        if (queue == null) {
            return;
        }
        boolean accepted = queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_INGEST,
                toJson(payload),
                WikiIngestConstants.INFLIGHT_BACKOFF,
                WikiIngestConstants.INGEST_MAX_RETRY,
                Duration.ofMinutes(60),
                WikiIngestConstants.cappedRetryTaskId(payload.knowledgeBaseId())));
        if (!accepted) {
            return; // 已有 cap 重试在排队 —— 已合并
        }
    }

    /**
     * 为一个"仍有待办行、
     * 却什么都认领不到"（所有合格行都被<b>新鲜</b>认领持有）的 KB 布下单个、远期的
     * 安全网触发。
     *
     * <p>正常情况下运行中的批次会排空那些行、并在完成时链上自己的快速后续；
     * 这张网只针对<b>认领持有者崩溃</b>的情形——此时 {@code claimed_at} 已盖戳，
     * 在 {@code CLAIM_STALE_AFTER} 过去之前没有 worker 能重新认领，而且此后也不会
     * 有任何东西再去触发这个 KB。</p>
     *
     * <p>延迟设在陈旧阈值之后，保证网触发时被遗弃的认领必然已重新合格。
     * TaskID 让一个 KB 的所有重检合并成单张网（并发空转批次不会造成惊群）。
     * {@code PendingCount} 已经为 0 说明该 KB 完全排空，不需要网。</p>
     *
     * @return 网是否（已经）布下
     */
    public boolean scheduleStaleClaimRecheck(WikiIngestPayload payload) {
        long count;
        try {
            count = pendingRepo.pendingCount(
                    WikiIngestConstants.TASK_TYPE,
                    WikiIngestConstants.TASK_SCOPE,
                    payload.knowledgeBaseId());
        } catch (Exception e) {
            return false;
        }
        if (count == 0) {
            return false;
        }
        log.info("wiki ingest: {} rows for KB {} held by fresh claims, arming stale-claim recheck",
                count, payload.knowledgeBaseId());

        WikiIngestTaskQueue queue = taskQueue.getIfAvailable();
        if (queue == null) {
            return false;
        }
        boolean accepted = queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_INGEST,
                toJson(payload),
                WikiIngestConstants.CLAIM_STALE_AFTER.plus(WikiIngestConstants.FOLLOW_UP_DELAY),
                WikiIngestConstants.INGEST_MAX_RETRY,
                Duration.ofMinutes(60),
                WikiIngestConstants.staleClaimRecheckTaskId(payload.knowledgeBaseId())));
        if (!accepted) {
            return true; // 已有重检在布防 —— 已合并
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // 队列消费
    // ═══════════════════════════════════════════════════════════════

    /** 窥视/认领结果：解码后的 op 列表 + 被触及的原始行 id。 */
    public record PendingBatch(List<WikiPendingOp> ops, List<Long> peekedIds) {}

    /**
     * 为该 KB 按 FIFO 载入最多
     * {@code limit} 条 op。<b>行不会被移除</b>；消费后必须
     * {@code DeleteByIDs}（或 {@code IncrFailCount} 后留着给下一轮）。
     *
     * <p>{@code peekedIds} 返回被窥视到的<b>每一行</b>的 db id（不只是通过去重的那些），
     * 好让 {@code trimPendingList} 在批次末尾一条语句删光——这对应历史的
     * "LTrim peekedCount 条"语义：被消费者按 dedup 折叠掉的重复行，
     * 也在其规范兄弟被处理之后一并排空。</p>
     */
    public PendingBatch peekPendingList(String kbId, int limit) {
        int effective = limit <= 0 ? WikiIngestConstants.MAX_DOCS_PER_BATCH : limit;
        List<TaskPendingOp> rows = pendingRepo.peekBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, kbId, effective);
        return decodePendingRows(rows);
    }

    /**
     * {@code peekPendingList} 在
     * standard（分布式协调）模式下的对应物——原子地<b>认领</b>最多 {@code limit}
     * 条 op（标记 {@code claimed_at}），让同一 KB 的并发批次拉到<b>互不相交</b>的
     * 文档而不是重复处理。
     * 陈旧认领（早于 {@code CLAIM_STALE_AFTER}，即来自崩溃 worker）会被回收。
     *
     * <p>去重 / peekedIds 语义与 {@code peekPendingList} 相同；返回的 peekedIds 是
     * 已认领、调用方必须在成功时 {@code DeleteByIDs} 或失败时 {@code ReleaseByIDs}
     * 的那些行。</p>
     */
    public PendingBatch claimPendingList(String kbId, int limit) {
        int effective = limit <= 0 ? WikiIngestConstants.MAX_DOCS_PER_BATCH : limit;
        java.time.OffsetDateTime staleBefore =
                java.time.OffsetDateTime.now().minus(WikiIngestConstants.CLAIM_STALE_AFTER);
        List<TaskPendingOp> rows = pendingRepo.claimBatch(
                WikiIngestConstants.TASK_TYPE, WikiIngestConstants.TASK_SCOPE, kbId, effective, staleBefore);
        return decodePendingRows(rows);
    }

    /**
     * 把原始行转成
     * {@link WikiPendingOp}，并按 knowledge_id 施加 last-write-wins 去重。
     *
     * <p>去重只保留每篇文档<b>最后</b>一个操作，从而优化掉冗余序列
     * （例如"刚上传就删除"：{@code [ingest, retract]} → {@code [retract]}）。
     * 非规范行仍会在 trim 时被排空——它们的 dbID 就在 peekedIDs 里。</p>
     */
    public PendingBatch decodePendingRows(List<TaskPendingOp> rows) {
        if (rows == null || rows.isEmpty()) {
            return new PendingBatch(List.of(), List.of());
        }
        List<WikiPendingOp> all = new ArrayList<>(rows.size());
        List<Long> peekedIds = new ArrayList<>(rows.size());
        for (TaskPendingOp r : rows) {
            peekedIds.add(r.getId());
            WikiPendingOp op;
            JsonNode payload = r.getPayload();
            if (payload != null) {
                try {
                    op = MAPPER.treeToValue(payload, WikiPendingOp.class);
                    if (op == null) {
                        op = new WikiPendingOp();
                    }
                } catch (Exception e) {
                    log.warn("wiki ingest: failed to unmarshal pending op id={}: {}",
                            r.getId(), e.getMessage());
                    continue;
                }
            } else {
                // 防御：载荷丢失时回落到列数据，让该行仍可被排空
                // （否则它会每批次都因"删不掉"而空转）
                op = new WikiPendingOp(r.getOp(), r.getDedupKey());
            }
            op.setDbId(r.getId());
            all.add(op);
        }

        Set<String> seen = new HashSet<>();
        List<WikiPendingOp> reversedUnique = new ArrayList<>(all.size());
        for (int i = all.size() - 1; i >= 0; i--) {
            WikiPendingOp op = all.get(i);
            if (op.getKnowledgeId().isEmpty()) {
                // 没有去重键 —— 原样保留（罕见；留给未来没有知识锚点的 op）
                reversedUnique.add(op);
                continue;
            }
            if (!seen.add(op.getKnowledgeId())) {
                continue;
            }
            reversedUnique.add(op);
        }

        List<WikiPendingOp> ops = new ArrayList<>(reversedUnique.size());
        for (int i = reversedUnique.size() - 1; i >= 0; i--) {
            ops.add(reversedUnique.get(i));
        }
        return new PendingBatch(ops, peekedIds);
    }

    /**
     * 删除已消费的行。
     * 空入参是 no-op，因此调用方可以在批次结束时无条件调用。
     *
     * @throws RuntimeException 删除失败；调用方据此让批次结算失败
     */
    public void trimPendingList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            pendingRepo.deleteByIds(ids);
        } catch (RuntimeException e) {
            log.warn("wiki ingest: failed to trim {} pending rows: {}", ids.size(), e.getMessage());
            throw e;
        }
    }

    /**
     * 在<b>脱钩</b>的清理路径上删除已消费的行——父作用域已被取消/中断时
     * 删除仍要执行（有回归测试覆盖该行为）。
     */
    public void trimPendingListDetached(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try (WikiCleanupScope scope = cleanupScope()) {
            scope.run(() -> trimPendingList(ids));
        }
    }

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

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize wiki payload", e);
        }
    }

    /** 供可观测/测试：当前在飞行的 LLM 请求数 */
    public int inflightLlmRequests() {
        return llmRequests.inflightCount();
    }
}
