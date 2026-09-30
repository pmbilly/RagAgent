package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.domain.WikiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ragagent.common.wiki.WikiLanguageSupport;

/**
 * wiki 批次摄取的 Finalize 阶段协作者:终稿落库与批次收尾(从 RunSupport 分解)。
 *
 * <p>持有 {@link WikiIngestRunSupport} 回引;本类不得独立实例化。</p>
 */
final class WikiIngestFinalizePhase {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestFinalizePhase.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WikiIngestRunSupport run;
    private final WikiIngestBatchHandler handler;

    WikiIngestFinalizePhase(WikiIngestRunSupport run) {
        this.run = run;
        this.handler = run.handler;
    }

    /**
     * 跑防抖的、按 KB 的
     * KB 级收敛：索引导语重建、死链清理、交叉链接注入。它排空
     * {@code task_pending_ops} 的 finalize 通道（由 ingest 批次经
     * {@code enqueueFinalize} 写入），让 N 篇文档的突发只重建索引<b>一次</b>，
     * 而不是每个 5 文档批次一次。
     */
    public void processWikiFinalize(WikiIngestPayload payload) {
        long startedAt = System.currentTimeMillis();
        String kbId = payload.knowledgeBaseId();
        if (handler.pendingRepo == null) {
            return;
        }

        // 按 KB 的 finalize 锁，与 ingest 的 active 锁分离，因此 finalize 与 ingest
        // 批次永不互相阻塞。稳定 TaskID 的合流已经保证每个 KB 最多一个 finalize 待执行；
        // 这道锁守护"重排重叠窗口"里与并发索引页写入的竞争。
        WikiFinalizeLock.AcquireResult acquired = handler.finalizeLock.tryAcquire(kbId);
        if (acquired == WikiFinalizeLock.AcquireResult.FAILED) {
            // fail CLOSED：无锁执行会让两次 finalize 排空同一批待办行并重复重建
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
            handler.finalizeLock.release(kbId);
        }
    }

    void runFinalize(WikiIngestPayload payload, long startedAt) {
        String kbId = payload.knowledgeBaseId();
        List<TaskPendingOp> rows;
        try {
            rows = handler.pendingRepo.peekBatch(WikiIngestConstants.FINALIZE_TASK_TYPE,
                    WikiIngestConstants.TASK_SCOPE, kbId, WikiIngestConstants.FINALIZE_MAX_ROWS);
        } catch (Exception e) {
            throw new IllegalStateException("wiki finalize: peek: " + e.getMessage(), e);
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }

        KnowledgeBase kb = handler.getKnowledgeBaseByIDOnly(kbId);
        if (kb == null) {
            handler.ingestService.clearDeletedKnowledgeBasePendingOps(kbId);
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
                            .append(WikiIngestBatchHandler.nullToEmpty(row.change().docTitle()))
                            .append("</title>\n<summary>")
                            .append(WikiIngestBatchHandler.nullToEmpty(row.change().docSummary()))
                            .append("</summary>\n</document_removed>\n\n");
                } else {
                    changeDesc.append("<document_added>\n<title>")
                            .append(WikiIngestBatchHandler.nullToEmpty(row.change().docTitle()))
                            .append("</title>\n<summary>")
                            .append(WikiIngestBatchHandler.nullToEmpty(row.change().docSummary()))
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
            handler.ingestService.trimPendingListDetached(ids);
            return;
        }

        WikiConfig wikiConfig = WikiIngestBatchHandler.wikiConfigOf(kb);
        String synthesisModelId = wikiConfig == null ? "" : WikiIngestBatchHandler.nullToEmpty(wikiConfig.getSynthesisModelId());
        if (synthesisModelId.isEmpty()) {
            synthesisModelId = WikiIngestBatchHandler.nullToEmpty(kb.getSummaryModelId());
        }
        if (synthesisModelId.isEmpty()) {
            // 没有模型可用于重建索引；仍跑纯文本遍历，然后排空。
            // 缺模型是配置缺口，不是瞬时错误。
            log.warn("wiki finalize: no synthesis model for KB {}, skipping index rebuild", kbId);
        }

        WikiBatchContext batchCtx = handler.newWikiBatchContext(kbId, wikiConfig);
        String lang = WikiLanguageSupport.languageNameFromContext();

        boolean indexRebuilt = false;
        if (changeDesc.length() > 0 && !synthesisModelId.isEmpty()) {
            LlmChatClient chatModel = null;
            try {
                chatModel = handler.modelResolver.getChatModel(synthesisModelId);
            } catch (RuntimeException e) {
                log.warn("wiki finalize: get chat model failed: {}", e.getMessage());
            }
            if (chatModel != null) {
                try {
                    handler.ingestService.rebuildIndexPage(chatModel, payload, changeDesc.toString(), lang,
                            batchCtx.getContentInstructions());
                    indexRebuilt = true;
                } catch (Exception e) {
                    log.warn("wiki finalize: rebuild index failed: {}", e.getMessage());
                }
            }
        }

        if (!affectedSlugs.isEmpty()) {
            handler.ingestService.cleanDeadLinks(kbId, affectedSlugs, batchCtx);
            handler.ingestService.injectCrossLinks(kbId, affectedSlugs, freshRefs, batchCtx);
        }

        // 一次 retract 可能留下一个或多个变空的生成目录。在该 KB 还有任何 ingest 行排队
        // 或已认领时<b>不要</b>剪枝：handler.taxonomy 规划会在 reduce 写页面<b>之前</b>创建目录，
        // 因此一个看似空的目录仍可能被在途批次拥有。持久化的 prune 行留在 finalize 通道
        // 里，等 ingest 通道排空后重试。
        boolean pruneDeferred = false;
        int deletedFolders = 0;
        if (!folderPruneIDs.isEmpty()) {
            Long pending = null;
            try {
                pending = handler.pendingRepo.pendingCount(WikiIngestConstants.TASK_TYPE,
                        WikiIngestConstants.TASK_SCOPE, kbId);
            } catch (Exception e) {
                log.warn("wiki finalize: cannot verify ingest drain before folder prune: {}",
                        e.getMessage());
            }
            if (pending == null || pending > 0) {
                pruneDeferred = true;
            } else {
                try {
                    List<String> deleted = handler.wikiService.pruneEmptyFolderChains(
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
        handler.ingestService.trimPendingListDetached(idsToTrim);

        // 如果在我们干活期间又有 finalize 行落进来，就重排，让它们得到自己的收敛遍。
        boolean rescheduled = false;
        if (pruneDeferred) {
            handler.ingestService.scheduleFinalizeRetry(payload);
            rescheduled = true;
        }
        long remaining;
        try {
            remaining = handler.pendingRepo.pendingCount(WikiIngestConstants.FINALIZE_TASK_TYPE,
                    WikiIngestConstants.TASK_SCOPE, kbId);
        } catch (Exception e) {
            remaining = 0;
        }
        if (remaining > 0) {
            if (!pruneDeferred) {
                handler.ingestService.scheduleFinalize(payload);
            }
            rescheduled = true;
        }

        log.info("wiki finalize: kb={} rows={} affected_slugs={} deleted_folders={} "
                        + "folder_prune_deferred={} index_rebuilt={} rescheduled={} elapsed={}ms",
                kbId, rows.size(), affectedSlugs.size(), deletedFolders, pruneDeferred,
                indexRebuilt, rescheduled, System.currentTimeMillis() - startedAt);
    }
}
