package com.ragagent.wiki.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.wiki.domain.WikiActivityAudit;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
import com.ragagent.common.wiki.ExtractedItem;
import com.ragagent.common.wiki.SlugUpdate;
import com.ragagent.wiki.service.WikiModelResolver;
import com.ragagent.wiki.service.page.WikiPageService;

/**
 * {@link WikiIngestBatchHandler} 的对等测试（对照 Go
 * internal/application/service/wiki_folder_prune_finalize_test.go（122 行）与
 * wiki_deleted_kb_guard_test.go（175 行）），外加批次主干里几个可独立验证的纯函数
 * （{@code mergeChunkRefs}、粒度映射、速率限制错误分类、内联 chunk 引用剥离）。
 *
 * <p><b>范围说明</b>：Go 的 {@code TestEnqueueWikiWorkSkipsDeletedKnowledgeBase} 与
 * {@code TestEnqueueWikiFinalizeOnlySchedulesAcceptedRows} 针对的是
 * {@code EnqueueWikiIngest} / {@code EnqueueWikiRetract} / {@code enqueueFinalize}
 * ——那些函数在上一轮的 {@link WikiIngestService} 里，本轮的
 * {@code finalize} 通道测试因此只覆盖<b>排空</b>侧（{@code ProcessWikiFinalize}）。</p>
 */
class WikiIngestBatchHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WikiIngestService ingestService = mock(WikiIngestService.class);
    private final WikiPageService wikiService = mock(WikiPageService.class);
    private final TaskPendingOpsRepository pendingRepo = mock(TaskPendingOpsRepository.class);
    private final WikiIngestCitePipeline citePipeline = mock(WikiIngestCitePipeline.class);
    private final WikiIngestTaxonomy taxonomy = mock(WikiIngestTaxonomy.class);
    private final WikiIngestDedupService dedupService = mock(WikiIngestDedupService.class);
    private final WikiModelResolver modelResolver = mock(WikiModelResolver.class);
    private final InProcessWikiFinalizeLock finalizeLock = new InProcessWikiFinalizeLock();
    private final ChunkMapper chunkMapper = mock(ChunkMapper.class);
    private final KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeMapper knowledgeMapper = mock(KnowledgeMapper.class);
    private final WikiActivityAudit audit = mock(WikiActivityAudit.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<WikiActivityAudit> auditProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<WikiIngestTaskQueue> queueProvider = mock(ObjectProvider.class);
    /** 测试不接线追踪器 → 门面走 NOOP 语义（与 Go 的 nil tracker 同形）。 */
    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.ragagent.knowledge.service.SpanTracker> spanTrackerProvider =
            mock(ObjectProvider.class);

    private WikiIngestBatchHandler handler() {
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(queueProvider.getIfAvailable()).thenReturn(null);
        when(spanTrackerProvider.getIfAvailable()).thenReturn(null);
        return new WikiIngestBatchHandler(ingestService, wikiService, pendingRepo, citePipeline,
                taxonomy, dedupService, modelResolver, finalizeLock, chunkMapper, kbMapper,
                knowledgeMapper, auditProvider, queueProvider, spanTrackerProvider);
    }

    private static KnowledgeBase wikiKb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId("kb-1");
        kb.setTenantId(1L);
        KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
        s.setWikiEnabled(true);
        kb.setIndexingStrategy(s);
        return kb;
    }

    private static TaskPendingOp finalizeRow(long id, String op, WikiFinalizeRow row) {
        TaskPendingOp r = new TaskPendingOp();
        r.setId(id);
        r.setTaskType(WikiIngestConstants.FINALIZE_TASK_TYPE);
        r.setScope(WikiIngestConstants.TASK_SCOPE);
        r.setScopeId("kb-1");
        r.setOp(op);
        r.setDedupKey("");
        r.setPayload(MAPPER.valueToTree(row));
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    // 目录剪枝（Go wiki_folder_prune_finalize_test.go）
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ProcessWikiFinalize 的目录剪枝延迟")
    class FolderPrune {

        private WikiIngestPayload payload() {
            return new WikiIngestPayload(1L, "kb-1", null);
        }

        private void stubKb(KnowledgeBase kb) {
            when(kbMapper.selectOne(any(Wrapper.class))).thenReturn(kb);
        }

        /**
         * 对照 Go {@code TestProcessWikiFinalizeDefersFolderPruneWhileIngestIsPending}：
         * ingest 还有待办行时<b>绝不</b>剪目录（taxonomy 规划会在 reduce 写页之前建目录），
         * 持久化的 prune 行必须留着重试。
         */
        @Test
        @DisplayName("ingest 未排空时延迟剪枝（对照 Go TestProcessWikiFinalizeDefersFolderPruneWhileIngestIsPending）")
        void defersFolderPruneWhileIngestIsPending() {
            TaskPendingOp row = finalizeRow(11, WikiIngestConstants.FINALIZE_OP_FOLDER_PRUNE,
                    WikiFinalizeRow.folderIds(List.of("folder-a")));
            when(pendingRepo.peekBatch(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(),
                    eq("kb-1"), anyInt())).thenReturn(List.of(row));
            when(pendingRepo.pendingCount(eq(WikiIngestConstants.TASK_TYPE), anyString(), eq("kb-1")))
                    .thenReturn(1L);
            when(pendingRepo.pendingCount(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(), eq("kb-1")))
                    .thenReturn(1L);
            stubKb(wikiKb());

            handler().processWikiFinalize(payload());

            verify(wikiService, never()).pruneEmptyFolderChains(anyString(), anyList());
            // 剪枝行必须留在通道里：trim 集合不含被延迟的那一行
            // （对照 Go 的 require.Empty(t, pendingRepo.deletedRowIDs)）
            ArgumentCaptor<List<Long>> trimmed = ArgumentCaptor.forClass(List.class);
            verify(ingestService).trimPendingListDetached(trimmed.capture());
            assertThat(trimmed.getValue()).isEmpty();
            // 只有一个 finalize 触发（重试用），且不带稳定 TaskID
            verify(ingestService, times(1)).scheduleFinalizeRetry(payload());
            verify(ingestService, never()).scheduleFinalize(any(WikiIngestPayload.class));
        }

        /**
         * 对照 Go {@code TestProcessWikiFinalizePrunesFolderAfterIngestDrains}：
         * ingest 通道排空后，剪掉候选链上仍为空的目录并删除该行。
         */
        @Test
        @DisplayName("ingest 排空后剪枝（对照 Go TestProcessWikiFinalizePrunesFolderAfterIngestDrains）")
        void prunesFolderAfterIngestDrains() {
            TaskPendingOp row = finalizeRow(11, WikiIngestConstants.FINALIZE_OP_FOLDER_PRUNE,
                    WikiFinalizeRow.folderIds(List.of("folder-a")));
            when(pendingRepo.peekBatch(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(),
                    eq("kb-1"), anyInt())).thenReturn(List.of(row));
            when(pendingRepo.pendingCount(eq(WikiIngestConstants.TASK_TYPE), anyString(), eq("kb-1")))
                    .thenReturn(0L);
            when(pendingRepo.pendingCount(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(), eq("kb-1")))
                    .thenReturn(0L);
            when(wikiService.pruneEmptyFolderChains(eq("kb-1"), anyList()))
                    .thenReturn(List.of("folder-a"));
            stubKb(wikiKb());

            handler().processWikiFinalize(payload());

            ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
            verify(wikiService).pruneEmptyFolderChains(eq("kb-1"), ids.capture());
            assertThat(ids.getValue()).containsExactly("folder-a");
            verify(ingestService).trimPendingListDetached(List.of(11L));
            verify(ingestService, never()).scheduleFinalizeRetry(any());
        }

        /**
         * 对照 Go {@code PendingCount} 报错的路径：无法确认 ingest 是否排空时必须延迟，
         * 而不是冒险剪掉一个在途批次仍然拥有的目录。
         */
        @Test
        @DisplayName("无法确认 ingest 排空时延迟剪枝")
        void defersWhenDrainCheckFails() {
            TaskPendingOp row = finalizeRow(11, WikiIngestConstants.FINALIZE_OP_FOLDER_PRUNE,
                    WikiFinalizeRow.folderIds(List.of("folder-a")));
            when(pendingRepo.peekBatch(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(),
                    eq("kb-1"), anyInt())).thenReturn(List.of(row));
            when(pendingRepo.pendingCount(eq(WikiIngestConstants.TASK_TYPE), anyString(), eq("kb-1")))
                    .thenThrow(new IllegalStateException("db down"));
            stubKb(wikiKb());

            handler().processWikiFinalize(payload());

            verify(wikiService, never()).pruneEmptyFolderChains(anyString(), anyList());
            ArgumentCaptor<List<Long>> trimmed = ArgumentCaptor.forClass(List.class);
            verify(ingestService).trimPendingListDetached(trimmed.capture());
            assertThat(trimmed.getValue()).isEmpty();
            verify(ingestService, times(1)).scheduleFinalizeRetry(payload());
        }

        /** 空通道是 no-op（对照 Go {@code len(rows) == 0 → return nil}） */
        @Test
        @DisplayName("finalize 空通道是 no-op")
        void emptyLaneIsNoop() {
            when(pendingRepo.peekBatch(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(),
                    eq("kb-1"), anyInt())).thenReturn(List.of());

            handler().processWikiFinalize(payload());

            verify(ingestService, never()).cleanDeadLinks(anyString(), anyList(), any());
            verify(ingestService, never()).scheduleFinalize(any(WikiIngestPayload.class));
        }

        /**
         * KB 已不是 wiki（被删 / 改类型）：排空通道后停下，避免行无限堆积。
         */
        @Test
        @DisplayName("KB 停用后只排空通道")
        void drainsLaneForDisabledKb() {
            TaskPendingOp row = finalizeRow(11, WikiIngestConstants.FINALIZE_OP_SLUG,
                    WikiFinalizeRow.slug("entity/a", "A"));
            when(pendingRepo.peekBatch(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(),
                    eq("kb-1"), anyInt())).thenReturn(List.of(row));
            KnowledgeBase kb = wikiKb();
            KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
            s.setWikiEnabled(false);
            kb.setIndexingStrategy(s);
            stubKb(kb);

            handler().processWikiFinalize(payload());

            verify(ingestService).trimPendingListDetached(List.of(11L));
            verify(ingestService, never()).cleanDeadLinks(anyString(), anyList(), any());
        }

        /**
         * 按 KB 的 finalize 锁被别人持有时安全 no-op（对照 Go 的
         * {@code else if !acquired { return nil }}）。
         */
        @Test
        @DisplayName("finalize 锁被占用时 no-op")
        void busyLockIsNoop() {
            assertThat(finalizeLock.tryAcquire("kb-1"))
                    .isEqualTo(WikiFinalizeLock.AcquireResult.ACQUIRED);
            try {
                handler().processWikiFinalize(payload());
                verify(pendingRepo, never()).peekBatch(anyString(), anyString(), anyString(), anyInt());
            } finally {
                finalizeLock.release("kb-1");
            }
        }

        /** 释放后该 KB 可以被再次取锁（对照 Go 的 {@code defer Del}） */
        @Test
        @DisplayName("finalize 锁可重入获取")
        void lockReleasedAfterRun() {
            when(pendingRepo.peekBatch(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(),
                    eq("kb-1"), anyInt())).thenReturn(List.of());
            handler().processWikiFinalize(payload());
            assertThat(finalizeLock.heldCount()).isZero();
            assertThat(finalizeLock.tryAcquire("kb-1"))
                    .isEqualTo(WikiFinalizeLock.AcquireResult.ACQUIRED);
            finalizeLock.release("kb-1");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 已删除 KB 的守卫（Go wiki_deleted_kb_guard_test.go）
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("已删除 KB 的守卫")
    class DeletedKbGuard {

        @Test
        @DisplayName("ProcessWikiIngest：KB 已删除 → 排空队列并正常返回")
        void ingestDrainsDeletedKbQueue() {
            when(kbMapper.selectOne(any(Wrapper.class))).thenReturn(null);

            handler().processWikiIngest(new WikiIngestPayload(7L, "kb-deleted", null));

            verify(ingestService).clearDeletedKnowledgeBasePendingOps("kb-deleted");
            verify(ingestService, never()).claimPendingList(anyString(), anyInt());
        }

        @Test
        @DisplayName("ProcessWikiFinalize：KB 已删除 → 排空 finalize 通道")
        void finalizeDrainsDeletedKbQueue() {
            TaskPendingOp row = finalizeRow(1, WikiIngestConstants.FINALIZE_OP_SLUG,
                    WikiFinalizeRow.slug("entity/a", "A"));
            row.setScopeId("kb-deleted");
            when(pendingRepo.peekBatch(eq(WikiIngestConstants.FINALIZE_TASK_TYPE), anyString(),
                    eq("kb-deleted"), anyInt())).thenReturn(List.of(row));
            when(kbMapper.selectOne(any(Wrapper.class))).thenReturn(null);

            handler().processWikiFinalize(new WikiIngestPayload(7L, "kb-deleted", null));

            verify(ingestService).clearDeletedKnowledgeBasePendingOps("kb-deleted");
        }

        /**
         * 对照 Go {@code TestWikiDeletedKnowledgeBaseCleanupFailureRetries}：
         * 清理失败必须让任务失败（让队列按重试预算重排），而不是静默 ack。
         */
        @Test
        @DisplayName("清理失败必须重试（对照 Go TestWikiDeletedKnowledgeBaseCleanupFailureRetries）")
        void cleanupFailureRetries() {
            when(kbMapper.selectOne(any(Wrapper.class))).thenReturn(null);
            RuntimeException boom = new IllegalStateException("cleanup failed");
            org.mockito.Mockito.doThrow(boom)
                    .when(ingestService).clearDeletedKnowledgeBasePendingOps("kb-deleted");

            assertThatThrownBy(() -> handler().processWikiIngest(
                    new WikiIngestPayload(7L, "kb-deleted", null)))
                    .isSameAs(boom);
        }

        /** KB 存在但未启用 wiki：报错让任务重试（对照 Go 的 "KB is not wiki type"） */
        @Test
        @DisplayName("KB 未启用 wiki → 抛错重试")
        void kbNotWikiEnabled() {
            KnowledgeBase kb = wikiKb();
            KnowledgeBaseIndexingStrategy s = new KnowledgeBaseIndexingStrategy();
            s.setWikiEnabled(false);
            kb.setIndexingStrategy(s);
            when(kbMapper.selectOne(any(Wrapper.class))).thenReturn(kb);

            assertThatThrownBy(() -> handler().processWikiIngest(
                    new WikiIngestPayload(7L, "kb-1", null)))
                    .hasMessageContaining("is not wiki type");
        }

        /** 没有合成模型：报错让任务重试（对照 Go 的 missing_synthesis_model） */
        @Test
        @DisplayName("缺合成模型 → 抛错重试")
        void missingSynthesisModel() {
            when(kbMapper.selectOne(any(Wrapper.class))).thenReturn(wikiKb());

            assertThatThrownBy(() -> handler().processWikiIngest(
                    new WikiIngestPayload(7L, "kb-1", null)))
                    .hasMessageContaining("no synthesis model configured");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 纯函数
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code mergeChunkRefs}（batch L2134-2154）：当前 refs 与新增引用求并集，
     * 去重保序，空串被过滤；纯 retract（无 additions）时保持原样。
     */
    @Test
    @DisplayName("mergeChunkRefs 求并集且去重保序")
    void mergeChunkRefsUnions() {
        List<String> current = new ArrayList<>(List.of("c1", "c2", "", "c1"));
        SlugUpdate add1 = new SlugUpdate("entity/a", SlugUpdate.TYPE_ENTITY);
        add1.setSourceChunks(new ArrayList<>(List.of("c2", "c3", "")));
        SlugUpdate add2 = new SlugUpdate("entity/a", SlugUpdate.TYPE_ENTITY);
        add2.setSourceChunks(new ArrayList<>(List.of("c4")));

        assertThat(WikiIngestBatchHandler.mergeChunkRefs(current, List.of(add1, add2)))
                .containsExactly("c1", "c2", "c3", "c4");
        assertThat(WikiIngestBatchHandler.mergeChunkRefs(current, List.of()))
                .containsExactly("c1", "c2");
        assertThat(WikiIngestBatchHandler.mergeChunkRefs(null, null)).isEmpty();
    }

    /** 对照 Go {@code (*WikiConfig).ExtractionGranularity.Normalize()} 的枚举回落 */
    @Test
    @DisplayName("粒度映射：未知值回落 standard")
    void granularityMapping() {
        assertThat(WikiIngestBatchHandler.granularityFrom("focused"))
                .isEqualTo(com.ragagent.wiki.domain.WikiExtractionGranularity.FOCUSED);
        assertThat(WikiIngestBatchHandler.granularityFrom("exhaustive"))
                .isEqualTo(com.ragagent.wiki.domain.WikiExtractionGranularity.EXHAUSTIVE);
        assertThat(WikiIngestBatchHandler.granularityFrom(""))
                .isEqualTo(com.ragagent.wiki.domain.WikiExtractionGranularity.STANDARD);
        assertThat(WikiIngestBatchHandler.granularityFrom("STANDARD"))
                .isEqualTo(com.ragagent.wiki.domain.WikiExtractionGranularity.STANDARD);
    }

    /**
     * 对照 Go {@code wikiInlineChunkCitationRegex}（wiki_page.go L29）：
     * {@code [c003]}、{@code [c003, c007;c009]} 被剥离，普通正文与不合规的括号保留。
     */
    @Test
    @DisplayName("剥离内联 chunk 引用")
    void stripInlineChunkCitations() {
        assertThat(WikiBatchSupport.stripInlineChunkCitations("正文 [c003] 之后"))
                .isEqualTo("正文 之后");
        assertThat(WikiBatchSupport.stripInlineChunkCitations("[c003, c007;c009]"))
                .isEmpty();
        assertThat(WikiBatchSupport.stripInlineChunkCitations("[c01]"))
                .as("少于 3 位的编号不是合法句柄")
                .isEqualTo("[c01]");
        assertThat(WikiBatchSupport.stripInlineChunkCitations("普通 [链接](url)"))
                .isEqualTo("普通 [链接](url)");
        assertThat(WikiBatchSupport.stripInlineChunkCitations("")).isEmpty();
    }

    /**
     * 对照 Go {@code isLikelyRateLimitError}（knowledge_process.go L3872-3883）：
     * 429 / rate limit / quota 措辞命中；普通错误不命中；异常链上的 cause 也被检查。
     */
    @Test
    @DisplayName("速率限制错误分类")
    void rateLimitErrorClassification() {
        assertThat(WikiBatchSupport.isLikelyRateLimitError(null)).isFalse();
        assertThat(WikiBatchSupport.isLikelyRateLimitError(
                new IllegalStateException("HTTP 429 Too Many Requests"))).isTrue();
        assertThat(WikiBatchSupport.isLikelyRateLimitError(
                new IllegalStateException("Rate Limit exceeded"))).isTrue();
        assertThat(WikiBatchSupport.isLikelyRateLimitError(
                new IllegalStateException("ratelimit: quota exhausted"))).isTrue();
        assertThat(WikiBatchSupport.isLikelyRateLimitError(
                new IllegalStateException("connection refused"))).isFalse();
        // 异常链上的 cause 也要被看到（Java 特有的包装形态）
        assertThat(WikiBatchSupport.isLikelyRateLimitError(new IllegalStateException("LLM call failed",
                new java.io.IOException("429 too many requests")))).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════
    // 批次上下文（对照 Go newWikiBatchContext 的懒加载与缓存）
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("批次上下文懒加载")
    class BatchContext {

        /**
         * 对照 Go {@code newWikiBatchContext}：slug 标题按需解析、批内缓存、归档页与
         * 系统页视为"查无"、缺失 slug 也进缓存（负缓存）。
         */
        @Test
        @DisplayName("slug 标题懒加载 + 负缓存")
        void slugTitleLazyAndCached() {
            WikiIngestBatchHandler h = handler();
            WikiPageLite live = new WikiPageLite();
            live.setSlug("entity/a");
            live.setTitle("A");
            live.setPageType(WikiConstants.PAGE_TYPE_ENTITY);
            live.setStatus(WikiConstants.STATUS_PUBLISHED);

            WikiPageLite archived = new WikiPageLite();
            archived.setSlug("entity/old");
            archived.setTitle("Old");
            archived.setPageType(WikiConstants.PAGE_TYPE_ENTITY);
            archived.setStatus(WikiConstants.STATUS_ARCHIVED);

            WikiPageLite index = new WikiPageLite();
            index.setSlug("index");
            index.setTitle("Index");
            index.setPageType(WikiConstants.PAGE_TYPE_INDEX);
            index.setStatus(WikiConstants.STATUS_PUBLISHED);

            when(wikiService.listBySlugs(eq("kb-1"), anyList())).thenReturn(Map.of(
                    "entity/a", live, "entity/old", archived, "index", index));

            WikiBatchContext ctx = h.newWikiBatchContext("kb-1", null);
            assertThat(ctx.getExtractionGranularity())
                    .isEqualTo(com.ragagent.wiki.domain.WikiExtractionGranularity.STANDARD);

            // 批量入口一次打库覆盖全部 slug（含负缓存）
            Map<String, String> many = ctx.slugTitleMany(
                    List.of("entity/a", "entity/old", "index", "entity/missing"));
            assertThat(many).containsOnlyKeys("entity/a");

            // 已有缓存，逐条查询与再次批量都不再打库
            assertThat(ctx.slugTitle("entity/a")).isEqualTo("A");
            assertThat(ctx.slugTitle("entity/old")).as("归档页视为查无").isEmpty();
            assertThat(ctx.slugTitle("index")).as("系统页视为查无").isEmpty();
            assertThat(ctx.slugTitle("entity/missing")).isEmpty();
            assertThat(ctx.slugTitleMany(List.of("entity/a"))).containsOnlyKeys("entity/a");
            verify(wikiService, times(1)).listBySlugs(eq("kb-1"), anyList());
        }

        /** 对照 Go：摘要正文按 knowledge id 懒加载并缓存 */
        @Test
        @DisplayName("摘要正文懒加载 + 缓存")
        void summaryLazyAndCached() {
            when(wikiService.listSummariesByKnowledgeIDs(eq("kb-1"), anyList()))
                    .thenReturn(Map.of("kid-1", "body"));

            WikiBatchContext ctx = handler().newWikiBatchContext("kb-1", null);
            assertThat(ctx.summaryContentByKnowledgeId("kid-1")).isEqualTo("body");
            assertThat(ctx.summaryContentByKnowledgeId("kid-1")).isEqualTo("body");
            assertThat(ctx.summaryContentByKnowledgeId("kid-missing")).isEmpty();
            // 两次真实打库：kid-1 一次，kid-missing（负缓存）一次；第二次 kid-1 走缓存
            verify(wikiService, times(2)).listSummariesByKnowledgeIDs(eq("kb-1"), anyList());
        }

        /** 对照 Go：WikiConfig 缺席时用零值默认；存在时解析出粒度与指令 */
        @Test
        @DisplayName("WikiConfig 解析粒度与指令")
        void wikiConfigResolved() {
            com.ragagent.wiki.domain.WikiConfig cfg = new com.ragagent.wiki.domain.WikiConfig();
            cfg.setExtractionGranularity("exhaustive");
            cfg.setContentInstructions("content-instr");
            cfg.setExtractionInstructions("extract-instr");

            WikiBatchContext ctx = handler().newWikiBatchContext("kb-1", cfg);
            assertThat(ctx.getExtractionGranularity())
                    .isEqualTo(com.ragagent.wiki.domain.WikiExtractionGranularity.EXHAUSTIVE);
            assertThat(ctx.getContentInstructions()).isEqualTo("content-instr");
            assertThat(ctx.getExtractionInstructions()).isEqualTo("extract-instr");

            // 历史行里的非法值 → standard
            com.ragagent.wiki.domain.WikiConfig bad = new com.ragagent.wiki.domain.WikiConfig();
            bad.setExtractionGranularity("bogus");
            assertThat(handler().newWikiBatchContext("kb-1", bad).getExtractionGranularity())
                    .isEqualTo(com.ragagent.wiki.domain.WikiExtractionGranularity.STANDARD);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 后续调度
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code scheduleFollowUp}：队列已排空时不排后续；还有待办时排一个
     * <b>不带 TaskID</b> 的触发（因此不会被合并）。
     */
    @Test
    @DisplayName("scheduleFollowUp 的条件与参数")
    void scheduleFollowUp() {
        WikiIngestBatchHandler h = handler();
        WikiIngestPayload payload = new WikiIngestPayload(1L, "kb-1", "zh");

        when(pendingRepo.pendingCount(eq(WikiIngestConstants.TASK_TYPE), anyString(), eq("kb-1")))
                .thenReturn(0L);
        assertThat(h.scheduleFollowUp(payload, WikiIngestConstants.FOLLOW_UP_DELAY)).isFalse();

        when(pendingRepo.pendingCount(eq(WikiIngestConstants.TASK_TYPE), anyString(), eq("kb-1")))
                .thenThrow(new IllegalStateException("db down"));
        assertThat(h.scheduleFollowUp(payload, WikiIngestConstants.FOLLOW_UP_DELAY)).isFalse();

        when(pendingRepo.pendingCount(eq(WikiIngestConstants.TASK_TYPE), anyString(), eq("kb-1")))
                .thenReturn(3L);
        // 队列未接线 → 记 warn 并返回 false
        assertThat(h.scheduleFollowUp(payload, WikiIngestConstants.RATE_LIMIT_BACKOFF)).isFalse();
    }

    /** 对照 Go：{@code kb == nil} 时 wikiConfigOf 返回 null（零值语义） */
    @Test
    @DisplayName("wikiConfigOf 的零值容忍")
    void wikiConfigOfNullTolerance() {
        assertThat(WikiIngestBatchHandler.wikiConfigOf(null)).isNull();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setWikiConfig(null);
        assertThat(WikiIngestBatchHandler.wikiConfigOf(kb)).isNull();
    }

    /** 对照 Go {@code GetKnowledgeBaseByIDOnly}：空 id 返回 null（不打库） */
    @Test
    @DisplayName("KB 查询的空 id 短路")
    void kbLookupEmptyId() {
        WikiIngestBatchHandler h = handler();
        assertThat(h.getKnowledgeBaseByIDOnly("")).isNull();
        assertThat(h.getKnowledgeBaseByIDOnly(null)).isNull();
        assertThat(h.getKnowledgeByIDOnly("")).isNull();
        verify(kbMapper, never()).selectOne(any(Wrapper.class));
    }

    // ═══════════════════════════════════════════════════════════════
    // Reduce 主干（对照 Go reduceSlugUpdates，batch L1702-2123）
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("reduceSlugUpdates 的读-改-写")
    class Reduce {

        private final LlmChatClient chatModel = mock(LlmChatClient.class);
        private final WikiBatchContext batchCtx = new WikiBatchContext();

        private WikiIngestBatchHandler h() {
            when(ingestService.filterLiveUpdates(eq("kb-1"), anyList()))
                    .thenAnswer(inv -> inv.getArgument(1));
            return handler();
        }

        private SlugUpdate entityAdd(String slug, String name, String docSummary,
                                     String... chunks) {
            SlugUpdate u = new SlugUpdate(slug, SlugUpdate.TYPE_ENTITY);
            ExtractedItem it = new ExtractedItem();
            it.setSlug(slug);
            it.setName(name);
            it.setDescription("desc");
            it.setDetails("details");
            it.setAliases(new ArrayList<>(List.of("alias-1")));
            u.setItem(it);
            u.setDocTitle("Doc");
            u.setKnowledgeId("kid-1");
            u.setSourceRef("kid-1");
            u.setLanguage("zh-CN");
            u.setSourceChunks(new ArrayList<>(List.of(chunks)));
            u.setDocSummary(docSummary);
            return u;
        }

        /**
         * 对照 Go 的 summary 分支：摘要页整体覆盖，title 为
         * {@code "<docTitle> - Summary"}，pageType=summary，chunk refs 清空，
         * 新建时走 CreatePage。
         */
        @Test
        @DisplayName("summary 更新整体覆盖并清空 chunk refs")
        void summaryBranchOverwrites() {
            when(wikiService.findPageBySlug("kb-1", "summary/kid-1")).thenReturn(null);

            SlugUpdate u = new SlugUpdate("summary/kid-1", SlugUpdate.TYPE_SUMMARY);
            u.setDocTitle("Doc");
            u.setKnowledgeId("kid-1");
            u.setSourceRef("kid-1");
            u.setLanguage("zh-CN");
            u.setSummaryLine("line");
            u.setSummaryBody("body");

            WikiIngestBatchHandler.ReduceOutcome got = h().reduceSlugUpdates(
                    chatModel, "kb-1", "summary/kid-1", new ArrayList<>(List.of(u)), 7L, batchCtx, Map.of());

            assertThat(got.changed()).isTrue();
            assertThat(got.affectedType()).isEqualTo("ingest");
            assertThat(got.additionFailed()).isFalse();
            ArgumentCaptor<com.ragagent.wiki.domain.WikiPage> created =
                    ArgumentCaptor.forClass(com.ragagent.wiki.domain.WikiPage.class);
            verify(wikiService).createPage(created.capture());
            assertThat(created.getValue().getTitle()).isEqualTo("Doc - Summary");
            assertThat(created.getValue().getContent()).isEqualTo("body");
            assertThat(created.getValue().getSummary()).isEqualTo("line");
            assertThat(created.getValue().getPageType()).isEqualTo(WikiConstants.PAGE_TYPE_SUMMARY);
            assertThat(created.getValue().getChunkRefs()).isEmpty();
            assertThat(created.getValue().getStatus()).isEqualTo(WikiConstants.STATUS_DRAFT);
            verify(wikiService, never()).updatePage(any());
        }

        /**
         * 对照 Go 的 additions 分支：调 {@code WikiPageModifyUserPrompt} 生成正文，
         * 落 aliases / source_refs / chunk refs，并把 taxonomy 计划里的 folder id 应用上。
         */
        @Test
        @DisplayName("新增分支写入正文、别名、引用与目录")
        void additionBranchWritesPage() {
            when(wikiService.findPageBySlug("kb-1", "entity/acme")).thenReturn(null);
            when(ingestService.generateWithTemplate(eq(chatModel), anyString(),
                    org.mockito.ArgumentMatchers.<String, String>anyMap()))
                    .thenReturn("SUMMARY: 一句话\n\n改写后的正文 [ref-1]");
            // 引用正文解析（resolveCitedChunks 走 citePipeline）
            when(citePipeline.resolveCitedChunks(eq(7L), anyList())).thenReturn(Map.of("c1", "chunk body"));

            batchCtx.setPlannedFolderId(Map.of("entity/acme", "folder-1"));

            WikiIngestBatchHandler.ReduceOutcome got = h().reduceSlugUpdates(
                    chatModel, "kb-1", "entity/acme",
                    new ArrayList<>(List.of(entityAdd("entity/acme", "Acme", "doc summary", "c1"))),
                    7L, batchCtx, Map.of());

            assertThat(got.changed()).isTrue();
            ArgumentCaptor<com.ragagent.wiki.domain.WikiPage> created =
                    ArgumentCaptor.forClass(com.ragagent.wiki.domain.WikiPage.class);
            verify(wikiService).createPage(created.capture());
            com.ragagent.wiki.domain.WikiPage page = created.getValue();
            assertThat(page.getTitle()).isEqualTo("Acme");
            assertThat(page.getPageType()).isEqualTo(WikiConstants.PAGE_TYPE_ENTITY);
            assertThat(page.getContent()).isEqualTo("改写后的正文 [ref-1]");
            assertThat(page.getSummary()).isEqualTo("一句话");
            assertThat(page.getAliases()).contains("alias-1");
            assertThat(page.getSourceRefs()).contains("kid-1");
            assertThat(page.getChunkRefs()).contains("c1");
            assertThat(page.getFolderId()).isEqualTo("folder-1");
        }

        /**
         * 对照 Go：页面库报错时返回 {@code error} 且 {@code changed=false}
         * （调用方据此把贡献文档重新排队，而不是静默丢弃）。
         */
        @Test
        @DisplayName("仓储报错时返回 error")
        void repositoryFailureSurfaces() {
            when(wikiService.findPageBySlug("kb-1", "entity/acme"))
                    .thenThrow(new IllegalStateException("db down"));

            WikiIngestBatchHandler.ReduceOutcome got = h().reduceSlugUpdates(
                    chatModel, "kb-1", "entity/acme",
                    new ArrayList<>(List.of(entityAdd("entity/acme", "Acme", "", "c1"))),
                    7L, batchCtx, Map.of());

            assertThat(got.error()).isNotNull();
            assertThat(got.changed()).isFalse();
        }

        /**
         * 对照 Go：LLM 生成失败时 {@code additionFailed=true}，但<b>不</b>把错误向上传播
         * （页面保持原样，批次据此净化摘要页里的死链）。
         */
        @Test
        @DisplayName("生成失败标记 additionFailed 且不再抛错")
        void generationFailureFlagsAddition() {
            when(wikiService.findPageBySlug("kb-1", "entity/acme")).thenReturn(null);
            when(citePipeline.resolveCitedChunks(eq(7L), anyList())).thenReturn(null);
            when(ingestService.generateWithTemplate(eq(chatModel), anyString(),
                    org.mockito.ArgumentMatchers.<String, String>anyMap()))
                    .thenThrow(new IllegalStateException("LLM down"));

            WikiIngestBatchHandler.ReduceOutcome got = h().reduceSlugUpdates(
                    chatModel, "kb-1", "entity/acme",
                    new ArrayList<>(List.of(entityAdd("entity/acme", "Acme", "", "c1"))),
                    7L, batchCtx, Map.of());

            assertThat(got.additionFailed()).isTrue();
            assertThat(got.changed()).isFalse();
            assertThat(got.error()).as("LLM 错误不该向上传播").isNull();
            verify(wikiService, never()).createPage(any());
        }

        /**
         * 对照 Go：页面不存在且更新里没有新增/摘要（纯 retract / retractStale）时，
         * 整个 reduce 是 no-op。
         */
        @Test
        @DisplayName("纯 retract 且页面不存在时 no-op")
        void retractWithoutPageIsNoop() {
            when(wikiService.findPageBySlug("kb-1", "entity/gone")).thenReturn(null);

            SlugUpdate u = new SlugUpdate("entity/gone", SlugUpdate.TYPE_RETRACT_STALE);
            u.setKnowledgeId("kid-1");

            WikiIngestBatchHandler.ReduceOutcome got = h().reduceSlugUpdates(
                    chatModel, "kb-1", "entity/gone", new ArrayList<>(List.of(u)), 7L, batchCtx, Map.of());

            assertThat(got.changed()).isFalse();
            assertThat(got.error()).isNull();
            verify(wikiService, never()).createPage(any());
            verify(wikiService, never()).updatePage(any());
        }

        /** 对照 Go：filterLiveUpdates 清空全部更新时直接返回 */
        @Test
        @DisplayName("全部更新被过滤时 no-op")
        void allUpdatesFilteredIsNoop() {
            when(ingestService.filterLiveUpdates(eq("kb-1"), anyList())).thenReturn(List.of());

            WikiIngestBatchHandler.ReduceOutcome got = handler().reduceSlugUpdates(
                    chatModel, "kb-1", "entity/acme",
                    new ArrayList<>(List.of(entityAdd("entity/acme", "Acme", "", "c1"))),
                    7L, batchCtx, Map.of());

            assertThat(got.changed()).isFalse();
            verify(wikiService, never()).findPageBySlug(anyString(), anyString());
        }
    }

    /** 供阅读者：批次统计载体是可变的（对照 Go 的闭包捕获变量） */
    @Test
    @DisplayName("Stats 是可变的统计载体")
    void statsIsMutable() {
        WikiIngestBatchHandler.Stats stats = new WikiIngestBatchHandler.Stats();
        stats.ingestOps++;
        stats.retractOps = 3;
        stats.docPreview.add("x");
        assertThat(stats.ingestOps).isEqualTo(1);
        assertThat(stats.retractOps).isEqualTo(3);
        assertThat(WikiIngestBatchHandler.preview(stats, 6)).isEqualTo("[x]");
    }
}
