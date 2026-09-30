package com.ragagent.wiki.service.ingest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.common.wiki.GoStrings;
import com.ragagent.common.wiki.WikiIngestPort;
import com.ragagent.common.wiki.WikiLanguageSupport;
import com.ragagent.wiki.domain.TaskPendingOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 摄取的写面：待办 op 持久化（task_pending_ops）、ingest/retract 投递、
 * finalize 通道与各路防抖触发调度。经由门面 WikiIngestService 的包内字段访问仓储与队列。
 */
final class WikiIngestEnqueueOps {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestEnqueueOps.class);

    private final WikiIngestService service;

    WikiIngestEnqueueOps(WikiIngestService service) {
        this.service = service;
    }

    // ── 投递：ingest / retract ──

    /**
     * 把 op 持久化到
     * {@code task_pending_ops}。
     *
     * <p>设计上有原子守卫（"KB 仍活跃才入队"），但该守卫尚未接线
     * （它属于 knowledge 模块的删除路径），因此当前是普通入队。</p>
     */
    boolean enqueueWikiPendingOp(TaskPendingOp op) {
        if (service.pendingRepo == null) {
            // pendingRepo 缺席：直接视为已接受
            return true;
        }
        service.pendingRepo.enqueue(op);
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
    WikiIngestPort.EnqueueResult enqueueWikiIngest(long tenantId, String kbId, String knowledgeId) {
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
    TaskPendingOp newWikiIngestPendingOp(long tenantId, String kbId, String knowledgeId) throws Exception {
        WikiPendingOp op = new WikiPendingOp(WikiIngestConstants.OP_INGEST, knowledgeId);
        op.setLanguage(WikiLanguageSupport.languageFromContextOrDefault());

        TaskPendingOp row = new TaskPendingOp();
        row.setTenantId(tenantId);
        row.setTaskType(WikiIngestConstants.TASK_TYPE);
        row.setScope(WikiIngestConstants.TASK_SCOPE);
        row.setScopeId(kbId);
        row.setOp(WikiIngestConstants.OP_INGEST);
        row.setDedupKey(knowledgeId);
        row.setPayload(WikiIngestService.MAPPER.valueToTree(op));
        return row;
    }

    /**
     * 调度防抖的批次触发。
     *
     * <p>任务参数：MaxRetry 10、Timeout 60 分钟、ProcessIn 30 秒
     * （{@link WikiIngestConstants#INGEST_DELAY}）。</p>
     */
    void enqueueWikiIngestTrigger(long tenantId, String kbId) {
        // 入队侧注入：把当前
        // 请求的 traceparent 打进负载，worker 侧续接同一棵树
        WikiIngestPayload trigger = WikiIngestPayload.withTracing(
                tenantId, kbId, WikiLanguageSupport.languageFromContextOrDefault(),
                com.ragagent.tracing.langfuse.LangfuseTracing.inject());
        WikiIngestTaskQueue queue = service.taskQueue.getIfAvailable();
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
    void enqueueWikiRetract(WikiRetractPayload payload) {
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
        row.setPayload(WikiIngestService.MAPPER.valueToTree(op));

        boolean accepted = enqueueWikiPendingOp(row);
        if (!accepted) {
            log.info("wiki retract: skip enqueue for deleted KB {}", payload.knowledgeBaseId());
            return;
        }

        WikiIngestPayload trigger = WikiIngestPayload.withTracing(
                payload.tenantId(), payload.knowledgeBaseId(), payload.language(),
                com.ragagent.tracing.langfuse.LangfuseTracing.inject());
        WikiIngestTaskQueue queue = service.taskQueue.getIfAvailable();
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

    // ── Finalize 通道 ──

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
    void enqueueFinalize(WikiIngestPayload payload,
                                List<String> affectedSlugs,
                                Map<String, String> freshTitleBySlug,
                                List<WikiFinalizeChange> changes,
                                List<String> folderIds) {
        if (service.pendingRepo == null) {
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
        entity.setPayload(WikiIngestService.MAPPER.valueToTree(row));
        return entity;
    }

    /**
     * 去空白、去重、保序。
     */
    static List<String> uniqueWikiFolderIDs(List<String> values) {
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
    void scheduleFinalize(WikiIngestPayload payload) {
        WikiIngestTaskQueue queue = service.taskQueue.getIfAvailable();
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
    void scheduleFinalizeRetry(WikiIngestPayload payload) {
        WikiIngestTaskQueue queue = service.taskQueue.getIfAvailable();
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
    void scheduleCappedRetry(WikiIngestPayload payload) {
        WikiIngestTaskQueue queue = service.taskQueue.getIfAvailable();
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
    boolean scheduleStaleClaimRecheck(WikiIngestPayload payload) {
        long count;
        try {
            count = service.pendingRepo.pendingCount(
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

        WikiIngestTaskQueue queue = service.taskQueue.getIfAvailable();
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

    // ── 内部工具 ──

    // ═══════════════════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════════════════

    private static String toJson(Object value) {
        try {
            return WikiIngestService.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize wiki payload", e);
        }
    }
}
