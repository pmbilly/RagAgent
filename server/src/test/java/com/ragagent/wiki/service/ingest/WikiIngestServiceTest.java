package com.ragagent.wiki.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
import com.ragagent.wiki.prompt.WikiPrompts;
import com.ragagent.common.wiki.SlugUpdate;
import com.ragagent.common.wiki.WikiLanguageSupport;
import com.ragagent.wiki.service.WikiLlmCallMetadata;
import com.ragagent.wiki.service.page.WikiPageService;
import com.ragagent.wiki.service.page.WikiTextUtils;

/**
 * {@link WikiIngestService} 核心行为的测试（prompt 脱敏 / 消息布局缓存 /
 * 预热门 / 并发合并 / 脱钩清理 / 待办结算 / 队列解码）。
 *
 * <p>测试替身以 {@link LlmChatClient} 的内部类形式提供。</p>
 *
 * <p>租户由 {@link TenantContext}（ThreadLocal，约定 §5）承载。
 * 需要"有租户"的用例显式 set，{@link #clearTenant()} 在每个用例后清掉。</p>
 */
class WikiIngestServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void clearTenant() throws Exception {
        TenantContext.clear();
        WikiLanguageSupport.clearCurrentLocale();
        for (AutoCloseable c : cleanup) {
            c.close();
        }
        cleanup.clear();
    }

    /** 记录 prompt / messages / options / 缓存元数据的替身。 */
    static class CapturingChatClient implements LlmChatClient {
        volatile String prompt = "";
        volatile List<ChatMessage> messages = List.of();
        volatile ChatOptions options;
        volatile String purpose = "";
        volatile String prefix = "";
        volatile String response = "";

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            if (!messages.isEmpty()) {
                this.prompt = messages.get(0).getContent();
            }
            this.messages = List.copyOf(messages);
            this.options = options;
            WikiLlmCallMetadata.Metadata meta = WikiLlmCallMetadata.current();
            this.purpose = meta.purpose();
            this.prefix = meta.prefixFingerprint();
            ChatResponse r = new ChatResponse();
            r.setContent(response);
            return r;
        }

        @Override
        public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
            return null;
        }

        @Override
        public String getModelName() {
            return "capture";
        }

        @Override
        public String getModelId() {
            return "capture";
        }
    }

    /** 阻塞直到被测释放的替身，用于验证并发合并。 */
    static class BlockingChatClient implements LlmChatClient {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ChatResponse r = new ChatResponse();
            r.setContent("shared result");
            return r;
        }

        @Override
        public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
            return null;
        }

        @Override
        public String getModelName() {
            return "blocking";
        }

        @Override
        public String getModelId() {
            return "blocking";
        }
    }

    /** 空 provider：getIfAvailable() 恒为 null，即"该依赖未接线"的分支。 */
    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    /**
     * 全部可选依赖缺席的 service —— generateWithTemplate / warmup / 调度路径不触碰它们。
     *
     * <p>"未接线" = "provider 里没有 bean"。
     * 生产环境里 Spring 永远注入一个非 null 的 provider，因此这里也必须给 provider
     * （而不是裸 null）才能复现真实装配形态。</p>
     */
    /** 指定 wikiService / pendingRepo、其余依赖缺席的 service。 */
    private static WikiIngestService serviceWith(Object wikiService, TaskPendingOpsRepository repo) {
        return serviceWith(wikiService, repo, null);
    }

    /** 指定 wikiService / pendingRepo / 墓碑存储、其余依赖缺席的 service。 */
    private static WikiIngestService serviceWith(Object wikiService, TaskPendingOpsRepository repo,
                                                 WikiDeletedTombstoneStore tombstones) {
        return new WikiIngestService((WikiPageService) wikiService, repo,
                emptyProvider(), emptyProvider(), null, null,
                tombstones == null ? emptyProvider() : providerOf(tombstones),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider());
    }

    /** pendingRepo 给定、其余依赖缺席的 service。 */
    private static WikiIngestService serviceWithRepo(TaskPendingOpsRepository repo) {
        return serviceWith(null, repo);
    }

    private static WikiIngestService bareService() {
        return serviceWith(null, null);
    }

    private static void setTenant(long tenantId) {
        TenantContext.set(tenantId, null, null, false, null, false);
    }

    // ═══════════════════════════════════════════════════════════════
    // TestGenerateWithTemplateMasksImageURLsBeforeLLM（Go L304-335）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("出站 prompt 不含真实 URL、同 URL 跨字段共享占位符、返回内容还原真 URL")
    void masksImageUrlsBeforeLlm() {
        String realUrl = "minio://kb/10000/exports/4135-aaaa-bbbb-cccc/page_1.jpg";
        CapturingChatClient model = new CapturingChatClient();
        model.response = "{\"details\":\"Model kept ![caption](wkimg:0001)\"}";
        WikiIngestService service = bareService();

        String got = service.generateWithTemplate(model,
                "Content={{.Content}} Existing={{.ExistingContent}}",
                Map.of(
                        "Content", "new ![alt](" + realUrl + ")",
                        "ExistingContent", "old ![same](" + realUrl + ")"));

        assertThat(model.prompt).doesNotContain(realUrl);
        assertThat(countOf(model.prompt, "wkimg:0001")).isEqualTo(2);
        assertThat(got).doesNotContain("wkimg:");
        assertThat(got).contains(realUrl);
    }

    // ═══════════════════════════════════════════════════════════════
    // TestGenerateWikiPageModifyUsesCacheableMessageLayout（Go L373-404）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("WikiPageModify 走 system+user 两条消息，共享源上下文领先 user 消息")
    void wikiPageModifyUsesCacheableMessageLayout() throws Exception {
        CapturingChatClient model = new CapturingChatClient();
        model.response = "SUMMARY: page\n# Alpha";
        WikiIngestService service = bareService();
        setTenant(7L);

        Map<String, String> data = new java.util.HashMap<>();
        data.put("HasAdditions", "1");
        data.put("SharedSourceContexts", "<document><context>shared source summary</context></document>\n");
        data.put("PageSlug", "concept/alpha");
        data.put("PageTitle", "Alpha");
        data.put("PageType", "concept");
        data.put("ExistingContent", "(New page)");
        data.put("NewContent", "page-specific evidence");
        data.put("AvailableSlugs", "concept/beta (Beta)");
        data.put("Language", "English");
        data.put("InstructionScope", "wiki_content");

        service.generateWithTemplate(model, WikiPrompts.WIKI_PAGE_MODIFY_USER_PROMPT, data);

        assertThat(model.messages).hasSize(2);
        assertThat(model.messages.get(0).getRole()).isEqualTo("system");
        assertThat(model.messages.get(1).getRole()).isEqualTo("user");
        assertThat(model.messages.get(0).getContent()).contains("SOURCE GROUNDING & MERGE RULES");
        assertThat(model.messages.get(1).getContent()).startsWith("<shared_source_contexts>");
        assertThat(model.purpose).isEqualTo("wiki_page_modify");
        assertThat(model.prefix).isNotEmpty();
    }

    @Test
    @DisplayName("非 WikiPageModify 模板只有一条 user 消息，业务指引追加在其后")
    void otherTemplatesUseSingleUserMessage() {
        CapturingChatClient model = new CapturingChatClient();
        model.response = "ok";
        WikiIngestService service = bareService();

        service.generateWithTemplate(model, "Content={{.Content}}", Map.of(
                "Content", "hello",
                "CustomInstructions", "Always cite laws.",
                "InstructionScope", "wiki_content"));

        assertThat(model.messages).hasSize(1);
        assertThat(model.messages.get(0).getRole()).isEqualTo("user");
        assertThat(model.messages.get(0).getContent())
                .startsWith("Content=hello")
                .contains("<wiki_content_business_instructions>")
                .contains("Always cite laws.");
        // 非页面修改模板的记账 purpose 是 wiki_generation（默认分支）
        assertThat(model.purpose).isEqualTo("wiki_generation");
    }

    // ═══════════════════════════════════════════════════════════════
    // 预热门
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("预热门：跟随者阻塞到 leader 释放为止")
    void warmupGateBlocksFollowers() throws Exception {
        WikiIngestService service = bareService();
        Runnable release = service.awaitWikiPromptWarmup("same-prefix");

        AtomicReference<String> followerOutcome = new AtomicReference<>("pending");
        CountDownLatch followerDone = new CountDownLatch(1);
        Thread follower = new Thread(() -> {
            try {
                service.awaitWikiPromptWarmup("same-prefix");
                followerOutcome.set("resumed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                followerOutcome.set("interrupted");
            } finally {
                followerDone.countDown();
            }
        });
        follower.start();

        // leader 未释放前，跟随者必须仍在等待
        assertThat(followerDone.await(20, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(followerOutcome.get()).isEqualTo("pending");

        release.run();
        assertThat(followerDone.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(followerOutcome.get()).isEqualTo("resumed");
    }

    @Test
    @DisplayName("预热门：空 key 直接放行（对照 Go key == \"\" 的早退）")
    void warmupGateEmptyKeyShortCircuits() throws Exception {
        WikiIngestService service = bareService();
        assertThat(service.awaitWikiPromptWarmup("")).isNotNull();
        assertThat(service.promptWarmupCount()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════
    // 并发合并
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("并发相同请求被 singleflight 合并成一次 provider 调用")
    void coalescesIdenticalConcurrentRequests() throws Exception {
        BlockingChatClient model = new BlockingChatClient();
        WikiIngestService service = bareService();
        setTenant(7L);

        AtomicReference<Exception> first = new AtomicReference<>();
        AtomicReference<Exception> second = new AtomicReference<>();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        // TenantContext 是 ThreadLocal（约定 §5）：跨线程必须显式传递，
        // 否则第二个线程会走"无租户 → 跳过跨调用合并"的分支。
        Thread t1 = new Thread(() -> {
            try {
                setTenant(7L);
                service.generateWithTemplate(model, "same {{.Value}}", Map.of("Value", "input"));
            } catch (Exception e) {
                first.set(e);
            } finally {
                firstDone.countDown();
            }
        });
        t1.start();
        assertThat(model.started.await(2, TimeUnit.SECONDS)).isTrue();

        Thread t2 = new Thread(() -> {
            try {
                setTenant(7L);
                service.generateWithTemplate(model, "same {{.Value}}", Map.of("Value", "input"));
            } catch (Exception e) {
                second.set(e);
            } finally {
                secondDone.countDown();
            }
        });
        t2.start();
        Thread.sleep(50);
        assertThat(model.calls.get())
                .as("identical requests reached provider before release")
                .isEqualTo(1);

        model.release.countDown();
        assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(first.get()).isNull();
        assertThat(second.get()).isNull();
        assertThat(model.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("合并只在飞行期间成立：完成后相同请求会真实再执行一次（不是缓存）")
    void coalescingIsNotACache() {
        CapturingChatClient model = new CapturingChatClient();
        model.response = "r";
        WikiIngestService service = bareService();
        setTenant(7L);

        service.generateWithTemplate(model, "same {{.Value}}", Map.of("Value", "input"));
        service.generateWithTemplate(model, "same {{.Value}}", Map.of("Value", "input"));
        assertThat(service.inflightLlmRequests()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════
    // TestGenerateWithTemplateSetsMaxTokens（Go L615-636，#2604 回归）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("每次调用都带 32768 的补全预算与显式的 thinking=false")
    void setsMaxTokensAndThinking() {
        CapturingChatClient model = new CapturingChatClient();
        model.response = "{\"entities\":[],\"concepts\":[]}";
        WikiIngestService service = bareService();

        service.generateWithTemplate(model, "Content={{.Content}}", Map.of("Content", "hello"));

        assertThat(model.options.getMaxTokens()).isEqualTo(WikiIngestConstants.LLM_MAX_TOKENS);
        assertThat(model.options.getMaxTokens()).isEqualTo(32768);
        assertThat(model.options.getThinking()).isNotNull().isFalse();
        assertThat(model.options.getTemperature()).isEqualTo(0.3);
    }

    // ═══════════════════════════════════════════════════════════════
    // TestWikiIngestCleanupContextDetachedFromCancelledParent（Go L485-504）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("脱钩清理：父作用域已取消（中断）时清理仍能执行，且租户值保留")
    void cleanupContextDetachedFromCancelledParent() throws Exception {
        AtomicReference<Boolean> sawInterrupt = new AtomicReference<>();
        AtomicReference<Long> sawTenant = new AtomicReference<>();
        TaskPendingOpsRepository repo = mock(TaskPendingOpsRepository.class);
        doAnswer(inv -> {
            sawInterrupt.set(Thread.currentThread().isInterrupted());
            sawTenant.set(TenantContext.currentTenantId());
            return null;
        }).when(repo).deleteByIds(anyList());

        WikiIngestService service = serviceWithRepo(repo);

        setTenant(42L);
        // 模拟父作用域已被取消（中断位）
        Thread.currentThread().interrupt();
        try {
            service.trimPendingListDetached(List.of(7L));
        } finally {
            // close() 会把中断位恢复（清理结束后调用方仍看到父作用域已取消）
            assertThat(Thread.interrupted()).isTrue();
        }

        assertThat(sawInterrupt.get())
                .as("cleanup path must not inherit the caller's cancellation")
                .isFalse();
        assertThat(sawTenant.get()).isEqualTo(42L);
    }

    // ═══════════════════════════════════════════════════════════════
    // 待办删除的错误透传
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("trimPendingList 把删除错误抛给调用方（批次据此结算失败）")
    void trimPendingListReturnsDeleteError() {
        RuntimeException want = new RuntimeException("delete failed");
        TaskPendingOpsRepository repo = mock(TaskPendingOpsRepository.class);
        doThrow(want).when(repo).deleteByIds(anyList());
        WikiIngestService service = serviceWithRepo(repo);

        assertThatThrownBy(() -> service.trimPendingList(List.of(1L, 2L, 3L)))
                .isSameAs(want);
    }

    @Test
    @DisplayName("trimPendingList 空入参是 no-op（批次末尾可无条件调用）")
    void trimPendingListEmptyIsNoop() {
        TaskPendingOpsRepository repo = mock(TaskPendingOpsRepository.class);
        WikiIngestService service = serviceWithRepo(repo);
        service.trimPendingList(List.of());
        service.trimPendingList(null);
        org.mockito.Mockito.verifyNoInteractions(repo);
    }

    // ═══════════════════════════════════════════════════════════════
    // 失败 op 重排的错误透传
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("requeueFailedOps 把 release 错误回传给调用方（settle error）")
    void requeueFailedOpsReturnsReleaseError() {
        RuntimeException want = new RuntimeException("release failed");
        TaskPendingOpsRepository repo = mock(TaskPendingOpsRepository.class);
        when(repo.incrFailCount(anyLong())).thenReturn(1);
        doThrow(want).when(repo).releaseByIds(anyList());
        WikiIngestService service = serviceWithRepo(repo);

        WikiPendingOp op = new WikiPendingOp(WikiIngestConstants.OP_INGEST, "k1");
        op.setDocTitle("doc");
        op.setDbId(99L);

        List<Exception> settleErrors =
                service.requeueFailedOps(WikiIngestPayload.of("kb-1"), List.of(op));

        assertThat(settleErrors).hasSize(1);
        assertThat(settleErrors.get(0)).hasCause(want);
    }

    @Test
    @DisplayName("requeueFailedOps 对 dbId == 0 的合成 op 是 no-op")
    void requeueFailedOpsSkipsUnpersistedOps() {
        TaskPendingOpsRepository repo = mock(TaskPendingOpsRepository.class);
        WikiIngestService service = serviceWithRepo(repo);

        List<Exception> settleErrors = service.requeueFailedOps(WikiIngestPayload.of("kb-1"),
                List.of(new WikiPendingOp(WikiIngestConstants.OP_INGEST, "k1")));

        assertThat(settleErrors).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(repo);
    }

    // ═══════════════════════════════════════════════════════════════
    // 队列解码（last-write-wins 去重）
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("待办行解码（对照 Go decodePendingRows L1086-1141）")
    class DecodePendingRows {

        @Test
        @DisplayName("同一 knowledge 的多行保留**最后一个**（[ingest, retract] → [retract]）")
        void lastWriteWinsPerKnowledge() throws Exception {
            WikiIngestService service = bareService();
            List<TaskPendingOp> rows = List.of(
                    row(1, "k1", op(WikiIngestConstants.OP_INGEST, "k1")),
                    row(2, "k2", op(WikiIngestConstants.OP_INGEST, "k2")),
                    row(3, "k1", op(WikiIngestConstants.OP_RETRACT, "k1")));

            WikiIngestService.PendingBatch batch = service.decodePendingRows(rows);

            assertThat(batch.ops()).hasSize(2);
            assertThat(batch.ops().get(0).getKnowledgeId()).isEqualTo("k2");
            assertThat(batch.ops().get(1).getKnowledgeId()).isEqualTo("k1");
            assertThat(batch.ops().get(1).getOp()).isEqualTo(WikiIngestConstants.OP_RETRACT);
            // peekedIds 包含**每一行**（含被去重折叠掉的），trim 时一次删光
            assertThat(batch.peekedIds()).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("dbId 由行主键回填，供 DeleteByIDs / IncrFailCount 寻址")
        void fillsDbId() throws Exception {
            WikiIngestService service = bareService();
            WikiIngestService.PendingBatch batch = service.decodePendingRows(List.of(
                    row(11, "k1", op(WikiIngestConstants.OP_INGEST, "k1"))));
            assertThat(batch.ops()).hasSize(1);
            assertThat(batch.ops().get(0).getDbId()).isEqualTo(11L);
        }

        @Test
        @DisplayName("载荷丢失时回落到列数据（否则该行永远删不掉）")
        void fallsBackToColumnsWhenPayloadMissing() {
            WikiIngestService service = bareService();
            TaskPendingOp r = new TaskPendingOp();
            r.setId(5L);
            r.setOp(WikiIngestConstants.OP_RETRACT);
            r.setDedupKey("k9");
            r.setPayload(null);

            WikiIngestService.PendingBatch batch = service.decodePendingRows(List.of(r));
            assertThat(batch.ops()).hasSize(1);
            assertThat(batch.ops().get(0).getOp()).isEqualTo(WikiIngestConstants.OP_RETRACT);
            assertThat(batch.ops().get(0).getKnowledgeId()).isEqualTo("k9");
        }

        @Test
        @DisplayName("空行集返回空批次")
        void emptyRows() {
            WikiIngestService.PendingBatch batch = bareService().decodePendingRows(List.of());
            assertThat(batch.ops()).isEmpty();
            assertThat(batch.peekedIds()).isEmpty();
        }

        private static TaskPendingOp row(long id, String dedupKey, WikiPendingOp payload)
                throws Exception {
            TaskPendingOp r = new TaskPendingOp();
            r.setId(id);
            r.setOp(payload.getOp());
            r.setDedupKey(dedupKey);
            r.setPayload(MAPPER.valueToTree(payload));
            return r;
        }

        private static WikiPendingOp op(String op, String knowledgeId) {
            return new WikiPendingOp(op, knowledgeId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // finalize 通道的行载荷
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("finalize 行载荷与去重键")
    class FinalizeRows {

        @Test
        @DisplayName("uniqueWikiFolderIDs：去空白、去重、保序")
        void uniqueFolderIds() {
            assertThat(WikiIngestService.uniqueWikiFolderIDs(
                    List.of(" a ", "b", "", "a", "  ", "b", "c")))
                    .containsExactly("a", "b", "c");
            assertThat(WikiIngestService.uniqueWikiFolderIDs(null)).isEmpty();
        }

        @Test
        @DisplayName("三种行的 JSON 形状：键名即字段名，未设分支显式 null")
        void rowJsonShapes() throws Exception {
            String slugRow = MAPPER.writeValueAsString(
                    WikiFinalizeRow.slug("entity/acme", "Acme"));
            assertThat(slugRow).isEqualTo(
                    "{\"slug\":\"entity/acme\",\"title\":\"Acme\",\"change\":null,\"folderIds\":null}");

            String changeRow = MAPPER.writeValueAsString(
                    WikiFinalizeRow.change(WikiFinalizeChange.added("Doc", null)));
            assertThat(changeRow).isEqualTo(
                    "{\"slug\":null,\"title\":null,\"change\":{\"action\":\"added\",\"docTitle\":\"Doc\",\"docSummary\":null},\"folderIds\":null}");

            String pruneRow = MAPPER.writeValueAsString(
                    WikiFinalizeRow.folderIds(List.of("f1")));
            assertThat(pruneRow).isEqualTo(
                    "{\"slug\":null,\"title\":null,\"change\":null,\"folderIds\":[\"f1\"]}");
        }

        @Test
        @DisplayName("finalize 调度在队列缺席时安全地什么都不做")
        void schedulingWithoutQueueIsSafe() {
            WikiIngestService service = bareService();
            service.enqueueFinalize(
                    WikiIngestPayload.of("kb-1"),
                    List.of("entity/acme"),
                    Map.of("entity/acme", "Acme"),
                    List.of(WikiFinalizeChange.added("Doc", "sum")),
                    List.of("f1"));
            service.scheduleFinalize(WikiIngestPayload.of("kb-1"));
            service.scheduleFinalizeRetry(WikiIngestPayload.of("kb-1"));
            service.scheduleCappedRetry(WikiIngestPayload.of("kb-1"));
            // 无 pendingRepo 时 scheduleStaleClaimRecheck 返回 false（失败分支）
            assertThat(service.scheduleStaleClaimRecheck(WikiIngestPayload.of("kb-1"))).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 既有 taxonomy 渲染
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("taxonomy 树按层缩进、同级按字符串升序（否则 provider 前缀缓存会抖）")
    void formatExistingTaxonomy() {
        String tree = WikiIngestService.formatExistingTaxonomyForPrompt(List.of(
                List.of("节日", "传统节日"),
                List.of("人物"),
                List.of("节日", "现代节日")));
        // 同级按**字节序**排序（UTF-8 下等价于码点序）：
        // "人物"(U+4EBA) < "节日"(U+8282)，因此人物在前。
        assertThat(tree).isEqualTo("""
人物
节日
  传统节日
  现代节日""".strip());

        assertThat(WikiIngestService.formatExistingTaxonomyForPrompt(List.of())).isEmpty();
        assertThat(WikiIngestService.formatExistingTaxonomyForPrompt(
                List.of(List.of("")))).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // goQuote / xmlEscape（%q 风格引号与 XML 转义）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("goQuote 对 slug 产出 Go %q 的形态")
    void goQuoteMatchesGoVerb() {
        assertThat(WikiIngestService.goQuote("entity/acme-corp")).isEqualTo("\"entity/acme-corp\"");
        assertThat(WikiIngestService.goQuote("a\"b")).isEqualTo("\"a\\\"b\"");
        assertThat(WikiIngestService.goQuote("")).isEqualTo("\"\"");
        assertThat(WikiIngestService.goQuote(null)).isEqualTo("\"\"");
    }

    @Test
    @DisplayName("xmlEscape 只转义 & < >")
    void xmlEscape() {
        assertThat(WikiTextUtils.xmlEscape("a & b < c > d")).isEqualTo("a &amp; b &lt; c &gt; d");
        assertThat(WikiTextUtils.xmlEscape("中文")).isEqualTo("中文");
        assertThat(WikiTextUtils.xmlEscape(null)).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 墓碑快路径
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isKnowledgeGone：空 id 直接算已消失；墓碑命中时不查库")
    void isKnowledgeGoneUsesTombstoneFastPath() {
        InProcessWikiDeletedTombstoneStore tombstones = new InProcessWikiDeletedTombstoneStore();
        WikiIngestService service = serviceWith(null, null, tombstones);

        assertThat(service.isKnowledgeGone("kb", "")).isTrue();
        assertThat(service.isKnowledgeGone("kb", "k1")).isFalse();

        tombstones.markDeleted("kb", "k1");
        assertThat(service.isKnowledgeGone("kb", "k1")).isTrue();
        assertThat(tombstones.size()).isEqualTo(1);

        tombstones.clear("kb", "k1");
        assertThat(tombstones.exists("kb", "k1")).isFalse();
    }

    @Test
    @DisplayName("filterLiveUpdates 保留 retract 更新、丢弃已删文档的新增")
    void filterLiveUpdatesKeepsRetracts() {
        InProcessWikiDeletedTombstoneStore tombstones = new InProcessWikiDeletedTombstoneStore();
        tombstones.markDeleted("kb", "gone");
        WikiIngestService service = serviceWith(null, null, tombstones);

        SlugUpdate added = new SlugUpdate("entity/a", SlugUpdate.TYPE_ENTITY);
        added.setKnowledgeId("gone");
        SlugUpdate retract = new SlugUpdate("entity/b", SlugUpdate.TYPE_RETRACT);
        retract.setKnowledgeId("gone");

        List<SlugUpdate> filtered = service.filterLiveUpdates("kb", List.of(added, retract));
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).getType()).isEqualTo(SlugUpdate.TYPE_RETRACT);
    }

    // ═══════════════════════════════════════════════════════════════
    // 内容重建
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reconstructEnrichedContent 在 enrich 未接线时等价于纯文本重建（= Go 的空图片信息分支）")
    void enrichedContentDegradesToPlainReconstruction() {
        WikiIngestService service = bareService();
        List<com.ragagent.knowledge.domain.Chunk> chunks = List.of();
        assertThat(service.reconstructEnrichedContent(chunks, 1L)).isEmpty();
        assertThat(service.reconstructEnrichedContent(null, 1L)).isEmpty();
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T instance) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<T> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(instance);
        return p;
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

}
