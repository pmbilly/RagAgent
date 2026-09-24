package com.ragagent.wiki.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.chat.PromptCache;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.wiki.domain.TaskDeadLetter;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiIndexEntry;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.mapper.TaskDeadLetterRepository;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
import com.ragagent.wiki.prompt.WikiPromptTemplate;
import com.ragagent.wiki.prompt.WikiPrompts;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * wiki 生成管线的主入口（对照 Go internal/application/service/wiki_ingest.go，3246 行）。
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
 * <h2>Java 侧的三处结构性替换（已登记为已知差异）</h2>
 * <ol>
 *   <li><b>asynq → 进程内虚拟线程队列</b>（{@link InProcessWikiIngestTaskQueue}）：
 *       与阶段 3 的 {@code KnowledgeProcessWorker} 同模式。延迟、TaskID 合并、
 *       重试预算与超时语义保留；多副本下的协调缺失见该实现注释。</li>
 *   <li><b>Redis → 可插拔端口</b>：{@link WikiSlugLock}（已有）、
 *       {@link WikiInflightLimiter}、{@link WikiDeletedTombstoneStore}。
 *       默认都是进程内实现，语义与 Go 的 Lite 模式一致。</li>
 *   <li><b>context.Context → 显式传参 + TenantContext</b>：取消传播改用线程中断
 *       （见 {@link WikiCleanupScope}）；LLM 记账元数据改用 {@link WikiLlmCallMetadata}。</li>
 * </ol>
 *
 * <h2>暴露给后续翻译任务的接缝</h2>
 * <ul>
 *   <li>{@link WikiIngestTaskHandler} —— {@code ProcessWikiIngest} /
 *       {@code ProcessWikiFinalize}（wiki_ingest_batch.go）的落点；</li>
 *   <li>{@link WikiDedupSupport} —— wiki_ingest_dedup.go 的五个函数的落点；</li>
 *   <li>{@code NewSlugFromCitation} / {@code ExtractedItem} 等共享类型 —— cite 侧复用；</li>
 *   <li>{@code WikiBatchContext} / {@code SlugUpdate} / {@code DocIngestResult} ——
 *       Map/Reduce 阶段的数据载体。</li>
 * </ul>
 */
@Service
public class WikiIngestService {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 Go {@code indexIntroSummaryCap} 用到的占位内容（L2134）。 */
    private static final String LEGACY_INDEX_PLACEHOLDER = "Wiki index - table of contents";

    // ═══════════════════════════════════════════════════════════════
    // 依赖（对照 Go 的 wikiIngestService 字段，L363-395）
    // ═══════════════════════════════════════════════════════════════

    private final WikiPageService wikiService;
    private final TaskPendingOpsRepository pendingRepo;
    private final ObjectProvider<TaskDeadLetterRepository> deadLetterRepo;
    private final ObjectProvider<KnowledgeMapper> knowledgeMapper;
    private final WikiSlugLock slugLock;
    private final WikiInflightLimiter inflightLimiter;
    private final ObjectProvider<WikiDeletedTombstoneStore> tombstoneStore;
    private final ObjectProvider<WikiIngestTaskQueue> taskQueue;
    private final ObjectProvider<WikiCrossLinker> crossLinker;
    private final ObjectProvider<WikiDedupSupport> dedupSupport;
    private final ObjectProvider<WikiKnowledgeFinalizer> knowledgeFinalizer;
    private final ObjectProvider<WikiImageEnricher> imageEnricher;
    private final ObjectProvider<WikiIngestTaskHandler> taskHandler;

    /**
     * 对照 Go {@code liteLocks sync.Map}（L384）：Lite 模式下的按 KB 互斥。
     *
     * <p>Go 用 {@code redisClient == nil} 判断是否 Lite 模式；Java 侧的等价判据是
     * "没有分布式的在途限流实现"——即使用进程内实现（默认装配）。
     * 由 {@link WikiIngestTaskHandler} 的实现在 {@code processWikiIngest} 里使用
     * （Go 的用法就在 ProcessWikiIngest 里），本类提供容器与判据。</p>
     */
    private final Set<String> liteLocks = ConcurrentHashMap.newKeySet();

    /**
     * 对照 Go {@code liteFinalizeLocks sync.Map}（L388）：Lite 模式下
     * {@code wiki:finalize:active:<kbID>} 的进程内对应物。
     */
    private final Set<String> liteFinalizeLocks = ConcurrentHashMap.newKeySet();

    /**
     * 对照 Go {@code llmRequests singleflight.Group}（L391）：
     * 合并进程内<b>字节完全相同</b>的并发 prompt。
     */
    private final SingleFlight llmRequests = new SingleFlight();

    /**
     * 对照 Go {@code promptWarmups sync.Map}（L394）：只串行化同一个可复用 Wiki 页面
     * 前缀的<b>首个</b>请求；其它前缀与已经预热过的同类保持并行。
     */
    private final ConcurrentHashMap<String, PromptWarmup> promptWarmups = new ConcurrentHashMap<>();

    /** 预热标记的回收器（对照 Go 的 {@code time.AfterFunc(4*time.Minute, ...)}）。 */
    private final java.util.concurrent.ScheduledExecutorService warmupReaper =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wiki-prompt-warmup-reaper");
                t.setDaemon(true);
                return t;
            });

    /** 对照 Go 的 {@code wikiPromptWarmup{done chan, once sync.Once}} */
    private static final class PromptWarmup {
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
    }

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
    }

    // ═══════════════════════════════════════════════════════════════
    // 模式判定 / Lite 锁（对照 Go ProcessWikiIngest 的 L261-274 分支）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go 的 {@code s.redisClient == nil} 判据（L261）：是否处于 "Lite 模式"
     * （没有跨进程协调）。
     *
     * <p>Java 侧以"在途限流器是否为进程内实现"为判据——进程内实现意味着
     * 没有 Redis 级别的共享协调，正是 Go 里 {@code redisClient == nil} 的含义。</p>
     */
    public boolean isLiteMode() {
        return inflightLimiter instanceof InProcessWikiInflightLimiter;
    }

    /**
     * 对照 Go 的 {@code liteLocks.LoadOrStore(kbID, struct{}{})}（L263）：
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

    /** 对照 Go 的 {@code defer s.liteLocks.Delete(kbID)}（L267） */
    public void releaseLiteLock(String kbId) {
        liteLocks.remove(kbId);
    }

    /** 对照 Go 的 {@code liteFinalizeLocks} 用法：finalize 的进程内互斥入口。 */
    public boolean tryAcquireLiteFinalizeLock(String kbId) {
        return liteFinalizeLocks.add(kbId);
    }

    /** 释放 finalize 的进程内锁。 */
    public void releaseLiteFinalizeLock(String kbId) {
        liteFinalizeLocks.remove(kbId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 任务分派（对照 Go Handle，L671-678）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code Handle}：按任务类型分派。
     *
     * <p>Go 在这里 {@code switch t.Type()}。Java 侧队列（
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
    // 清理作用域（对照 Go wikiIngestCleanupContext，L680-695）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code wikiIngestCleanupContext}（L680-685）：开一个脱钩的清理作用域。
     *
     * <p>用法与 Go 相同：{@code try (var scope = cleanupScope()) { scope.run(...); }}</p>
     */
    public WikiCleanupScope cleanupScope() {
        return WikiCleanupScope.open();
    }

    /**
     * 对照 Go {@code clearDeletedKnowledgeBasePendingOps}（L687-695）：
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
    // 投递：ingest / retract（对照 Go L485-665）
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go 的 {@code (bool, error)} 返回：布尔是"待办 op 是否已持久化"。 */
    public record EnqueueResult(boolean accepted, Exception error) {}

    /**
     * 对照 Go {@code enqueueWikiPendingOp}（L485-500）：把 op 持久化到
     * {@code task_pending_ops}。
     *
     * <p>Go 在有 {@code TaskPendingOpsKnowledgeBaseGuard} 时走原子守卫
     * （"KB 仍活跃才入队"）。Java 侧该守卫尚未接线（它属于 knowledge 模块的
     * 删除路径），因此退化为普通入队——与 Go 在没有该扩展接口时的行为一致。</p>
     */
    public boolean enqueueWikiPendingOp(TaskPendingOp op) {
        if (pendingRepo == null) {
            // 对照 Go L490-492：pendingRepo 缺席时直接返回 (true, nil)
            return true;
        }
        pendingRepo.enqueue(op);
        return true;
    }

    /**
     * 对照 Go {@code EnqueueWikiIngest}（L502-533）：把一篇文档排进 wiki 队列，
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
     *         <b>触发错误可能与 accepted=true 同时返回</b>（对照 Go 注释），
     *         调用方可以只重试 KB 级的触发而不追加重复的操作。
     */
    public EnqueueResult enqueueWikiIngest(long tenantId, String kbId, String knowledgeId) {
        TaskPendingOp op;
        try {
            op = newWikiIngestPendingOp(tenantId, kbId, knowledgeId);
        } catch (Exception e) {
            log.warn("wiki ingest: failed to marshal pending op for {}: {}", knowledgeId, e.getMessage());
            return new EnqueueResult(false, e);
        }
        boolean accepted;
        try {
            accepted = enqueueWikiPendingOp(op);
        } catch (Exception e) {
            log.warn("wiki ingest: failed to enqueue pending op for {}: {}", knowledgeId, e.getMessage());
            return new EnqueueResult(false, e);
        }
        if (!accepted) {
            log.info("wiki ingest: skip enqueue for deleted KB {}", kbId);
            return new EnqueueResult(false, null);
        }
        try {
            enqueueWikiIngestTrigger(tenantId, kbId);
        } catch (Exception e) {
            return new EnqueueResult(true, e);
        }
        return new EnqueueResult(true, null);
    }

    /**
     * 对照 Go {@code newWikiIngestPendingOp}（L535-559）：构造 ingest 待办行。
     *
     * <p><b>语言必须在这里落定</b>（wiki_ingest_language_test.go 钉住的行为）：
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
     * 对照 Go {@code enqueueWikiIngestTrigger}（L561-594）：调度防抖的批次触发。
     *
     * <p>asynq 选项逐条对照：队列 wiki、MaxRetry 10、Timeout 60 分钟、
     * ProcessIn 30 秒（{@link WikiIngestConstants#INGEST_DELAY}）。</p>
     */
    public void enqueueWikiIngestTrigger(long tenantId, String kbId) {
        WikiIngestPayload trigger = new WikiIngestPayload(
                tenantId, kbId, WikiLanguageSupport.languageFromContextOrDefault());
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
     * 对照 Go {@code EnqueueWikiRetract}（L603-607）：排一次撤回（删除清理）。
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

    /** 对照 Go {@code enqueueWikiRetract}（L609-665） */
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

        WikiIngestPayload trigger = new WikiIngestPayload(
                payload.tenantId(), payload.knowledgeBaseId(), payload.language());
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
    // Finalize 通道（对照 Go L697-839）
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go {@code enqueueFinalizeRow}（L697-704） */
    private boolean enqueueFinalizeRow(TaskPendingOp op) {
        try {
            return enqueueWikiPendingOp(op);
        } catch (Exception e) {
            log.warn("wiki finalize: enqueue {} row failed: {}", op.getOp(), e.getMessage());
            return false;
        }
    }

    /**
     * 对照 Go {@code enqueueFinalize}（L711-779）：把本批次的 KB 级收敛工作持久化进
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
            // 对照 Go L719-721：没有持久化队列就没有 finalize 工作可记
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
     * 对照 Go {@code uniqueWikiFolderIDs}（L781-796）：去空白、去重、保序。
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
     * 对照 Go {@code scheduleFinalize}（L804-820）：调度一个防抖、可合并的
     * KB 级 finalize 触发。
     *
     * <p>{@code asynq.TaskID("wiki-finalize-<kbID>")} 让防抖窗口内的并发调度坍缩成
     * 一个待执行任务；<b>冲突不是失败，而是预期的合并信号</b>。Lite 模式下
     * （对照 Go 的 sync executor）TaskID 被忽略，因此 finalize 每批次跑一次
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
     * 对照 Go {@code scheduleFinalizeRetry}（L827-839）：目录剪枝还在等 ingest 行排空
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
     * 对照 Go {@code scheduleCappedRetry}（L1019-1035）：批次被在途上限挡回后，
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
     * 对照 Go {@code scheduleStaleClaimRecheck}（L1051-1079）：为一个"仍有待办行、
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
    // 队列消费（对照 Go L841-1155）
    // ═══════════════════════════════════════════════════════════════

    /** 对照 Go 的 {@code (ops, peekedIDs, err)} 三元返回。 */
    public record PendingBatch(List<WikiPendingOp> ops, List<Long> peekedIds) {}

    /**
     * 对照 Go {@code peekPendingList}（L852-869）：为该 KB 按 FIFO 载入最多
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
     * 对照 Go {@code claimPendingList}（L879-896）：{@code peekPendingList} 在
     * standard（分布式协调）模式下的对应物——原子地<b>认领</b>最多 {@code limit}
     * 条 op（标记 {@code claimed_at}），让同一 KB 的并发批次（Phase 3 移除独占
     * 按 KB 锁之后成为可能）拉到<b>互不相交</b>的文档而不是重复处理。
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
     * 对照 Go {@code decodePendingRows}（L1086-1141）：把原始行转成
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
     * 对照 Go {@code trimPendingList}（L1146-1155）：删除已消费的行。
     * 空入参是 no-op，因此调用方可以在批次结束时无条件调用。
     *
     * @throws RuntimeException 删除失败（对照 Go 返回 error；调用方据此让批次结算失败）
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
     * 对照 Go {@code trimPendingList(wikiIngestCleanupContext(ctx), ids)}：
     * 在<b>脱钩</b>的清理路径上删除已消费的行（wiki_ingest_test.go 的
     * {@code TestWikiIngestCleanupContextDetachedFromCancelledParent} 覆盖的行为）。
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
    // 锁与限流（对照 Go L898-1011）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code withSlugLock}（L912-938）：把对<b>同一个共享 wiki 页面</b>的
     * 读-改-写串行化。
     *
     * <p>Phase 3 移除了按 KB 的独占批次锁，因此同一 KB 的两个批次可能同时为同一个
     * 共享 entity/concept slug 产出更新；没有这把锁，它们的
     * {@code GetPageBySlug → UpdatePage} 循环会竞争并丢掉一份贡献。</p>
     *
     * <p><b>失败语义（与 Go 一致）</b>：等待超时返回 false（调用方把该 slug 当作
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
     * 对照 Go {@code reserveInflightSlot}（L969-1011）：占用该 KB 的一个并发批次槽位。
     *
     * <p>{@code granted == false} 时调用方应调度 cap 重试并放弃本批次；
     * {@code granted == true} 时<b>必须</b>在批次结束时释放。</p>
     */
    public WikiInflightLimiter.Reservation reserveInflightSlot(String kbId, int maxInflight) {
        return inflightLimiter.reserve(kbId, maxInflight);
    }

    // ═══════════════════════════════════════════════════════════════
    // 失败结算（对照 Go L1157-1255）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code finalizeWikiSubtask}（L1168-1174）：该文档的 wiki op 到达终态
     * （成功映射或已进死信）时，释放它在 finalizing 计数里的槽位。
     *
     * <p>对应的 +1 是由 {@code KnowledgePostProcess.SetFinalizing} 在
     * {@code willSpawnWiki} 为真时播种的。<b>只能对 ingest op 调用</b>——
     * retract op 针对的是已删除的知识，没有计数器需要排空。</p>
     *
     * <p>对已完成、或计数已为 0 的行调用是安全的 no-op（Go 的 FinalizeSubtask
     * 同时守住了递减与晋升两个条件）。使用<b>脱钩的执行路径</b>：wiki 批次 worker
     * 可能正在关闭或父作用域已被取消，吞掉失败会把父文档永久留在 "finalizing"。</p>
     */
    public void finalizeWikiSubtask(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return;
        }
        WikiKnowledgeFinalizer finalizer = knowledgeFinalizer.getIfAvailable();
        if (finalizer == null) {
            // 对照 Go：knowledgeRepo 缺席时 finalizeSubtaskDetached 是 no-op
            log.debug("wiki ingest: knowledge finalizer not wired, skipping subtask finalize for {}",
                    knowledgeId);
            return;
        }
        try (WikiCleanupScope scope = cleanupScope()) {
            scope.run(() -> finalizer.finalizeWikiSubtask(knowledgeId));
        }
    }

    /**
     * 对照 Go {@code requeueFailedOps}（L1190-1255）：记录批内失败。
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
     * <p><b>返回结算错误列表</b>（对照 Go 的 {@code errors.Join(settleErrs...)}；
     * Java 没有错误聚合类型，返回列表比只留第一个更能暴露问题）。
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
     * 对照 Go {@code requeueFailedOps(wikiIngestCleanupContext(ctx), ...)}：
     * 在脱钩路径上结算失败（Go 的调用点就是 cleanup ctx——批次超时/被取消时
     * 仍必须把失败记账与归档做完）。
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
    // 死链清理 / 交叉链接（对照 Go L1512-1892）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code sanitizeDeadSummaryLinks}（L1534-1587）：重写<b>本批次</b>产出的
     * 摘要页，修掉那些指向 reduce 阶段生成失败的 entity/concept 页面的
     * {@code [[slug]]} / {@code [[slug|display]]} 引用。
     *
     * <p>纯文本替换，不调用 LLM。作用域限定在本批次的文档摘要 slug
     * （{@code summary/<slugify(knowledgeID)>}），让工作量与批次大小成正比。</p>
     */
    public void sanitizeDeadSummaryLinks(String kbId,
                                         List<DocIngestResult> docResults,
                                         Set<String> failedSlugs,
                                         WikiBatchContext batchCtx) {
        if (failedSlugs == null || failedSlugs.isEmpty()
                || docResults == null || docResults.isEmpty()) {
            return;
        }
        for (DocIngestResult r : docResults) {
            if (r == null || r.getKnowledgeId().isEmpty()) {
                continue;
            }
            String summarySlug = "summary/" + WikiTextUtils.slugify(r.getKnowledgeId());
            // 对照 Go：if err != nil || page == nil { continue }
            WikiPage page = wikiService.findPageBySlug(kbId, summarySlug);
            if (page == null) {
                continue;
            }

            // 收集这份摘要实际链接到的 slug（让 resolver 有非空的候选池），
            // 加上同一文档里成功写出的兄弟页面。这两个集合合起来覆盖了
            // "LLM 说的" vs "实际存在的" 不匹配，又不必为一次全量扫描付费。
            Set<String> candidateSlugs = new LinkedHashSet<>(page.getOutLinks());
            for (DocIngestResult.PageRef ref : r.getPages()) {
                if (failedSlugs.contains(ref.slug())) {
                    continue;
                }
                candidateSlugs.add(ref.slug());
            }
            WikiDeadLinks.ResolvedLiveSlugs resolved =
                    WikiDeadLinks.resolveLiveSlugs(batchCtx, candidateSlugs);

            WikiDeadLinks.Result stripped = WikiDeadLinks.stripDeadWikiLinks(
                    page.getContent(), failedSlugs, resolved.liveSlugs(), resolved.titleToSlug());
            if (!stripped.changed()) {
                continue;
            }
            page.setContent(stripped.content());
            try {
                wikiService.updateAutoLinkedContent(page);
            } catch (Exception e) {
                log.warn("wiki ingest: failed to sanitize dead links in summary {}: {}",
                        summarySlug, e.getMessage());
                continue;
            }
            log.info("wiki ingest: sanitized dead [[slug]] refs in summary {}", summarySlug);
        }
    }

    /**
     * 对照 Go {@code cleanDeadLinks}（L1716-1790）：重写本批次受影响页面里指向
     * 已不存在（或已归档）目标的 {@code [[slug]]}。纯文本清理，不调用 LLM。
     *
     * <p>作用域刻意限定在本批次触碰过的 slug：4 万文档规模下，"扫全表页面"的历史路径
     * 是批次后阶段的主要尾巴，而长尾的历史死链更适合交给 lint AutoFix 管线
     * （它跑在带外，承担得起全表遍历）。</p>
     *
     * <p>流程：取页面 → 用一次批量 {@code ExistsSlugs} 把出链分类成活/死 →
     * 对每条死链先试 {@code resolveDeadSlug}，能安全还原就改写，否则剥离成纯文本 →
     * 用 {@code UpdateAutoLinkedContent} 持久化（版本号不变——这是维护性写入，
     * 不是用户可见的编辑）。</p>
     */
    public void cleanDeadLinks(String kbId, List<String> affectedSlugs, WikiBatchContext batchCtx) {
        if (affectedSlugs == null || affectedSlugs.isEmpty()) {
            return;
        }
        int cleaned = 0;
        for (String slug : affectedSlugs) {
            // 对照 Go：if err != nil || page == nil { continue }
            WikiPage page = wikiService.findPageBySlug(kbId, slug);
            if (page == null) {
                continue;
            }
            if (WikiConstants.STATUS_ARCHIVED.equals(page.getStatus())) {
                continue;
            }
            if (WikiConstants.PAGE_TYPE_INDEX.equals(page.getPageType())) {
                continue;
            }
            if (page.getOutLinks().isEmpty()) {
                continue;
            }

            Map<String, Boolean> liveMap;
            try {
                liveMap = wikiService.existsSlugs(kbId, new ArrayList<>(page.getOutLinks()));
            } catch (Exception e) {
                log.warn("wiki: ExistsSlugs failed during dead-link cleanup for {}: {}",
                        slug, e.getMessage());
                continue;
            }
            Set<String> deadSlugs = new LinkedHashSet<>();
            Set<String> liveSlugs = new LinkedHashSet<>();
            for (Map.Entry<String, Boolean> e : liveMap.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) {
                    liveSlugs.add(e.getKey());
                } else {
                    deadSlugs.add(e.getKey());
                }
            }
            if (deadSlugs.isEmpty()) {
                continue;
            }

            // 只为活跃 slug 取标题——它们才是一条死引用可能被重映射到的候选
            Map<String, String> titles = batchCtx == null
                    ? Map.of() : batchCtx.slugTitleMany(new ArrayList<>(page.getOutLinks()));
            Map<String, String> titleToSlug = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : titles.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    titleToSlug.put(e.getValue(), e.getKey());
                }
            }

            WikiDeadLinks.Result stripped =
                    WikiDeadLinks.stripDeadWikiLinks(page.getContent(), deadSlugs, liveSlugs, titleToSlug);
            if (!stripped.changed()) {
                continue;
            }

            page.setContent(stripped.content());
            try {
                wikiService.updateAutoLinkedContent(page);
            } catch (Exception e) {
                log.warn("wiki: failed to clean dead links in page {}: {}", page.getSlug(), e.getMessage());
                continue;
            }
            cleaned++;
        }
        if (cleaned > 0) {
            log.info("wiki: cleaned dead links in {} pages", cleaned);
        }
    }

    /**
     * 对照 Go {@code injectCrossLinks}（L1812-1872）：扫描本批次受影响的页面，
     * 为正文里提到的其它页面标题 / 别名注入 {@code [[wiki-links]]}。
     * 纯文本替换，不调用 LLM。
     *
     * <p>作用域刻意限定在两批 slug：</p>
     * <ol>
     *   <li>受影响的页面本身——我们只重写它们的正文；</li>
     *   <li>候选 ref 来自 (a) 这些页面既有的出链（已通过先前的 linkify 或人工编辑
     *       证明其相关性）加上 (b) 调用方通过 {@code freshRefs} 传入的、本批次刚写出的
     *       兄弟 slug。</li>
     * </ol>
     *
     * <p>较之"只为了找链接候选就加载 10 万+ 页面"，这是 O(批次大小) 的查询。
     * 代价是长尾召回略降（本批次新出现的实体，要等到相关页面被重新编辑时才会
     * 被链进去），而 lint AutoFix 才是处理那种情况的正道。</p>
     *
     * <p>实际匹配（含代码块 / 既有链接 / 词边界排除）由 {@link WikiCrossLinker} 完成。</p>
     */
    public void injectCrossLinks(String kbId,
                                 List<String> affectedSlugs,
                                 List<WikiCrossLinker.LinkRef> freshRefs,
                                 WikiBatchContext batchCtx) {
        if (affectedSlugs == null || affectedSlugs.isEmpty()) {
            return;
        }
        WikiCrossLinker linker = crossLinker.getIfAvailable(WikiCrossLinker.Noop::new);

        int updated = 0;
        for (String slug : affectedSlugs) {
            // 对照 Go：if err != nil || page == nil { continue }
            WikiPage page = wikiService.findPageBySlug(kbId, slug);
            if (page == null) {
                continue;
            }
            if (WikiConstants.PAGE_TYPE_INDEX.equals(page.getPageType())) {
                continue;
            }

            // 逐页候选 ref 集合：既有出链（经批次的标题 fetcher 解析，
            // 顺带跳过归档 / 系统页面）加上本批次刚写出的兄弟 slug。
            List<WikiCrossLinker.LinkRef> refs = new ArrayList<>();
            if (!page.getOutLinks().isEmpty()) {
                Map<String, String> titles = batchCtx == null
                        ? Map.of() : batchCtx.slugTitleMany(new ArrayList<>(page.getOutLinks()));
                for (Map.Entry<String, String> e : titles.entrySet()) {
                    if (e.getValue() == null || e.getValue().isEmpty()) {
                        continue;
                    }
                    if (e.getKey().equals(slug)) {
                        continue;
                    }
                    refs.add(new WikiCrossLinker.LinkRef(e.getKey(), e.getValue()));
                }
            }
            if (freshRefs != null) {
                for (WikiCrossLinker.LinkRef fr : freshRefs) {
                    if (fr.slug().equals(slug)) {
                        continue;
                    }
                    refs.add(fr);
                }
            }
            if (refs.isEmpty()) {
                continue;
            }

            WikiCrossLinker.LinkifyResult result = linker.linkify(page.getContent(), refs, page.getSlug());
            if (!result.changed()) {
                continue;
            }
            page.setContent(result.content());
            try {
                wikiService.updateAutoLinkedContent(page);
            } catch (Exception e) {
                log.warn("wiki ingest: cross-link injection failed for {}: {}",
                        page.getSlug(), e.getMessage());
                continue;
            }
            updated++;
        }
        if (updated > 0) {
            log.info("wiki ingest: injected cross-links in {} pages", updated);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 既有 taxonomy / source-ref 快照（对照 Go L1894-2023）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code formatExistingTaxonomyForPrompt}（L1966-1987）：把去重后的
     * category_path 列表渲染成缩进的目录树，供抽取 prompt 使用。
     *
     * <p>同级标签按<b>字符串升序</b>输出（对照 Go 的 {@code sort.Strings(keys)}）
     * ——Go 的 map 迭代是随机的，所以它显式排序；Java 侧照抄，否则 prompt 的字节
     * 前缀会随批次抖动，provider 前缀缓存会失效。</p>
     *
     * @return 空树时返回 ""（对照 Go 的 {@code return ""}）
     */
    public static String formatExistingTaxonomyForPrompt(List<List<String>> paths) {
        if (paths == null || paths.isEmpty()) {
            return "";
        }
        TaxonomyNode root = new TaxonomyNode();
        for (List<String> path : paths) {
            insertWikiTaxonomyPath(root, path);
        }
        if (root.children.isEmpty()) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        // 对照 Go 的 sort.Strings：**字节序**（对 UTF-8 等价于码点序）。
        // 不能用 Collections.sort 的 UTF-16 码元序——顺序会直接影响 prompt 字节，
        // 进而决定 provider 前缀缓存是否命中。
        List<String> keys = new ArrayList<>(root.children.keySet());
        keys.sort(GoStrings::compareByCodePoints);
        for (String k : keys) {
            appendWikiTaxonomyNode(buf, k, root.children.get(k), 0);
        }
        return GoStrings.trimSpace(buf.toString());
    }

    /**
     * 对照 Go {@code wikiTaxonomyNode}（L1921-1923）。
     *
     * <p>用 {@link java.util.TreeMap} 保证遍历即有序，但比较器是<b>码点序</b>
     * （{@link GoStrings#compareByCodePoints}）而不是 Java 默认的 UTF-16 码元序
     * ——Go 的 {@code sort.Strings} 是字节序，对 UTF-8 等价于码点序。</p>
     */
    private static final class TaxonomyNode {
        private final Map<String, TaxonomyNode> children =
                new java.util.TreeMap<>(GoStrings::compareByCodePoints);
    }

    /** 对照 Go {@code insertWikiTaxonomyPath}（L1925-1945） */
    private static void insertWikiTaxonomyPath(TaxonomyNode root, List<String> path) {
        if (root == null || path == null || path.isEmpty()) {
            return;
        }
        TaxonomyNode cur = root;
        for (String raw : path) {
            String part = GoStrings.trimSpace(raw == null ? "" : raw);
            if (part.isEmpty()) {
                continue;
            }
            cur = cur.children.computeIfAbsent(part, k -> new TaxonomyNode());
        }
    }

    /** 对照 Go {@code appendWikiTaxonomyNode}（L1947-1962）：每层两个空格缩进。 */
    private static void appendWikiTaxonomyNode(StringBuilder buf, String label,
                                               TaxonomyNode node, int depth) {
        if (label != null && !label.isEmpty()) {
            buf.append("  ".repeat(Math.max(0, depth))).append(label).append('\n');
        }
        if (node == null || node.children.isEmpty()) {
            return;
        }
        // TreeMap 已保证升序；照 Go 的 sort.Strings 语义
        for (Map.Entry<String, TaxonomyNode> e : node.children.entrySet()) {
            appendWikiTaxonomyNode(buf, e.getKey(), e.getValue(), depth + 1);
        }
    }

    /**
     * 对照 Go {@code getExistingPageSlugsForKnowledge}（L2004-2023）：返回当前在
     * {@code source_refs} 里引用了给定 knowledge id 的全部页面 slug。
     * 重新摄取前用它快照状态，好让 reduce 阶段调和"新增 vs 撤回"。
     *
     * <p>系统页（{@code index}）显式跳过——纵深防御：一个老版本有 bug 的摄取若曾
     * 误把知识引用盖到系统页上，那些 slug 会出现在重解析的"旧集合"里并搅乱 reduce。</p>
     *
     * @return 无命中时返回 <b>null</b>（对照 Go 的 {@code return nil}）
     */
    public Set<String> getExistingPageSlugsForKnowledge(String kbId, String knowledgeId) {
        List<String> slugs;
        try {
            slugs = wikiService.listSlugsBySourceRef(kbId, knowledgeId);
        } catch (Exception e) {
            log.warn("wiki ingest: ListSlugsBySourceRef({}) failed: {}", knowledgeId, e.getMessage());
            return null;
        }
        if (slugs == null || slugs.isEmpty()) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>(slugs.size());
        for (String slug : slugs) {
            if (WikiConstants.PAGE_TYPE_INDEX.equals(slug)) {
                continue;
            }
            out.add(slug);
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    // 索引页 / 草稿发布（对照 Go L2061-2248）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code rebuildIndexPage}（L2108-2213）：刷新索引页上由 LLM 生成的导语。
     *
     * <p>历史：索引页曾把"导语 + 完整目录"作为单个数 MB 的 markdown blob 存在 content 里，
     * 每个 ingest 批次都重写整列——在数万页的 KB 上是每批次 O(N) 的 TOAST 写入。
     * 目录已被提升为结构化的 {@code GET /wiki/index} 端点（{@code GetIndexView}），
     * 本方法现在只维护导语。</p>
     *
     * <p>导语生命周期：</p>
     * <ul>
     *   <li>首次（空或历史占位符）：用全部文档摘要经 {@code WikiIndexIntroPrompt} 生成；</li>
     *   <li>带变更描述的后续调用：经 {@code WikiIndexIntroUpdatePrompt} 增量更新；</li>
     *   <li>没有变更描述：原样保留既有导语，不动版本号。</li>
     * </ul>
     * <p>新导语同时写进 {@code Content} 与 {@code Summary}，让仍回落到 Summary 的读取方
     * （老客户端、历史迁移数据）与实际渲染的那一列保持同步。</p>
     */
    public void rebuildIndexPage(LlmChatClient chatModel,
                                 WikiIngestPayload payload,
                                 String changeDesc,
                                 String lang,
                                 String customInstructions) {
        WikiPage indexPage = wikiService.getIndex(payload.knowledgeBaseId());
        if (indexPage == null) {
            return;
        }

        // 导语同时住在 Content 与 Summary。优先 Content（新的索引视图返回的就是它）；
        // 回落到 Summary 是为了兼容本次重构之前写入的行，
        // 好让增量更新 prompt 有东西可用。
        String existingIntro = GoStrings.trimSpace(indexPage.getContent());
        if (existingIntro.isEmpty()) {
            existingIntro = GoStrings.trimSpace(indexPage.getSummary());
        }
        // 识别历史的"导语 + 目录"载荷：那种行在导语之后紧跟围栏分隔的 "## Summary" 段，
        // 因此从第一个目录标题起全部裁掉，让回灌进更新 prompt 的导语长度有界。
        int dirIdx = existingIntro.indexOf("\n## ");
        if (dirIdx >= 0) {
            existingIntro = GoStrings.trimSpace(existingIntro.substring(0, dirIdx));
        }

        String intro;
        if (existingIntro.isEmpty() || LEGACY_INDEX_PLACEHOLDER.equals(existingIntro)) {
            // 首次生成：经 lite 投影拉最近更新的 top-N 摘要页。
            // CountByType 让我们能告诉 LLM "showing N of M"，
            // 从而在 KB 比采样集更大时诚实地交代。
            List<WikiIndexEntry> recentSummaries = wikiService.listByTypeRecent(
                    payload.knowledgeBaseId(), WikiConstants.PAGE_TYPE_SUMMARY,
                    WikiIngestConstants.INDEX_INTRO_SUMMARY_CAP);

            StringBuilder docSummaries = new StringBuilder();
            for (WikiIndexEntry e : recentSummaries) {
                docSummaries.append("<document>\n<title>").append(e.getTitle())
                        .append("</title>\n<summary>").append(e.getSummary())
                        .append("</summary>\n</document>\n\n");
            }
            long totalSummaries = recentSummaries.size();
            try {
                Map<String, Long> counts = wikiService.countByType(payload.knowledgeBaseId());
                if (counts != null && counts.get(WikiConstants.PAGE_TYPE_SUMMARY) != null) {
                    totalSummaries = counts.get(WikiConstants.PAGE_TYPE_SUMMARY);
                }
            } catch (Exception e) {
                // 计数失败不阻断导语生成（对照 Go 的 "A failure here doesn't block"）
                log.debug("wiki ingest: CountByType failed, using sample size for framing hint");
            }
            String framing = "";
            if (totalSummaries > recentSummaries.size() && !recentSummaries.isEmpty()) {
                framing = "(showing " + recentSummaries.size() + " most recent of "
                        + totalSummaries + " total documents)\n\n";
            }
            if (docSummaries.length() == 0) {
                docSummaries.append("(no documents yet)");
            }
            String generated;
            try {
                generated = generateWithTemplate(chatModel, WikiPrompts.WIKI_INDEX_INTRO_PROMPT,
                        Map.of(
                                "DocumentSummaries", framing + docSummaries,
                                "Language", lang,
                                "CustomInstructions", customInstructions == null ? "" : customInstructions,
                                "InstructionScope", "wiki_content"));
                intro = GoStrings.trimSpace(generated);
            } catch (Exception e) {
                intro = "# Wiki Index\n\nThis wiki contains knowledge extracted from uploaded documents.\n";
            }
        } else if (changeDesc != null && !changeDesc.isEmpty()) {
            // 增量更新：只把既有导语 + 本批次的变更描述放进 prompt。
            // 这里刻意不再传完整的 DocumentSummaries——4 万文档时它每个批次都会
            // 重新灌满上下文，而变更描述块已经编码了 prompt 想要的"刚发生了什么"。
            String updated;
            try {
                updated = generateWithTemplate(chatModel, WikiPrompts.WIKI_INDEX_INTRO_UPDATE_PROMPT,
                        Map.of(
                                "ExistingIntro", existingIntro,
                                "ChangeDescription", changeDesc,
                                "DocumentSummaries", "",
                                "Language", lang,
                                "CustomInstructions", customInstructions == null ? "" : customInstructions,
                                "InstructionScope", "wiki_content"));
                intro = GoStrings.trimSpace(updated);
            } catch (Exception e) {
                intro = existingIntro; // 出错时保留既有导语
            }
        } else {
            // 没有变更描述且已有导语：原样保留，避免为一次 no-op 递增版本号
            intro = existingIntro;
        }

        // 防御：某些 LLM 输出即使 prompt 没要求，也会渗出类似目录的段落。
        // 若刚生成的导语开始像历史载荷，就按读路径同样的规则在第一个 "\n## " 处裁掉，
        // 让 indexPage.Content 保持为长度有界的纯导语。
        int cut = intro.indexOf("\n## ");
        if (cut >= 0) {
            intro = GoStrings.trimSpace(intro.substring(0, cut));
        }

        indexPage.setContent(intro);
        indexPage.setSummary(intro);
        wikiService.updatePage(indexPage);
    }

    /**
     * 对照 Go {@code publishDraftPages}（L2235-2248）：摄取完成后把草稿页转为已发布，
     * 确保用户在摄取过程中看不到半成品页面。
     */
    public void publishDraftPages(String kbId, List<String> slugs) {
        if (slugs == null) {
            return;
        }
        for (String slug : slugs) {
            // 对照 Go：if err != nil || page == nil { continue }
            WikiPage page = wikiService.findPageBySlug(kbId, slug);
            if (page == null) {
                continue;
            }
            if (WikiConstants.STATUS_DRAFT.equals(page.getStatus())) {
                page.setStatus(WikiConstants.STATUS_PUBLISHED);
                try {
                    wikiService.updatePageMeta(page);
                } catch (Exception e) {
                    log.warn("wiki ingest: failed to publish page {}: {}", slug, e.getMessage());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 去重（对照 Go L2250-2502）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code writeDedupCandidateGroup}（L2256-2284）：把一个新条目连同
     * <b>它自己的</b>相似候选页渲染成嵌套在 {@code <candidates>} 下的 XML。
     *
     * <p>这种逐条目分组正是把去重模型约束成"局部决策"的机制。候选页保留它们的
     * aliases，好让模型仍握有接受一次合法合并所需的缩写 / 翻译信号。</p>
     *
     * <p>{@code slug=%q} / {@code type=%q} 用 Go 的 {@code fmt %q} 语义——
     * 对 slug（纯 ASCII）而言就是加双引号并转义 {@code "} 与 {@code \}。
     * Go 的 %q 还会对不可打印字符用反斜杠转义（{@code \xNN} / {@code uXXXX} 形态），
     * Java 侧实现了同样的规则（见 {@link #goQuote}）。</p>
     */
    static void writeDedupCandidateGroup(StringBuilder buf, ExtractedItem item,
                                         String itemType, List<WikiPageLite> candidates) {
        buf.append("  <item slug=").append(goQuote(item.getSlug()))
                .append(" type=").append(goQuote(itemType)).append(">\n");
        buf.append("    <name>").append(WikiTextUtils.xmlEscape(item.getName())).append("</name>\n");
        for (String alias : item.getAliases()) {
            if (alias == null || alias.isEmpty()) {
                continue;
            }
            buf.append("    <alias>").append(WikiTextUtils.xmlEscape(alias)).append("</alias>\n");
        }
        buf.append("    <candidates>\n");
        for (WikiPageLite p : candidates) {
            if (p == null) {
                continue;
            }
            buf.append("      <page slug=").append(goQuote(p.getSlug()))
                    .append(" type=").append(goQuote(p.getPageType())).append(">\n");
            buf.append("        <name>").append(WikiTextUtils.xmlEscape(p.getTitle())).append("</name>\n");
            for (String alias : p.getAliases()) {
                if (alias == null || alias.isEmpty()) {
                    continue;
                }
                buf.append("        <alias>").append(WikiTextUtils.xmlEscape(alias)).append("</alias>\n");
            }
            buf.append("      </page>\n");
        }
        buf.append("    </candidates>\n");
        buf.append("  </item>\n");
    }

    /**
     * 对照 Go 的 {@code %q} 动词（{@code strconv.Quote}）：给字符串加双引号，
     * 并转义 {@code "}、{@code \} 以及不可打印字符。
     *
     * <p>wiki slug 是 ASCII 且不含引号，因此实际输出就是 {@code "entity/foo"}。
     * 这里把规则补全是为了将来有人把非 ASCII 内容塞进来时不至于产出非法 XML
     * （Go 的 %q 同样保留可打印 Unicode 原样，只转义不可打印字符）。</p>
     */
    static String goQuote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (cp) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (cp < 0x20 || cp == 0x7F) {
                        out.append(String.format("\\x%02x", cp));
                    } else if (cp > 0x7E && Character.getType(cp) == Character.CONTROL) {
                        out.append(String.format("\\u%04x", cp));
                    } else {
                        out.appendCodePoint(cp);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /**
     * 对照 Go {@code deduplicateExtractedBatch}（L2306-2502）：用<b>一次 LLM 调用</b>
     * 把 entities 与 concepts 一起对既有 wiki 页面去重。
     *
     * <p>候选预筛走 {@code FindSimilarPages}（PG 侧是 {@code lower(title)} 上的
     * pg_trgm 三元组索引）：每个新条目发一次探测，所有条目的 top-K 命中并集就是候选集。
     * 这取代了历史"ListAllPages + Go 侧表面形式 Jaccard"的 O(P × N) 路径。</p>
     *
     * <p>另外逐条目记录"为<b>这个</b>条目召回了哪些 slug"（{@code itemCandidates}）。
     * prompt 只看到扁平化的并集，而 {@code dedupMergeRejectReason} 用这份逐条目作用域
     * 拒绝"目标是为另一个条目召回的"合并——那正是并集否则会放行的幻觉类型。</p>
     *
     * <h2>未接线 {@link WikiDedupSupport} 时的行为</h2>
     * <p>整个去重退化为"跳过 LLM 调用 + 恒等 stabilize"（见该接口的说明）。
     * 这是可见的降级，不是静默的错误答案。</p>
     */
    public ExtractedProjection deduplicateExtractedBatch(LlmChatClient chatModel,
                                                         String kbId,
                                                         List<ExtractedItem> entities,
                                                         List<ExtractedItem> concepts,
                                                         WikiBatchContext batchCtx) {
        WikiDedupSupport dedup = dedupSupport.getIfAvailable();
        if (dedup == null) {
            log.info("wiki ingest: dedup support not wired, skipping deduplication for {} + {} items",
                    entities.size(), concepts.size());
            return new ExtractedProjection(entities, concepts);
        }

        Map<String, WikiPageLite> candidatePages = new LinkedHashMap<>();
        Map<String, Set<String>> itemCandidates = new LinkedHashMap<>();

        // 逐条目探测：用它的 name 与每个 alias 各查一次 top-K，并集即候选
        for (List<ExtractedItem> group : List.of(entities, concepts)) {
            for (ExtractedItem item : group) {
                List<String> queries = new ArrayList<>(1 + item.getAliases().size());
                if (!item.getName().isEmpty()) {
                    queries.add(item.getName());
                }
                for (String alias : item.getAliases()) {
                    if (alias != null && !alias.isEmpty()) {
                        queries.add(alias);
                    }
                }
                Set<String> own = itemCandidates.computeIfAbsent(item.getSlug(), k -> new LinkedHashSet<>());
                for (String q : queries) {
                    List<WikiPageLite> pages;
                    try {
                        pages = wikiService.findSimilarPages(kbId, q,
                                List.of(WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.PAGE_TYPE_CONCEPT),
                                WikiDedupSupport.DEDUP_CANDIDATE_TOP_K);
                    } catch (Exception e) {
                        log.warn("wiki ingest: dedup FindSimilarPages({}) failed: {}", q, e.getMessage());
                        continue;
                    }
                    for (WikiPageLite p : pages) {
                        if (p == null || p.getSlug().isEmpty()) {
                            continue;
                        }
                        candidatePages.putIfAbsent(p.getSlug(), p);
                        own.add(p.getSlug());
                    }
                }
            }
        }

        dedup.attachExactIdentityPages(
                kbId, WikiConstants.PAGE_TYPE_ENTITY, entities, candidatePages, itemCandidates, batchCtx);
        dedup.attachExactIdentityPages(
                kbId, WikiConstants.PAGE_TYPE_CONCEPT, concepts, candidatePages, itemCandidates, batchCtx);

        // 在问模型"语义/别名变体"之前，先确定性地解析"同类型同标题"的候选。
        // 除了省掉明显情形的一次 LLM 调用，它还让已物化的页面在下方的身份预留中成为权威。
        Map<String, String> exactTargets = new LinkedHashMap<>();
        Map<String, String> mergeTargets = new LinkedHashMap<>();
        dedup.collectExactIdentityTargets(
                entities, WikiConstants.PAGE_TYPE_ENTITY, itemCandidates, candidatePages, exactTargets);
        dedup.collectExactIdentityTargets(
                concepts, WikiConstants.PAGE_TYPE_CONCEPT, itemCandidates, candidatePages, exactTargets);

        if (candidatePages.isEmpty()) {
            log.info("wiki ingest: no similar existing pages found for {} new items",
                    entities.size() + concepts.size());
            return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);
        }
        log.info("wiki ingest: {} similar existing pages selected for {} new items",
                candidatePages.size(), entities.size() + concepts.size());

        // 把每个新条目与<b>只为它自己</b>召回的既有页面分成一组。给模型看两个扁平列表
        // （全部新条目 × 全部候选）会诱发跨条目错配——它无从判断哪个候选与哪个条目相关，
        // 于是弱模型会把仅仅共处同一 prompt 的不相干 slug 配成对。逐条目短名单把去重
        // 变成针对少量真正相似页面的局部 yes/no 决策，跨条目配对在结构上无从表达。
        // 没有候选的条目整个省略（它们无法合并，只会增加幻觉面与 token）。
        StringBuilder candBuf = new StringBuilder();
        int[] groups = {0};
        for (ExtractedItem item : entities) {
            renderDedupGroup(candBuf, groups, item, "entity",
                    itemCandidates, candidatePages, exactTargets);
        }
        for (ExtractedItem item : concepts) {
            renderDedupGroup(candBuf, groups, item, "concept",
                    itemCandidates, candidatePages, exactTargets);
        }
        if (groups[0] == 0) {
            // 每个条目都已被精确解析，或没有安全的语义候选
            return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);
        }

        String dedupeJson;
        try {
            dedupeJson = generateWithTemplate(chatModel, WikiPrompts.WIKI_DEDUPLICATION_PROMPT,
                    Map.of("Candidates", candBuf.toString()));
        } catch (Exception e) {
            log.warn("wiki ingest: deduplication LLM call failed: {}", e.getMessage());
            return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);
        }

        dedupeJson = WikiTextUtils.cleanLLMJSON(dedupeJson);
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(dedupeJson);
        } catch (Exception e) {
            log.warn("wiki ingest: failed to parse dedup JSON: {}\nRaw: {}", e.getMessage(), dedupeJson);
            return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);
        }
        JsonNode merges = parsed == null ? null : parsed.get("merges");

        for (List<ExtractedItem> group : List.of(entities, concepts)) {
            for (ExtractedItem item : group) {
                // 已被确定性精确解析的条目跳过——它们不需要模型判断
                if (!exactTargets.getOrDefault(item.getSlug(), "").isEmpty()) {
                    continue;
                }
                if (merges == null || !merges.isObject()) {
                    continue;
                }
                JsonNode target = merges.get(item.getSlug());
                if (target == null || !target.isTextual()) {
                    continue;
                }
                String existingSlug = target.textValue();
                String reason = dedup.dedupMergeRejectReason(
                        item.getSlug(), existingSlug, itemCandidates.get(item.getSlug()));
                if (reason != null && !reason.isEmpty()) {
                    log.warn("wiki ingest: dedup rejected {} → {} ({})",
                            item.getSlug(), existingSlug, reason);
                    continue;
                }
                log.info("wiki ingest: dedup merge {} → {}", item.getSlug(), existingSlug);
                mergeTargets.put(item.getSlug(), existingSlug);
            }
        }

        return stabilize(dedup, kbId, entities, concepts, mergeTargets, exactTargets, batchCtx);
    }

    private void renderDedupGroup(StringBuilder candBuf, int[] groups,
                                  ExtractedItem item, String itemType,
                                  Map<String, Set<String>> itemCandidates,
                                  Map<String, WikiPageLite> candidatePages,
                                  Map<String, String> exactTargets) {
        if (!exactTargets.getOrDefault(item.getSlug(), "").isEmpty()) {
            return;
        }
        Set<String> cset = itemCandidates.get(item.getSlug());
        if (cset == null || cset.isEmpty()) {
            return;
        }
        List<String> slugs = new ArrayList<>(cset.size());
        for (String slug : cset) {
            // 跳过条目自己的 slug：slug 完全相同的既有页是"重新摄取/更新"，
            // 不是合并目标
            if (slug.equals(item.getSlug())) {
                continue;
            }
            if (candidatePages.containsKey(slug)) {
                slugs.add(slug);
            }
        }
        if (slugs.isEmpty()) {
            return;
        }
        Collections.sort(slugs);
        List<WikiPageLite> pages = new ArrayList<>(slugs.size());
        for (String slug : slugs) {
            pages.add(candidatePages.get(slug));
        }
        writeDedupCandidateGroup(candBuf, item, itemType, pages);
        groups[0]++;
    }

    /** 对照 Go 的 {@code stabilize} 闭包（L2386-2392） */
    private ExtractedProjection stabilize(WikiDedupSupport dedup, String kbId,
                                          List<ExtractedItem> entities, List<ExtractedItem> concepts,
                                          Map<String, String> mergeTargets,
                                          Map<String, String> exactTargets,
                                          WikiBatchContext batchCtx) {
        List<ExtractedItem> es = dedup.stabilizeExtractedIdentities(
                kbId, WikiConstants.PAGE_TYPE_ENTITY, entities, mergeTargets, exactTargets, batchCtx);
        List<ExtractedItem> cs = dedup.stabilizeExtractedIdentities(
                kbId, WikiConstants.PAGE_TYPE_CONCEPT, concepts, mergeTargets, exactTargets, batchCtx);
        return new ExtractedProjection(es, cs);
    }

    /** 对照 Go {@code deduplicateExtractedBatch} 的 {@code ([]extractedItem, []extractedItem)} 返回。 */
    public record ExtractedProjection(List<ExtractedItem> entities, List<ExtractedItem> concepts) {}

    // ═══════════════════════════════════════════════════════════════
    // 带模板的 LLM 调用（对照 Go L2504-2692）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code generateWithTemplate}（L2521-2644）：执行一个 prompt 模板，
     * 并对瞬时基础设施错误做<b>有界的指数退避重试</b>。
     *
     * <h2>重试策略</h2>
     * <ul>
     *   <li>总计最多 {@code LLM_MAX_ATTEMPTS}(3) 次尝试（首次 + 重试）；</li>
     *   <li>只重试 {@link WikiLlmRetryPolicy#isTransientLlmError} 判为瞬时的错误：
     *       HTTP 408/429/5xx、父作用域仍存活时的 context deadline exceeded、
     *       以及通用的 "timeout"/"connection reset" 措辞。4xx（除 408/429）
     *       是调用方自己的问题，快速失败；</li>
     *   <li>退避指数基数 2 秒：2s、4s、8s（{@code base << (attempt-1)}）；
     *       可被线程中断打断，让任务能及时退出。</li>
     * </ul>
     *
     * <p><b>存在理由</b>：wiki ingest 每篇文档要发好几次独立 LLM 调用
     * （抽取、摘要、去重、引用、导语），上游网关一次瞬时 504 过去会<b>永久</b>丢掉该文档的
     * 摘要页。重试加上 failedOps 重排队（见 {@code requeueFailedOps}）把这类事件
     * 变成至多几分钟的抖动。</p>
     *
     * <h2>消息布局（provider 前缀缓存的关键）</h2>
     * <ul>
     *   <li>{@code WikiPageModifyUserPrompt} 走<b>两条消息</b>：稳定的规则做 system、
     *       逐页数据做 user——规则因此可跨 reduce 批次缓存；</li>
     *   <li>其余模板只有一条 user 消息，业务指引追加在其后；</li>
     *   <li>两者的业务指引都由 {@link WikiPromptInstructions} 以同样的措辞追加。</li>
     * </ul>
     *
     * <h2>图片脱敏</h2>
     * {@code data} 的每个字段在渲染<b>之前</b>统一脱敏，跨字段共享同一份 URL→token 映射，
     * 因此同一个 URL 出现在多个字段里也拿到同一个占位符；返回内容统一还原、
     * 并丢弃模型编造或弄坏的占位符。
     */
    public String generateWithTemplate(LlmChatClient chatModel, String promptTpl,
                                       Map<String, String> data) {
        Map<String, String> safeData = data == null ? Map.of() : data;

        WikiImageMarkup.MaskedTemplateData maskedData =
                WikiImageMarkup.maskTemplateDataImageURLs(safeData);
        Map<String, String> fields = maskedData.masked();
        String prompt = WikiPromptTemplate.render(promptTpl, fields);

        String purpose = WikiPrompts.purposeOf(promptTpl);
        List<ChatMessage> messages = new ArrayList<>(2);
        if (WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT.equals(promptTpl)) {
            String systemPrompt = WikiPromptInstructions.appendCustomPromptInstructions(
                    WikiPrompts.WIKI_PAGE_MODIFY_SYSTEM_PROMPT,
                    fields.get("CustomInstructions"), fields.get("InstructionScope"));
            messages.add(ChatMessage.system(systemPrompt));
            messages.add(ChatMessage.user(prompt));
        } else {
            messages.add(ChatMessage.user(WikiPromptInstructions.appendCustomPromptInstructions(
                    prompt, fields.get("CustomInstructions"), fields.get("InstructionScope"))));
        }

        ChatOptions opts = new ChatOptions();
        opts.setTemperature(0.3);
        opts.setThinking(Boolean.FALSE);
        opts.setMaxTokens(WikiIngestConstants.LLM_MAX_TOKENS);

        String prefixFingerprint = PromptCache.promptPrefixFingerprint(messages, opts);
        String warmupKey = "";
        Long tenantId = TenantContext.currentTenantId();
        boolean tenantScoped = tenantId != null;
        if (WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT.equals(promptTpl)) {
            // 页面修改走"system 消息 + 共享源上下文"作为缓存前缀：
            // 同一源文档产出的所有页面共享它，页面元数据在它之后才分叉。
            prefixFingerprint = PromptCache.fingerprintPromptPrefix(
                    messages.get(0).getContent(), fields.getOrDefault("SharedSourceContexts", ""));
            if (tenantScoped) {
                warmupKey = PromptCache.buildPromptCacheKey(
                        tenantId, chatModel.getModelId(), purpose, prefixFingerprint);
            }
        }

        // 对照 Go 的 types.WithLLMCallMetadata：把记账元数据挂到执行线程上
        String effectivePrefixFingerprint = prefixFingerprint;
        final String resolvedWarmupKey = warmupKey;
        String requestKey = PromptCache.buildPromptCacheKey(
                tenantScoped ? tenantId : 0L,
                chatModel.getModelId(),
                "wiki_exact_request",
                PromptCache.fingerprintPromptPrefix(serializeRequest(messages, opts)));

        java.util.concurrent.Callable<Object> execute = () -> {
            WikiLlmCallMetadata.set(purpose, effectivePrefixFingerprint);

            // 用长度为 1 的数组承载 release 句柄：lambda 里不能给局部变量重新赋值
            // （对照 Go 的 defer releaseWarmup()，defer 的接收者是可变变量）
            Runnable[] warmupHolder = { () -> { } };
            boolean holdsWarmup = tenantScoped
                    && WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT.equals(promptTpl)
                    && !GoStrings.trimSpace(fields.getOrDefault("SharedSourceContexts", "")).isEmpty();
            if (holdsWarmup) {
                warmupHolder[0] = awaitWikiPromptWarmup(resolvedWarmupKey);
            }
            try {
                Exception lastErr = null;
                for (int attempt = 1; attempt <= WikiIngestConstants.LLM_MAX_ATTEMPTS; attempt++) {
                    ChatResponse response = null;
                    Exception callErr = null;
                    try {
                        response = chatModel.chat(messages, opts);
                    } catch (Exception e) {
                        callErr = e;
                    }
                    if (callErr == null && response != null) {
                        return response.getContent() == null ? "" : response.getContent();
                    }
                    if (callErr == null) {
                        callErr = new IllegalStateException("LLM returned nil response");
                    }
                    lastErr = callErr;

                    if (!WikiLlmRetryPolicy.isTransientLlmError(
                            Thread.currentThread().isInterrupted(), callErr)) {
                        throw new IllegalStateException("LLM call failed: " + callErr.getMessage(), callErr);
                    }
                    if (attempt == WikiIngestConstants.LLM_MAX_ATTEMPTS) {
                        break;
                    }
                    Duration backoff = WikiIngestConstants.llmBackoff(attempt);
                    log.warn("wiki ingest: LLM call failed (attempt {}/{}), retrying in {}s: {}",
                            attempt, WikiIngestConstants.LLM_MAX_ATTEMPTS,
                            backoff.toSeconds(), callErr.getMessage());
                    try {
                        Thread.sleep(backoff.toMillis());
                    } catch (InterruptedException ie) {
                        // 对照 Go 的 ctx.Done() 分支：任务正在取消，不再退避
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "LLM call aborted during backoff: interrupted", ie);
                    }
                }
                throw new IllegalStateException("LLM call failed after "
                        + WikiIngestConstants.LLM_MAX_ATTEMPTS + " attempts: "
                        + (lastErr == null ? "" : lastErr.getMessage()), lastErr);
            } finally {
                WikiLlmCallMetadata.clear();
                warmupHolder[0].run();
            }
        };

        String content;
        if (!tenantScoped) {
            // 缺少租户上下文对生产 wiki 工作是异常情况。安全起见<b>跳过跨调用合并</b>，
            // 而不是把不相关的请求塞进一个合成的 tenant-0 桶里。
            try {
                content = (String) execute.call();
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            return WikiImageMarkup.unmaskImageURLs(content, maskedData.tokenToUrl());
        }

        try {
            CompletableFuture<Object> result = llmRequests.doChan(requestKey, execute);
            content = (String) SingleFlight.await(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM call aborted: interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return WikiImageMarkup.unmaskImageURLs(content, maskedData.tokenToUrl());
    }

    /**
     * 对照 Go 的 {@code requestJSON, _ := json.Marshal(struct{Messages; Options})}（L2569-2572）：
     * 把消息与选项序列化成"精确请求"指纹的输入。
     *
     * <p>字段序分别是 {@code messages, options}（Go struct 声明序）。Java 侧用
     * {@code ChatMessage} / {@code ChatOptions} 自身的 {@code @JsonPropertyOrder} 与
     * omitempty 注解产出同样的形状，因此指纹在两侧的意义一致。
     * 该键只用于<b>进程内</b>的跨调用合并，不落库、不外泄。</p>
     */
    private static String serializeRequest(List<ChatMessage> messages, ChatOptions opts) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode messagesNode = root.putArray("messages");
        for (ChatMessage m : messages) {
            messagesNode.add(MAPPER.valueToTree(m));
        }
        root.set("options", MAPPER.valueToTree(opts));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 对照 Go {@code awaitWikiPromptWarmup}（L2669-2692）：只串行化同一个可复用
     * Wiki 页面前缀的<b>首个</b>请求。
     *
     * <p>leader（第一个到达的调用）拿到一个 release 句柄，<b>必须</b>在它的 LLM 调用
     * 结束后调用（Go 用 {@code defer releaseWarmup()}）；跟随者会阻塞到 leader 释放。
     * 释放后本地的"已预热"标记保留 4 分钟（覆盖并行的 reduce 突发），
     * 然后被回收——它不该变成常驻的应用级缓存。</p>
     *
     * @return release 句柄
     * @throws InterruptedException 等待期间线程被中断（对照 Go 的 {@code ctx.Done()} 分支）
     */
    public Runnable awaitWikiPromptWarmup(String key) throws InterruptedException {
        if (key == null || key.isEmpty()) {
            return () -> { };
        }
        PromptWarmup candidate = new PromptWarmup();
        PromptWarmup existing = promptWarmups.putIfAbsent(key, candidate);
        if (existing == null) {
            // leader
            return () -> {
                if (candidate.closed.compareAndSet(false, true)) {
                    candidate.done.complete(null);
                }
                // 保持本地"已预热"标记足够久以覆盖并行的 reduce 突发，
                // 又不至于变成常驻应用缓存
                warmupReaper.schedule(() -> promptWarmups.remove(key, candidate),
                        4, TimeUnit.MINUTES);
            };
        }
        // 跟随者：等 leader 完成（对照 Go 的 select ctx.Done / entry.done）
        try {
            existing.done.get();
        } catch (InterruptedException e) {
            // 对照 Go 的 ctx.Done() 分支：等待期间被取消
            Thread.currentThread().interrupt();
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            // leader 的 future 不会异常完成（只 complete(null)），走到这里说明装配错了
            throw new IllegalStateException("prompt warmup gate failed", e.getCause());
        }
        return () -> { };
    }

    /** 供测试/可观测：当前的预热标记数 */
    public int promptWarmupCount() {
        return promptWarmups.size();
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助（对照 Go L2789-2909）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code isKnowledgeGone}（L2797-2815）：知识文档是否已删除、或正在删除中。
     *
     * <p>先查墓碑快路径，再回落数据库。{@code GetKnowledgeByIDOnly} 返回 nil 同样算
     * "已消失"：仓储层用 {@code First()} 过滤软删行，因此软删的知识在这里表现为
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
            // 无知识仓储可查 —— 无法判定，保守地当作"还在"
            // （对照 Go 只有 knowledgeSvc 一定存在，此处是 Java 侧的装配差异）
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
     * 对照 Go {@code filterLiveUpdates}（L2821-2855）：丢弃那些源知识在 Map 阶段结束后
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
     * 对照 Go {@code reconstructContent}（L2865-2875）：从 chunk 重建文档正文。
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

    /** 对照 Go {@code types.ChunkTypeText}（= "text"） */
    public static final String CHUNK_TYPE_TEXT = "text";

    /** 对照 Go {@code types.ChunkTypeImageOCR}（= "image_ocr"） */
    public static final String CHUNK_TYPE_IMAGE_OCR = "image_ocr";

    /** 对照 Go {@code types.ChunkTypeImageCaption}（= "image_caption"） */
    public static final String CHUNK_TYPE_IMAGE_CAPTION = "image_caption";

    /**
     * 对照 Go {@code reconstructEnrichedContent}（L2883-2909）：重建正文并把
     * 图片的 OCR / caption 文本内联进来。
     *
     * <p>没有图片信息时返回纯文本重建结果——这<b>正是</b> Go 在
     * {@code mergedImageInfo == ""} 或没有文本 chunk 时的行为，因此
     * {@link WikiImageEnricher} 未接线时的退化路径与 Go 的等价路径完全重合。</p>
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
     * 对照 Go {@code beginWikiSubspan}（L453-466）：为该文档在知识追踪树下开一个
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
