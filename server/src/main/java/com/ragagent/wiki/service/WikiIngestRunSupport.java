package com.ragagent.wiki.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.service.SpanTracker;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.controller.WikiActivityAudit;
import com.ragagent.wiki.domain.WikiConfig;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * wiki 批次摄取的运行管道协作者:processWikiFinalize 的执行体、ingest 的
 * 声明/正文/分阶段执行、map retract 对账与未应用集合回收。
 *
 * <p>持有 {@link WikiIngestBatchHandler} 回引以访问其依赖字段;本类不得独立实例化。</p>
 */
final class WikiIngestRunSupport {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestRunSupport.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    final WikiIngestBatchHandler handler;
    final WikiIngestFinalizePhase finalize;

    WikiIngestRunSupport(WikiIngestBatchHandler handler) {
        this.handler = handler;
        this.finalize = new WikiIngestFinalizePhase(this);
    }

    /**
     * ingest 入口：Lite 模式取按 KB 的进程内独占锁；
     * Standard 模式不取任何按 KB 的独占锁（并发安全靠认领与 slug 锁）。
     */
    void runIngest(WikiIngestPayload payload, WikiIngestBatchHandler.Stats stats) {
        if (handler.ingestService.isLiteMode()) {
            stats.mode = "lite";
            if (!handler.ingestService.tryAcquireLiteLock(payload.knowledgeBaseId())) {
                stats.exitStatus = "active_lock_conflict";
                log.info("wiki ingest: another batch active for KB {} (lite lock), deferring to retry",
                        payload.knowledgeBaseId());
                throw new WikiIngestConstants.ConcurrentTaskActiveException();
            }
            try {
                runIngestBody(payload, stats);
            } finally {
                handler.ingestService.releaseLiteLock(payload.knowledgeBaseId());
            }
            return;
        }
        runIngestBody(payload, stats);
    }

    /** 批次体第一步：KB 校验、模型解析、可调参数、在途上限、认领 */
    void runIngestBody(WikiIngestPayload payload, WikiIngestBatchHandler.Stats stats) {
        String kbId = payload.knowledgeBaseId();

        KnowledgeBase kb = handler.getKnowledgeBaseByIDOnly(kbId);
        if (kb == null) {
            stats.exitStatus = "kb_deleted";
            handler.ingestService.clearDeletedKnowledgeBasePendingOps(kbId);
            return;
        }
        if (!kb.getIndexingStrategy().isWikiEnabled()) {
            stats.exitStatus = "kb_not_wiki_enabled";
            throw new IllegalStateException("wiki ingest: KB " + kb.getId() + " is not wiki type");
        }

        WikiConfig wikiConfig = WikiIngestBatchHandler.wikiConfigOf(kb);
        String synthesisModelId = wikiConfig == null ? "" : WikiIngestBatchHandler.nullToEmpty(wikiConfig.getSynthesisModelId());
        if (synthesisModelId.isEmpty()) {
            synthesisModelId = WikiIngestBatchHandler.nullToEmpty(kb.getSummaryModelId());
        }
        if (synthesisModelId.isEmpty()) {
            stats.exitStatus = "missing_synthesis_model";
            throw new IllegalStateException(
                    "wiki ingest: no synthesis model configured for KB " + kb.getId());
        }
        LlmChatClient chatModel;
        try {
            chatModel = handler.modelResolver.getChatModel(synthesisModelId);
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

        // 每 KB 的在途上限（standard 模式）：别让一个 KB 的批量导入独占总池。
        // 若该 KB 已经到顶，就排一个合并的重试并<b>不认领任何行</b>地退出，
        // 让这些行留给先腾出槽位的那个运行中批次。
        stats.maxInflight = WikiConfig.ingestMaxInflightOrDefault(
                wikiConfig, WikiIngestConstants.INFLIGHT_DEFAULT);
        WikiInflightLimiter.Reservation reservation =
                handler.ingestService.reserveInflightSlot(kbId, stats.maxInflight);
        if (!reservation.granted()) {
            stats.exitStatus = "inflight_cap";
            log.info("wiki ingest: KB {} at in-flight cap ({}), rescheduling",
                    kbId, stats.maxInflight);
            handler.ingestService.scheduleCappedRetry(payload);
            return;
        }
        try {
            runIngestClaimed(payload, kb, wikiConfig, chatModel, stats);
        } finally {
            reservation.releaseQuietly();
        }
    }

    /** 认领 + 崩溃安全网 */
    void runIngestClaimed(WikiIngestPayload payload,
                                  KnowledgeBase kb,
                                  WikiConfig wikiConfig,
                                  LlmChatClient chatModel,
                                  WikiIngestBatchHandler.Stats stats) {
        String kbId = payload.knowledgeBaseId();

        // standard 模式认领行（盖 claimed_at，并发批次之间互不相交）；
        // Lite 模式在进程内锁下窥视。
        boolean standardMode = !handler.ingestService.isLiteMode();
        WikiIngestService.PendingBatch claimed = standardMode
                ? handler.ingestService.claimPendingList(kbId, stats.batchSize)
                : handler.ingestService.peekPendingList(kbId, stats.batchSize);
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
            stats.followUpScheduled = handler.ingestService.scheduleStaleClaimRecheck(payload);
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
                    // 用有界的<b>脱钩</b>清理路径：执行线程可能已因超时被中断。
                    try (WikiCleanupScope scope = handler.ingestService.cleanupScope()) {
                        scope.run(() -> handler.pendingRepo.releaseByIds(peekedIds));
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

    /** Map → 目录规划 → Reduce → 收尾结算 */
    void runIngestPhases(WikiIngestPayload payload,
                                 KnowledgeBase kb,
                                 WikiConfig wikiConfig,
                                 LlmChatClient chatModel,
                                 List<WikiPendingOp> pendingOps,
                                 List<Long> peekedIds,
                                 WikiIngestBatchHandler.Stats stats) {
        String kbId = payload.knowledgeBaseId();
        String lang = WikiLanguageSupport.languageNameFromContext();

        WikiBatchContext batchCtx = handler.newWikiBatchContext(kbId, wikiConfig);

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
                    WikiIngestBatchHandler.MapResult mr = handler.map.mapOneDocument(chatModel, payload, op, batchCtx);
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
                    handler.ingestService.finalizeWikiSubtask(op.getKnowledgeId());
                }
            });
        }
        WikiBatchSupport.fanOut(stats.mapParallel, mapBodies);

        // 每个 map worker 都选完 slug 之后重读身份认领，让同一标题的并发罗马化在目录规划
        // 与 Reduce 按 slug 加锁之前收敛。
        Map<String, List<SlugUpdate>> remappedSlugUpdates =
                handler.dedupService.remapSlugUpdatesByIdentity(kbId, slugUpdates, batchCtx);

        // 在 reduce 之前为整批规划一次目录。Reduce 并行写页，自己无法在共享目录上收敛；
        // 这一遍给每个新的 entity/concept slug 分配一个连贯的 category_path，并复用既有
        // 目录。Reduce 随后只把计划应用到<b>尚未归档</b>的页面上（用户策展过的页面永远
        // 不被搅动）。
        batchCtx.setPlannedFolderId(handler.taxonomy.resolvePlannedFolders(kb,
                handler.taxonomy.planBatchTaxonomy(chatModel, kb, remappedSlugUpdates, lang, handler.ingestService)));

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

        // reduce 的页级 span 归属映射：kid → 该文档的 wikiSpan
        Map<String, SpanTracker.SpanHandle> kidToWikiMap = new LinkedHashMap<>();
        for (DocIngestResult r : docResults) {
            if (r != null && r.getWikiSpan() != null) {
                kidToWikiMap.put(r.getKnowledgeId(), r.getWikiSpan());
            }
        }

        List<Runnable> reduceBodies = new ArrayList<>(remappedSlugUpdates.size());
        for (Map.Entry<String, List<SlugUpdate>> entry : remappedSlugUpdates.entrySet()) {
            final String slug = entry.getKey();
            final List<SlugUpdate> updates = entry.getValue();
            reduceBodies.add(() -> {
                Reduced[] outcome = { Reduced.NONE };
                boolean acquired;
                try {
                    acquired = handler.ingestService.withSlugLock(kbId, slug, () -> {
                        WikiIngestBatchHandler.ReduceOutcome r = handler.reduce.reduceSlugUpdates(chatModel, kbId, slug, updates,
                                payload.tenantId(), batchCtx, kidToWikiMap);
                        outcome[0] = new Reduced(r);
                    });
                } catch (RuntimeException lockErr) {
                    // 锁协调层故障：安静停下，slug 记入未应用集合。
                    // 注意：reduce 自身的错误以前也曾被 throw 进这个 catch 一并吞掉，
                    // 其 "reduce failed for slug" warn 从未执行（2026-09-24 修复）。
                    log.warn("wiki ingest: slug lock failed for slug {}: {}", slug, lockErr.getMessage());
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
                WikiIngestBatchHandler.ReduceOutcome r = outcome[0].value();
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
            handler.ingestService.sanitizeDeadSummaryLinks(kbId, docResults, failedAdditionSlugs, batchCtx);
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
        WikiActivityAudit audit = handler.auditProvider.getIfAvailable();
        if (audit != null) {
            try (WikiCleanupScope scope = handler.ingestService.cleanupScope()) {
                scope.run(() -> audit.wikiContentChanged(payload.tenantId(), kbId, wikiActivityActions));
            } catch (Exception e) {
                log.warn("wiki ingest: record content activity failed: {}", e.getMessage());
            }
        }

        // 立即发布刚生成的页面（<b>不</b>推迟到 finalize）：用户应当在文档内容写出的第一
        // 时间看到它的 wiki 页面，而不是等到防抖窗口之后。这是一次便宜的状态翻转。
        if (!allPagesAffected.isEmpty()) {
            log.info("wiki ingest: publishing draft pages");
            handler.ingestService.publishDraftPages(kbId, allPagesAffected);
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
            handler.ingestService.enqueueFinalize(payload, allPagesAffected, freshTitleBySlug,
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
                handler.ingestService.finalizeWikiSubtask(r.getKnowledgeId());
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
            handler.spans.endSpan(r.getWikiSpan(), output);
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
        handler.ingestService.trimPendingListDetached(trimIds);

        // 处理失败的 op：fail_count 加一，达到上限就进死信。<b>必须</b>在 trim 之后跑，
        // 这样成功的兄弟行已经从队列里消失——否则后续批次可能重新拾起它们。
        if (!failedOps.isEmpty()) {
            List<Exception> settleErrors = handler.ingestService.requeueFailedOpsDetached(payload, failedOps);
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
        stats.followUpScheduled = handler.scheduleFollowUp(payload, followUpDelay);
    }

    /** 让 reduce 的闭包能把"可能被 catch 掉的结果"带出来（Java 的 lambda 捕获限制） */
    record Reduced(WikiIngestBatchHandler.ReduceOutcome value) {
        static final Reduced NONE = new Reduced(new WikiIngestBatchHandler.ReduceOutcome(false, "", false, null));
    }

    /**
     * retract op 的 map 阶段处理：在运行期解析权威页面集合。
     *
     * <p>调用方（{@code cleanupWikiOnKnowledgeDelete}）从任务触发<b>之前</b>的 DB 快照
     * 采集 PageSlugs，但存在一个窗口：清理跑在 ingest 之前时快照为空、而并发 ingest
     * 可能已经建出页面；或者上一次 ingest 批次在快照之后建了新页面。
     * 在这里重新查 {@code ListPagesBySourceRef}，把调用方的 slug 与当前任何引用该知识的
     * 页面求并集，从而没有页面会被漏掉。它也让我们支持"故意用空 PageSlugs 入队 retract"
     * 的调用方——即"自己去搞清楚"。</p>
     */
    void mapRetractOp(WikiIngestPayload payload,
                              WikiPendingOp op,
                              Object mapMu,
                              Map<String, List<SlugUpdate>> slugUpdates,
                              List<String> retractFolderIDs,
                              WikiIngestBatchHandler.Stats stats) {
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
                livePages = handler.wikiService.listPagesBySourceRef(
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

    /** 把"更新未落地"的 slug 对应的 knowledge_id 记入未应用集合 */
    static void collectUnapplied(Object reduceMu, Set<String> unappliedSlugKIDs,
                                         List<SlugUpdate> updates) {
        synchronized (reduceMu) {
            for (SlugUpdate u : updates) {
                if (!u.getKnowledgeId().isEmpty()) {
                    unappliedSlugKIDs.add(u.getKnowledgeId());
                }
            }
        }
    }


}
