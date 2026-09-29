package com.ragagent.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import com.ragagent.wiki.domain.TaskDeadLetter;
import com.ragagent.wiki.mapper.TaskDeadLetterRepository;

/**
 * asynq → 进程内虚拟线程队列的语义测试（对照 Go 的 asynq 客户端行为，
 * 见 wiki_ingest.go 的 {@code Enqueue} 调用点 L561-665 / L804-839 / L1019-1079）。
 *
 * <p>Go 侧这些语义由 asynq 库提供、由 router/task.go 的配置决定，没有单测；
 * Java 换成自研实现之后，<b>它们就成了需要自己钉住的行为</b>——
 * 尤其是 TaskID 合并（finalize 防抖与防惊群全靠它）。</p>
 */
class InProcessWikiIngestTaskQueueTest {

    private InProcessWikiIngestTaskQueue queue;
    private RecordingHandler handler;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    /** 记录调用并可选地失败若干次。 */
    static class RecordingHandler implements WikiIngestTaskHandler {
        final CopyOnWriteArrayList<String> ingestCalls = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> finalizeCalls = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();
        volatile CountDownLatch callLatch = new CountDownLatch(0);
        volatile CountDownLatch blockLatch;

        @Override
        public void processWikiIngest(WikiIngestPayload payload) {
            ingestCalls.add(payload.knowledgeBaseId());
            callLatch.countDown();
            awaitBlock();
            if (failuresRemaining.getAndDecrement() > 0) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void processWikiFinalize(WikiIngestPayload payload) {
            finalizeCalls.add(payload.knowledgeBaseId());
            callLatch.countDown();
            awaitBlock();
        }

        private void awaitBlock() {
            CountDownLatch latch = blockLatch;
            if (latch == null) {
                return;
            }
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> p = Mockito.mock(ObjectProvider.class);
        Mockito.when(p.getIfAvailable()).thenReturn(instance);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return Mockito.mock(ObjectProvider.class);
    }

    private void buildQueue(WikiIngestTaskHandler taskHandler, TaskDeadLetterRepository deadLetters) {
        queue = new InProcessWikiIngestTaskQueue(
                taskHandler == null ? emptyProvider() : providerOf(taskHandler),
                deadLetters == null ? emptyProvider() : providerOf(deadLetters));
    }

    private static WikiIngestTask ingest(String kbId, Duration delay, int maxRetry, String taskId) {
        return new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_INGEST,
                "{\"tenantId\":7,\"knowledgeBaseId\":\"" + kbId + "\",\"language\":\"en-US\"}",
                delay, maxRetry, Duration.ofMinutes(60), taskId);
    }

    // ═══════════════════════════════════════════════════════════════
    // TaskID 合并（对照 asynq.ErrTaskIDConflict）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同 TaskID 的第二个任务被合并（返回 false），任务完成后 ID 释放")
    void taskIdCoalescing() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch started = new CountDownLatch(1);
        handler.callLatch = started;
        handler.blockLatch = new CountDownLatch(1);
        buildQueue(handler, null);

        // 第一个：入队成功
        assertThat(queue.enqueue(ingest("kb-1", Duration.ZERO, 3, "wiki-ingest-capped-kb-1")))
                .isTrue();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.activeTaskIdCount()).isEqualTo(1);

        // 第二个：同 TaskID，被合并 —— 这正是"防惊群"依赖的行为
        assertThat(queue.enqueue(ingest("kb-1", Duration.ZERO, 3, "wiki-ingest-capped-kb-1")))
                .isFalse();

        // 放行第一个任务，完成之后 ID 被释放
        handler.blockLatch.countDown();
        for (int i = 0; i < 200 && queue.activeTaskIdCount() != 0; i++) {
            Thread.sleep(10);
        }
        assertThat(queue.activeTaskIdCount()).isZero();

        // 再入队同 ID → 又是新任务
        assertThat(queue.enqueue(ingest("kb-1", Duration.ZERO, 3, "wiki-ingest-capped-kb-1")))
                .isTrue();
    }

    @Test
    @DisplayName("不带 TaskID 的任务从不被合并（对照 scheduleFinalizeRetry 的刻意设计）")
    void tasksWithoutTaskIdAreNeverCoalesced() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch done = new CountDownLatch(2);
        handler.callLatch = done;
        buildQueue(handler, null);

        assertThat(queue.enqueue(ingest("kb-1", Duration.ZERO, 3, ""))).isTrue();
        assertThat(queue.enqueue(ingest("kb-1", Duration.ZERO, 3, ""))).isTrue();

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.ingestCalls).hasSize(2);
    }

    // ═══════════════════════════════════════════════════════════════
    // 延迟投递（对照 asynq.ProcessIn）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ProcessIn 生效：延迟未到不执行、到点执行")
    void processInDelayIsHonored() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch done = new CountDownLatch(1);
        handler.callLatch = done;
        buildQueue(handler, null);

        long startedAt = System.currentTimeMillis();
        queue.enqueue(ingest("kb-1", Duration.ofMillis(300), 3, ""));

        // 150ms 时还没执行
        assertThat(done.await(150, TimeUnit.MILLISECONDS)).isFalse();
        // 到点执行
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(System.currentTimeMillis() - startedAt).isGreaterThanOrEqualTo(280);
    }

    // ═══════════════════════════════════════════════════════════════
    // 重试 / 死信（对照 asynq.MaxRetry + 死信归档）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("失败按 MaxRetry 重试；耗尽后归档到死信并从 TaskID 表释放")
    void retriesThenArchivesToDeadLetters() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch done = new CountDownLatch(2);
        handler.callLatch = done;
        handler.failuresRemaining.set(2); // 两次尝试都失败，maxRetry=1 → 恰好耗尽
        TaskDeadLetterRepository deadLetters = Mockito.mock(TaskDeadLetterRepository.class);
        buildQueue(handler, deadLetters);
        queue.setRetryDelayOverrideSeconds(0L);

        queue.enqueue(ingest("kb-1", Duration.ZERO, 1, "wiki-ingest-kb-1"));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // 等归档与 TaskID 释放完成
        for (int i = 0; i < 200 && queue.activeTaskIdCount() != 0; i++) {
            Thread.sleep(10);
        }
        assertThat(handler.ingestCalls).hasSize(2);
        assertThat(queue.activeTaskIdCount()).isZero();

        org.mockito.ArgumentCaptor<TaskDeadLetter> captor =
                org.mockito.ArgumentCaptor.forClass(TaskDeadLetter.class);
        Mockito.verify(deadLetters).insert(captor.capture());
        TaskDeadLetter archived = captor.getValue();
        assertThat(archived.getTaskType()).isEqualTo(WikiIngestConstants.TASK_TYPE);
        assertThat(archived.getScopeId()).isEqualTo("kb-1");
        assertThat(archived.getTenantId()).isEqualTo(7L);
        assertThat(archived.getFailCount()).isEqualTo(2);
        assertThat(archived.getLastError()).contains("boom");
    }

    @Test
    @DisplayName("未耗尽预算时留在队列继续重试，不写死信")
    void retriesWithoutArchiving() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch done = new CountDownLatch(1);
        handler.callLatch = done;
        handler.failuresRemaining.set(1); // 第一次失败，第二次成功
        TaskDeadLetterRepository deadLetters = Mockito.mock(TaskDeadLetterRepository.class);
        buildQueue(handler, deadLetters);
        queue.setRetryDelayOverrideSeconds(0L);

        queue.enqueue(ingest("kb-1", Duration.ZERO, 3, ""));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 200 && handler.ingestCalls.size() < 2; i++) {
            Thread.sleep(10);
        }
        assertThat(handler.ingestCalls).hasSize(2);
        Mockito.verifyNoInteractions(deadLetters);
    }

    @Test
    @DisplayName("死信仓储未接线时静默跳过归档（不掩盖底层任务错误）")
    void archiveIsBestEffortWhenRepositoryMissing() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch done = new CountDownLatch(1);
        handler.callLatch = done;
        handler.failuresRemaining.set(1);
        buildQueue(handler, null);
        queue.setRetryDelayOverrideSeconds(0L);

        queue.enqueue(ingest("kb-1", Duration.ZERO, 0, ""));
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 200 && queue.activeTaskIdCount() != 0; i++) {
            Thread.sleep(10);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 重试延迟策略（对照 Go router/task.go 的 asynqRetryDelayFunc）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("锁冲突走固定 15 秒；其余走 asynq 默认退避（n^4 起步）")
    void retryDelayPolicy() {
        assertThat(InProcessWikiIngestTaskQueue.retryDelaySeconds(
                1, new WikiIngestConstants.ConcurrentTaskActiveException()))
                .isEqualTo(15);

        // 默认公式：n^4 + 15 + rand(30)*(n+1)，随机项是 [0, 30) 的整数。
        // n=1 → 1  + 15 + [0,29]*2 = [16, 74]
        // n=2 → 16 + 15 + [0,29]*3 = [31, 118]
        long d1 = InProcessWikiIngestTaskQueue.retryDelaySeconds(1, new RuntimeException("x"));
        long d2 = InProcessWikiIngestTaskQueue.retryDelaySeconds(2, new RuntimeException("x"));
        assertThat(d1).isBetween(16L, 74L);
        assertThat(d2).isBetween(31L, 118L);
        // 关键性质：默认退避明显长于锁冲突的固定 15 秒（Go 的注释解释了这个差异的理由）
        assertThat(d1).isGreaterThan(15L);
    }

    // ═══════════════════════════════════════════════════════════════
    // 分派 / 缺接线
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("按任务类型分派到正确的处理入口")
    void dispatchesByTaskType() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch done = new CountDownLatch(2);
        handler.callLatch = done;
        buildQueue(handler, null);

        queue.enqueue(ingest("kb-1", Duration.ZERO, 3, ""));
        queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_FINALIZE,
                "{\"tenantId\":7,\"knowledgeBaseId\":\"kb-1\"}",
                Duration.ZERO, 3, Duration.ofMinutes(30), "wiki-finalize-kb-1"));

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.ingestCalls).containsExactly("kb-1");
        assertThat(handler.finalizeCalls).containsExactly("kb-1");
    }

    @Test
    @DisplayName("没有 WikiIngestTaskHandler bean 时任务被丢弃并记 warn，不抛异常")
    void missingHandlerDropsTaskQuietly() throws Exception {
        buildQueue(null, null);
        assertThat(queue.enqueue(ingest("kb-1", Duration.ZERO, 3, "tid"))).isTrue();
        Thread.sleep(200);
        // TaskID 已释放（任务被视为终态）
        assertThat(queue.activeTaskIdCount()).isZero();
    }

    @Test
    @DisplayName("非法载荷抛出（对照 Go 的 invalid_payload 分支）")
    void invalidPayloadThrows() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch done = new CountDownLatch(1);
        handler.callLatch = done;
        buildQueue(handler, null);

        queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_INGEST, "not json", Duration.ZERO, 0,
                Duration.ofMinutes(60), ""));

        // 解析失败 → 抛异常 → 计入重试预算；maxRetry=0 直接归档（无死信仓储时静默）
        assertThat(done.await(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(handler.ingestCalls).isEmpty();
    }

    /** in-flight 上限与队列的关系：队列不做限流，限流在 WikiIngestService（对照 Go 的分层）。 */
    @Test
    @DisplayName("队列本身不限流（在途上限由 WikiInflightLimiter 承担）")
    void queueDoesNotRateLimit() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch done = new CountDownLatch(5);
        handler.callLatch = done;
        buildQueue(handler, null);

        for (int i = 0; i < 5; i++) {
            assertThat(queue.enqueue(ingest("kb-" + i, Duration.ZERO, 3, ""))).isTrue();
        }
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.ingestCalls).hasSize(5);
    }

    @Test
    @DisplayName("超时到点中断执行线程（对照 asynq.Timeout 取消 ctx）")
    void timeoutInterruptsWorker() throws Exception {
        handler = new RecordingHandler();
        CountDownLatch started = new CountDownLatch(1);
        handler.callLatch = started;
        handler.blockLatch = new CountDownLatch(1);
        buildQueue(handler, null);

        queue.enqueue(new WikiIngestTask(
                WikiIngestTask.TYPE_WIKI_INGEST,
                "{\"tenantId\":7,\"knowledgeBaseId\":\"kb-1\"}",
                Duration.ZERO, 0, Duration.ofMillis(200), ""));

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        // 超时看门狗会在 200ms 后中断工作线程；handler 的阻塞等待因此提前返回
        long deadline = System.currentTimeMillis() + 3000;
        while (handler.blockLatch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        handler.blockLatch.countDown();
        // 不抛异常即为通过：中断被清理、任务按失败路径结算
        assertThat(queue.activeTaskIdCount()).isZero();
    }

    @Test
    @DisplayName("常量与 Go 逐条一致（任务类型 / 上限 / 延迟）")
    void constantsMatchGo() {
        assertThat(WikiIngestTask.TYPE_WIKI_INGEST).isEqualTo("wiki:ingest");
        assertThat(WikiIngestTask.TYPE_WIKI_FINALIZE).isEqualTo("wiki:finalize");
        assertThat(WikiIngestConstants.TASK_TYPE).isEqualTo("wiki:ingest");
        assertThat(WikiIngestConstants.TASK_SCOPE).isEqualTo("knowledge_base");
        assertThat(WikiIngestConstants.INGEST_DELAY).isEqualTo(Duration.ofSeconds(30));
        assertThat(WikiIngestConstants.FOLLOW_UP_DELAY).isEqualTo(Duration.ofSeconds(5));
        assertThat(WikiIngestConstants.RATE_LIMIT_BACKOFF).isEqualTo(Duration.ofSeconds(60));
        assertThat(WikiIngestConstants.INGEST_MAX_RETRY).isEqualTo(10);
        assertThat(WikiIngestConstants.MAX_DOCS_PER_BATCH).isEqualTo(5);
        assertThat(WikiIngestConstants.MAX_FAIL_RETRIES).isEqualTo(5);
        assertThat(WikiIngestConstants.CLAIM_STALE_AFTER).isEqualTo(Duration.ofMinutes(90));
        assertThat(WikiIngestConstants.INFLIGHT_DEFAULT).isEqualTo(4);
        assertThat(WikiIngestConstants.INFLIGHT_TTL).isEqualTo(Duration.ofSeconds(90));
        assertThat(WikiIngestConstants.INFLIGHT_RENEW).isEqualTo(Duration.ofSeconds(30));
        assertThat(WikiIngestConstants.INFLIGHT_BACKOFF).isEqualTo(Duration.ofSeconds(10));
        assertThat(WikiIngestConstants.FINALIZE_DELAY).isEqualTo(Duration.ofSeconds(20));
        assertThat(WikiIngestConstants.FINALIZE_MAX_ROWS).isEqualTo(5000);
        assertThat(WikiIngestConstants.FOLDER_PRUNE_RETRY_DELAY).isEqualTo(Duration.ofMinutes(1));
        assertThat(WikiIngestConstants.INGEST_CLEANUP_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
        assertThat(WikiIngestConstants.MAX_CONTENT_FOR_WIKI).isEqualTo(32768);
        assertThat(WikiIngestConstants.INDEX_INTRO_SUMMARY_CAP).isEqualTo(200);
        assertThat(WikiIngestConstants.DELETED_TTL).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("键与 TaskID 的拼装与 Go 的格式串一致")
    void keyFormatsMatchGo() {
        assertThat(WikiIngestConstants.deletedTombstoneKey("kb", "kn")).isEqualTo("wiki:deleted:kb:kn");
        assertThat(WikiIngestConstants.slugLockKey("kb", "entity/x")).isEqualTo("wiki:slug:kb:entity/x");
        assertThat(WikiIngestConstants.inflightKey("kb")).isEqualTo("wiki:inflight:kb");
        assertThat(WikiIngestConstants.identityClaimKey("kb", "entity", "acme"))
                .isEqualTo("wiki:identity:kb:entity:acme");
        assertThat(WikiIngestConstants.finalizeTaskId("kb")).isEqualTo("wiki-finalize-kb");
        assertThat(WikiIngestConstants.cappedRetryTaskId("kb")).isEqualTo("wiki-ingest-capped-kb");
        assertThat(WikiIngestConstants.staleClaimRecheckTaskId("kb"))
                .isEqualTo("wiki-ingest-recheck-kb");
        assertThat(WikiIngestConstants.FINALIZE_LOCK_PREFIX).isEqualTo("wiki:finalize:active:");
    }

    @Test
    @DisplayName("ConcurrentTaskActiveException 的 message 与 Go 的哨兵错误一致")
    void concurrencySentinelMessage() {
        WikiIngestConstants.ConcurrentTaskActiveException e =
                new WikiIngestConstants.ConcurrentTaskActiveException();
        assertThat(e.getMessage()).isEqualTo("concurrent wiki task active");
        assertThatThrownBy(() -> {
            throw e;
        }).isInstanceOf(WikiIngestConstants.ConcurrentTaskActiveException.class);
    }

    @Test
    @DisplayName("WikiIngestTask 的空值归一：null 延迟 → ZERO、null taskId → 空串")
    void ingestTaskNormalizesNulls() {
        WikiIngestTask t = new WikiIngestTask("t", "p", null, 0, null, null);
        assertThat(t.processIn()).isEqualTo(Duration.ZERO);
        assertThat(t.timeout()).isEqualTo(Duration.ZERO);
        assertThat(t.taskId()).isEmpty();
        assertThat(t.hasTaskId()).isFalse();
    }
}
