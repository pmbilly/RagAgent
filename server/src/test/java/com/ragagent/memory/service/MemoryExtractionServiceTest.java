package com.ragagent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.memory.domain.MemoryConfig;
import com.ragagent.memory.domain.MemoryExtractionBatch;
import com.ragagent.memory.domain.MemoryExtractionFailure;
import com.ragagent.memory.domain.MemoryExtractionSession;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryKinds;
import com.ragagent.memory.domain.MemoryMessageCursor;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.mapper.MemoryRepository;
import com.ragagent.session.domain.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 蒸馏编排的对等测试（对照 Go extract.go 的 {@code Handle} /
 * {@code ScheduleExtraction} / {@code collectSessionSegments} / {@code applyDecisions}）。
 *
 * <p>仓储、消息读取、队列、模型全部是替身——这一层要验的是**分支与调用序列**
 * （租约释放时机、checkpoint 的 drained 取值、失败预算、段切分），
 * 而不是 SQL 或模型输出。三个真值来自 Go 侧的注释与语义，逐条标在断言旁边。</p>
 *
 * <p>不依赖任何网络：模型是返回固定正文的假客户端。</p>
 */
class MemoryExtractionServiceTest {

    private static final long TENANT = 7L;
    private static final MemoryScope SCOPE = new MemoryScope(TENANT, "web_user:u1");

    private final MemoryRepository repo = mock(MemoryRepository.class);
    private final MemoryService memoryService = mock(MemoryService.class);
    private final MemoryVectorService vectorService = mock(MemoryVectorService.class);
    private final MemoryMessageReader messages = mock(MemoryMessageReader.class);
    private final MemoryConsolidationService consolidation = mock(MemoryConsolidationService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MemoryExtractTaskQueue> queueProvider = mock(ObjectProvider.class);
    private final MemoryExtractTaskQueue queue = mock(MemoryExtractTaskQueue.class);

    private MemoryExtractionService service;

    private static MemoryConfig autoCfg() {
        MemoryConfig cfg = new MemoryConfig();
        cfg.setEnabled(true);
        cfg.setWriteMode(MemoryConfig.WRITE_MODE_AUTO);
        cfg.normalize();
        return cfg;
    }

    private static MemorySubject enabledSubject() {
        MemorySubject subject = new MemorySubject();
        subject.setEnabled(true);
        return subject;
    }

    private static MemoryExtractionPayloadHolder payloadHolder() {
        return new MemoryExtractionPayloadHolder();
    }

    /** 只是把负载构造收在一处，避免每个用例重复六个参数。 */
    private static final class MemoryExtractionPayloadHolder {
        MemoryExtractPayload build() {
            return new MemoryExtractPayload(TENANT, "web_user:u1", "sess-1", "msg-1", "chat-1", "");
        }
    }

    private static MemoryExtractionSession session(String sessionId) {
        MemoryExtractionSession s = new MemoryExtractionSession();
        s.setSessionId(sessionId);
        return s;
    }

    private static Message message(String id, String role, String content, OffsetDateTime at) {
        Message m = new Message();
        m.setId(id);
        m.setSessionId("sess-1");
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(at);
        return m;
    }

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso);
    }

    @BeforeEach
    void setUp() {
        when(queueProvider.getIfAvailable()).thenReturn(queue);
        service = new MemoryExtractionService(repo, memoryService, vectorService, messages,
                consolidation, queueProvider);
        when(memoryService.workspaceConfig(TENANT)).thenReturn(autoCfg());
    }

    private void stubSubjectEnabled() {
        when(repo.ensureSubject(SCOPE)).thenReturn(enabledSubject());
    }

    @Nested
    @DisplayName("Handle：入口守卫")
    class Guards {

        @Test
        void payloadWithoutScopeIsDroppedWithoutTouchingTheStore() {
            service.handle(new MemoryExtractPayload(0, "", "s", "m", "", ""));
            service.handle(new MemoryExtractPayload(TENANT, "", "s", "m", "", ""));
            verifyNoInteractions(repo);
        }

        @Test
        void autoExtractOffReleasesTheSlotAndStops() {
            when(memoryService.workspaceConfig(TENANT)).thenReturn(new MemoryConfig());
            service.handle(payloadHolder().build());

            verify(repo).releaseExtractionSlot(SCOPE, "");
            verify(repo, never()).ensureSubject(any());
        }

        @Test
        void disabledSubjectReleasesTheSlotAndStops() {
            MemorySubject subject = new MemorySubject();
            subject.setEnabled(false);
            when(repo.ensureSubject(SCOPE)).thenReturn(subject);

            service.handle(payloadHolder().build());

            verify(repo).releaseExtractionSlot(SCOPE, "");
            verify(repo, never()).claimPendingSessions(any(), anyString(), anyString(), any());
        }

        @Test
        void noPendingWorkReturnsWithoutFinishing() {
            stubSubjectEnabled();
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any())).thenReturn(null);

            service.handle(payloadHolder().build());

            verify(repo, never()).finishExtraction(any(), anyString());
            // ⚠️ 与 Go 一致：**没有**要释放的租约。
            // Go 的 `if batch == nil { return nil }` 在 `defer release(leaseID)` **之前**，
            // 而 ClaimPendingSessions 在"没有待办"时根本没设租约——所以这里不该释放任何东西。
            verify(repo, never()).releaseExtractionSlot(any(), anyString());
        }
    }

    @Nested
    @DisplayName("Handle：租约")
    class Lease {

        @Test
        void busyLeaseIsRequeuedForLaterWithoutTouchingTheWorkerLease() {
            stubSubjectEnabled();
            OffsetDateTime retryAt = OffsetDateTime.now().plusSeconds(60);
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any()))
                    .thenReturn(MemoryExtractionBatch.retryAt(retryAt));

            service.handle(payloadHolder().build());

            ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);
            verify(queue).enqueue(any(), delay.capture());
            // Go: time.Until(batch.RetryAt) + time.Second
            assertThat(delay.getValue()).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(62));
            // 重投分支在 defer 之前返回 → 绝不释放当前这个 worker 的租约
            verify(repo, never()).releaseExtractionSlot(any(), anyString());
        }

        @Test
        void busyLeaseWithoutAQueueIsAFailure() {
            stubSubjectEnabled();
            when(queueProvider.getIfAvailable()).thenReturn(null);
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any()))
                    .thenReturn(MemoryExtractionBatch.retryAt(OffsetDateTime.now().plusSeconds(60)));

            assertThatThrownBy(() -> service.handle(payloadHolder().build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("leased until");
            // 这个分支在 defer 之前 return（Go 也是），且租约属于**别人**——不释放。
            verify(repo, never()).releaseExtractionSlot(any(), anyString());
        }

        @Test
        void theWorkerLeaseIsAlwaysTheOneCapturedByTheClaim() {
            stubSubjectEnabled();
            MemoryExtractionSession s = session("sess-1");
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any()))
                    .thenReturn(MemoryExtractionBatch.of(List.of(s)));
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of());
            when(repo.hasPendingExtraction(SCOPE)).thenReturn(false);

            service.handle(payloadHolder().build());

            ArgumentCaptor<String> claimLease = ArgumentCaptor.forClass(String.class);
            verify(repo).claimPendingSessions(eq(SCOPE), anyString(), claimLease.capture(), any());
            ArgumentCaptor<String> releaseLease = ArgumentCaptor.forClass(String.class);
            verify(repo).releaseExtractionSlot(eq(SCOPE), releaseLease.capture());
            ArgumentCaptor<String> finishLease = ArgumentCaptor.forClass(String.class);
            verify(repo).finishExtraction(eq(SCOPE), finishLease.capture());

            assertThat(claimLease.getValue()).hasSize(36);
            assertThat(releaseLease.getValue()).isEqualTo(claimLease.getValue());
            assertThat(finishLease.getValue()).isEqualTo(claimLease.getValue());
        }
    }

    @Nested
    @DisplayName("Handle：正常路径")
    class HappyPath {

        @Test
        void aSessionWithNoReadableRowsIsCheckpointedAsDrained() {
            stubSubjectEnabled();
            MemoryExtractionSession s = session("sess-1");
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any()))
                    .thenReturn(MemoryExtractionBatch.of(List.of(s)));
            when(messages.listAfterCursor(eq("sess-1"), any(), eq(MemoryExtractionService.EXTRACT_MAX_MESSAGES_PER_RUN + 1)))
                    .thenReturn(List.of());
            when(repo.hasPendingExtraction(SCOPE)).thenReturn(false);

            service.handle(payloadHolder().build());

            // Go: CheckpointExtraction(ctx, scope, leaseID, session, session.Cursor, true)
            ArgumentCaptor<MemoryMessageCursor> cursor = ArgumentCaptor.forClass(MemoryMessageCursor.class);
            verify(repo).checkpointExtraction(eq(SCOPE), anyString(), eq(s), cursor.capture(), eq(true));
            assertThat(cursor.getValue().getId()).isEmpty();
            verify(repo).expireOverdue(SCOPE);
            verify(repo).finishExtraction(eq(SCOPE), anyString());
            // 模型 id 由替身给出（生产代码里 extractionModelId 恒非 null 字符串）
            verify(consolidation).consolidateIfDue(eq(SCOPE), any(), any(), any());
        }

        @Test
        void followUpIsQueuedWhenWorkRemainsAndTheSlotIsClaimedAgain() {
            stubSubjectEnabled();
            MemoryExtractionSession s = session("sess-1");
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any()))
                    .thenReturn(MemoryExtractionBatch.of(List.of(s)));
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of());
            when(repo.hasPendingExtraction(SCOPE)).thenReturn(true);
            MemorySubject snapshot = new MemorySubject();
            when(repo.enqueuePendingSession(eq(SCOPE), eq(""),
                    any(Duration.class)))
                    .thenReturn(new MemoryRepository.EnqueueResult(snapshot, true));

            service.handle(payloadHolder().build());

            ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);
            verify(queue).enqueue(any(), delay.capture());
            // Go: extractFollowUpDelay（15s）
            assertThat(delay.getValue()).isEqualTo(MemoryExtractionService.EXTRACT_FOLLOW_UP_DELAY);
        }

        @Test
        void noFollowUpWhenNothingIsPending() {
            stubSubjectEnabled();
            MemoryExtractionSession s = session("sess-1");
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any()))
                    .thenReturn(MemoryExtractionBatch.of(List.of(s)));
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of());
            when(repo.hasPendingExtraction(SCOPE)).thenReturn(false);

            service.handle(payloadHolder().build());

            verify(queue, never()).enqueue(any(), any());
        }
    }

    @Nested
    @DisplayName("Handle：模型输出不合法")
    class InvalidOutput {

        private void stubUnusableModel() {
            stubSubjectEnabled();
            when(memoryService.extractionModelId(any(), any())).thenReturn("chat-1");
            MemoryModelResolver resolver = mock(MemoryModelResolver.class);
            when(resolver.getChatModel("chat-1")).thenReturn(new FakeChat(""));
            when(memoryService.modelResolver()).thenReturn(resolver);
        }

        @Test
        void retriesTheRangeLaterUnlessTheFailureBudgetIsExhausted() {
            stubUnusableModel();
            MemoryExtractionSession s = session("sess-1");
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any()))
                    .thenReturn(MemoryExtractionBatch.of(List.of(s)));
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of(
                    message("m1", "user", "生产库用的是 MySQL", at("2026-03-02T09:05:00Z"))));
            when(repo.recordExtractionFailure(eq(SCOPE), anyString(), any()))
                    .thenReturn(false);
            when(repo.hasPendingExtraction(SCOPE)).thenReturn(false);

            service.handle(payloadHolder().build());

            ArgumentCaptor<MemoryExtractionFailure> failure =
                    ArgumentCaptor.forClass(MemoryExtractionFailure.class);
            verify(repo).recordExtractionFailure(eq(SCOPE), anyString(), failure.capture());
            assertThat(failure.getValue().code()).isEqualTo("invalid_model_output");
            // 不跳过 → 这一段**不**推进游标（留待重投），但仍要收尾
            verify(repo, never()).checkpointExtraction(any(), anyString(), any(), any(), eq(true));
            verify(repo).finishExtraction(eq(SCOPE), anyString());
            // 队列在，所以 retryErr 被随后继运行吸收，不外抛
            verify(consolidation).consolidateIfDue(eq(SCOPE), any(), any(), any());
        }

        @Test
        void skipsTheSegmentOnceTheBudgetIsExhausted() {
            stubUnusableModel();
            MemoryExtractionSession s = session("sess-1");
            when(repo.claimPendingSessions(eq(SCOPE), anyString(), anyString(), any()))
                    .thenReturn(MemoryExtractionBatch.of(List.of(s)));
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of(
                    message("m1", "user", "生产库用的是 MySQL", at("2026-03-02T09:05:00Z"))));
            when(repo.recordExtractionFailure(eq(SCOPE), anyString(), any())).thenReturn(true);
            when(repo.hasPendingExtraction(SCOPE)).thenReturn(false);

            service.handle(payloadHolder().build());

            // 跳过（skip=true）→ 照常推进水位线，drained 为 true（没有更多消息、也是最后一段）
            ArgumentCaptor<MemoryMessageCursor> cursor = ArgumentCaptor.forClass(MemoryMessageCursor.class);
            verify(repo).checkpointExtraction(eq(SCOPE), anyString(), eq(s), cursor.capture(), eq(true));
            assertThat(cursor.getValue().getId()).isEqualTo("m1");
        }
    }

    @Nested
    @DisplayName("collectSessionSegments：分段与上下文")
    class Segments {

        @Test
        void aLongSilenceBetweenUserMessagesStartsANewSegment() {
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of(
                    message("m1", "user", "第一段", at("2026-03-02T09:00:00Z")),
                    message("m2", "assistant", "好的", at("2026-03-02T09:01:00Z")),
                    // 与上一条用户消息相隔 > 1 小时 → 冲断
                    message("m3", "user", "第二段", at("2026-03-02T11:30:00Z"))));
            when(messages.listBeforeTime(anyString(), any(), anyInt())).thenReturn(List.of());

            var collected = service.collectSessionSegments(sessionWithCursor("sess-1"));

            assertThat(collected.more()).isFalse();
            assertThat(collected.segments()).hasSize(2);
            assertThat(collected.segments().get(0).lines).hasSize(1);
            assertThat(collected.segments().get(0).lines.get(0).content).isEqualTo("第一段");
            // 助手行不算 lines，但会把水位线推到它
            assertThat(collected.segments().get(0).endId).isEqualTo("m2");
            assertThat(collected.segments().get(1).lines.get(0).content).isEqualTo("第二段");
            assertThat(collected.segments().get(1).endId).isEqualTo("m3");
        }

        @Test
        void nonUserAndBlankRowsNeverBecomeExtractableLines() {
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of(
                    message("m1", "assistant", "我先说", at("2026-03-02T09:00:00Z")),
                    message("m2", "user", "   ", at("2026-03-02T09:01:00Z")),
                    message("m3", "user", " 有内容 ", at("2026-03-02T09:02:00Z"))));
            when(messages.listBeforeTime(anyString(), any(), anyInt())).thenReturn(List.of());

            var collected = service.collectSessionSegments(sessionWithCursor("sess-1"));

            assertThat(collected.segments()).hasSize(1);
            assertThat(collected.segments().get(0).lines).hasSize(1);
            // 内容去掉了首尾空白（对照 Go 的 strings.TrimSpace）
            assertThat(collected.segments().get(0).lines.get(0).content).isEqualTo("有内容");
            assertThat(collected.segments().get(0).endId).isEqualTo("m3");
        }

        @Test
        void aPageOverTheCapReportsMoreAndKeepsOnlyTheFirstForty() {
            List<Message> rows = new ArrayList<>();
            for (int i = 0; i < MemoryExtractionService.EXTRACT_MAX_MESSAGES_PER_RUN + 1; i++) {
                rows.add(message("m" + i, "user", "行" + i, at("2026-03-02T09:00:00Z").plusMinutes(i)));
            }
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(rows);
            when(messages.listBeforeTime(anyString(), any(), anyInt())).thenReturn(List.of());

            var collected = service.collectSessionSegments(sessionWithCursor("sess-1"));

            assertThat(collected.more()).isTrue();
            int lines = collected.segments().stream().mapToInt(s -> s.lines.size()).sum();
            assertThat(lines).isEqualTo(MemoryExtractionService.EXTRACT_MAX_MESSAGES_PER_RUN);
        }

        @Test
        void laterSegmentsTakeTheirContextFromThePreviousSegmentsTail() {
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of(
                    message("m1", "user", "一", at("2026-03-02T09:00:00Z")),
                    message("m2", "user", "二", at("2026-03-02T09:01:00Z")),
                    message("m3", "user", "三", at("2026-03-02T09:02:00Z")),
                    message("m4", "user", "四", at("2026-03-02T09:03:00Z")),
                    message("m5", "user", "五", at("2026-03-02T09:04:00Z")),
                    message("m6", "user", "六", at("2026-03-02T12:00:00Z"))));
            when(messages.listBeforeTime(eq("sess-1"), any(), anyInt())).thenReturn(List.of());

            var collected = service.collectSessionSegments(sessionWithCursor("sess-1"));

            assertThat(collected.segments()).hasSize(2);
            // 第一段：priorContext 从消息历史取，历史为空 → Go 的 tailContents 回 **nil**
            assertThat(collected.segments().get(0).context).isNull();
            // 第二段：取上一段 lines 的**尾部 4 条**（extractContextLines = 4）
            assertThat(collected.segments().get(1).context).containsExactly("二", "三", "四", "五");
        }

        @Test
        void priorContextReadsBackwardsAndKeepsOnlyTheLastFourUserLines() {
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of(
                    message("m9", "user", "新的", at("2026-03-02T12:00:00Z"))));
            when(messages.listBeforeTime(eq("sess-1"), any(), anyInt())).thenReturn(List.of(
                    message("a1", "assistant", "忽略我", at("2026-03-02T09:00:00Z")),
                    message("a2", "user", "c1", at("2026-03-02T09:01:00Z")),
                    message("a3", "user", "c2", at("2026-03-02T09:02:00Z")),
                    message("a4", "user", "c3", at("2026-03-02T09:03:00Z")),
                    message("a5", "user", "c4", at("2026-03-02T09:04:00Z")),
                    message("a6", "user", "c5", at("2026-03-02T09:05:00Z"))));

            var collected = service.collectSessionSegments(sessionWithCursor("sess-1"));

            // 只留最后 4 条**用户**消息，助手行不算
            assertThat(collected.segments().get(0).context).containsExactly("c2", "c3", "c4", "c5");
        }

        @Test
        void aVeryLongPastedLineIsTruncatedToOneThousandRunes() {
            String long_ = "字".repeat(MemoryExtractionService.EXTRACT_MAX_LINE_RUNES + 500);
            when(messages.listAfterCursor(anyString(), any(), anyInt())).thenReturn(List.of(
                    message("m1", "user", long_, at("2026-03-02T09:00:00Z"))));
            when(messages.listBeforeTime(anyString(), any(), anyInt())).thenReturn(List.of());

            var collected = service.collectSessionSegments(sessionWithCursor("sess-1"));

            assertThat(collected.segments().get(0).lines.get(0).content.codePointCount(
                    0, collected.segments().get(0).lines.get(0).content.length()))
                    .isEqualTo(MemoryExtractionService.EXTRACT_MAX_LINE_RUNES);
        }

        private MemoryExtractionSession sessionWithCursor(String sessionId) {
            MemoryExtractionSession s = session(sessionId);
            s.setCursor(new MemoryMessageCursor(at("2026-03-01T00:00:00Z"), "cursor-id"));
            return s;
        }
    }

    @Nested
    @DisplayName("applyDecisions：模型输出落地")
    class Decisions {

        private MemoryExtractionService.TranscriptSegment segmentWith(int lines) {
            MemoryExtractionService.TranscriptSegment segment =
                    new MemoryExtractionService.TranscriptSegment();
            segment.sessionId = "s";
            for (int i = 0; i < lines; i++) {
                segment.lines.add(new MemoryExtractionService.TranscriptLine("s", "m" + (i + 1),
                        at("2026-03-02T09:0" + i + ":00Z"), "line" + (i + 1)));
            }
            return segment;
        }

        private MemoryExtractionService.ExtractionDecision decision(String action, String topic,
                                                                   String content, Integer target,
                                                                   Integer source) {
            MemoryExtractionService.ExtractionDecision d =
                    new MemoryExtractionService.ExtractionDecision();
            d.action = action;
            d.kind = MemoryKinds.KIND_FACT;
            d.topic = topic;
            d.content = content;
            d.target = target;
            d.source = source;
            return d;
        }

        private MemoryItem existingItem(String id, String topic, String content) {
            MemoryItem it = new MemoryItem();
            it.setId(id);
            it.setTopic(topic);
            it.setContent(content);
            return it;
        }

        @Test
        void noneAndBlankActionsAreIgnored() {
            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), List.of(),
                    List.of(decision("", "t", "c", null, 1),
                            decision("none", "t", "c", null, 1),
                            decision("noop", "t", "c", null, 1),
                            decision("NONE", "t", "c", null, 1)));

            verify(memoryService, never()).writeReplacing(any(), any(), any(), anyString());
            verify(repo, never()).supersedeItem(any(), anyString(), anyString());
        }

        @Test
        void updateAndDeleteWithAnOutOfRangeTargetAreSkipped() {
            List<MemoryItem> existing = List.of(existingItem("i1", "t", "c"));
            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), existing,
                    List.of(decision("update", "t", "c2", 5, 1),
                            decision("delete", "t", "c3", -1, 1)));

            verify(memoryService, never()).writeReplacing(any(), any(), any(), anyString());
            verify(repo, never()).supersedeItem(any(), anyString(), anyString());
        }

        @Test
        void deleteSupersedesWithNoReplacement() {
            List<MemoryItem> existing = List.of(existingItem("i1", "t", "c"));

            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), existing,
                    List.of(decision("delete", "t", "c", 0, 1)));

            // 「作废而非删除」——记忆管理器仍然能解释它为什么不再成立
            verify(repo).supersedeItem(SCOPE, "i1", "");
            verify(memoryService).rebuildBlock(SCOPE);
        }

        @Test
        void updateWithATargetCarriesTheReplaceId() {
            List<MemoryItem> existing = List.of(existingItem("i1", "在用的数据库", "生产库用的是 MySQL"));
            service.applyDecisions(SCOPE, autoCfg(), segmentWith(2), existing,
                    List.of(decision("update", "在用的数据库", "生产库已迁到 PostgreSQL", 0, 2)));

            ArgumentCaptor<MemoryItem> item = ArgumentCaptor.forClass(MemoryItem.class);
            verify(memoryService).writeReplacing(eq(SCOPE), any(), item.capture(), eq("i1"));
            assertThat(item.getValue().getContent()).isEqualTo("生产库已迁到 PostgreSQL");
            assertThat(item.getValue().getTopic()).isEqualTo("在用的数据库");
            assertThat(item.getValue().getOrigin()).isEqualTo(MemoryKinds.ORIGIN_EXTRACTED);
            // source = 2 → 第 2 行的消息 id 与时间
            assertThat(item.getValue().getSourceMessageId()).isEqualTo("m2");
            assertThat(item.getValue().getSourceSessionId()).isEqualTo("s");
        }

        @Test
        void updateWithoutATargetFallsBackToTheTopicKey() {
            MemoryItem found = existingItem("i1", "在用的数据库", "生产库用的是 MySQL");
            when(repo.findActiveByKey(eq(SCOPE), anyString())).thenReturn(found);

            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), List.of(),
                    List.of(decision("update", "在用的数据库", "生产库已迁到 PostgreSQL", null, 1)));

            verify(repo).findActiveByKey(eq(SCOPE), anyString());
            verify(memoryService).writeReplacing(eq(SCOPE), any(), any(), eq("i1"));
        }

        @Test
        void whenTheTopicKeyIsUnknownTheDecisionIsDropped() {
            when(repo.findActiveByKey(eq(SCOPE), anyString())).thenReturn(null);

            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), List.of(),
                    List.of(decision("update", "未知主题", "x", null, 1)));

            verify(memoryService, never()).writeReplacing(any(), any(), any(), anyString());
        }

        @Test
        void twoDecisionsAboutTheSameKeyInsideOneResponseCollapseIntoOne() {
            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), List.of(),
                    List.of(decision("add", "t", "c", null, 1),
                            decision("add", "t", "c", null, 1)));

            verify(memoryService).writeReplacing(eq(SCOPE), any(), any(), eq(""));
        }

        @Test
        void swallowedWriteFailuresDoNotAbortTheRestOfTheRun() {
            when(memoryService.writeReplacing(any(), any(), any(), anyString()))
                    .thenThrow(new MemoryScopeExceptions.PreviouslyForgotten())
                    .thenThrow(new MemoryScopeExceptions.SensitiveContent())
                    .thenThrow(new com.ragagent.memory.domain.MemoryConflictException())
                    .thenReturn(new MemoryItem());

            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), List.of(),
                    List.of(decision("add", "t1", "c1", null, 1),
                            decision("add", "t2", "c2", null, 1),
                            decision("add", "t3", "c3", null, 1),
                            decision("add", "t4", "c4", null, 1)));

            // 三种"不算失败"的结果都被吞掉，第四条照常写入
            verify(memoryService, org.mockito.Mockito.times(4))
                    .writeReplacing(eq(SCOPE), any(), any(), eq(""));
        }

        @Test
        void otherWriteFailuresPropagate() {
            when(memoryService.writeReplacing(any(), any(), any(), anyString()))
                    .thenThrow(new IllegalStateException("boom"));

            assertThatThrownBy(() -> service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), List.of(),
                    List.of(decision("add", "t", "c", null, 1))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("apply memory decision");
        }

        @Test
        void atMostEightItemsAreAppliedPerRun() {
            List<MemoryExtractionService.ExtractionDecision> decisions = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                decisions.add(decision("add", "t" + i, "c" + i, null, 1));
            }
            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), List.of(), decisions);

            verify(memoryService, org.mockito.Mockito.times(
                    MemoryExtractionService.EXTRACT_MAX_ITEMS_PER_RUN))
                    .writeReplacing(any(), any(), any(), anyString());
        }

        @Test
        void addWithSanitisesToNothingIsSkipped() {
            service.applyDecisions(SCOPE, autoCfg(), segmentWith(1), List.of(),
                    List.of(decision("add", "t", "   ", null, 1)));

            verify(memoryService, never()).writeReplacing(any(), any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("ScheduleExtraction")
    class Schedule {

        @Test
        void debounceSaysNoWhenARunIsAlreadyComing() {
            when(memoryService.enabledScope()).thenReturn(
                    new MemoryService.ScopeState(SCOPE, autoCfg(), true));
            when(repo.ensureSubject(SCOPE)).thenReturn(enabledSubject());
            when(repo.enqueuePendingSession(eq(SCOPE), eq("sess-1"), any(Duration.class)))
                    .thenReturn(new MemoryRepository.EnqueueResult(new MemorySubject(), false));

            service.scheduleExtraction("sess-1", "msg-1", "chat-1");

            verify(queue, never()).enqueue(any(), any());
        }

        @Test
        void tooSoonDefersTheTaskInsteadOfDroppingTheTurn() {
            when(memoryService.enabledScope()).thenReturn(
                    new MemoryService.ScopeState(SCOPE, autoCfg(), true));
            when(repo.ensureSubject(SCOPE)).thenReturn(enabledSubject());
            MemorySubject previous = new MemorySubject();
            // 上一次抽取是刚刚发生的 → 剩余间隔比 delay 长，任务被排得更远
            previous.setLastExtractedAt(OffsetDateTime.now());
            when(repo.enqueuePendingSession(eq(SCOPE), eq("sess-1"), any(Duration.class)))
                    .thenReturn(new MemoryRepository.EnqueueResult(previous, true));

            service.scheduleExtraction("sess-1", "msg-1", "chat-1");

            ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);
            verify(queue).enqueue(any(), delay.capture());
            // extract_min_interval 默认 300s > extract_delay 默认 90s → 实际延迟被抬到接近 300s
            assertThat(delay.getValue()).isGreaterThan(Duration.ofSeconds(200));
        }

        @Test
        void blankSessionOrMessageIsIgnored() {
            when(memoryService.enabledScope()).thenReturn(
                    new MemoryService.ScopeState(SCOPE, autoCfg(), true));
            service.scheduleExtraction("", "msg-1", "chat-1");
            service.scheduleExtraction("sess-1", "", "chat-1");
            verify(repo, never()).ensureSubject(any());
        }

        @Test
        void autoExtractOffIsIgnored() {
            when(memoryService.enabledScope()).thenReturn(
                    new MemoryService.ScopeState(SCOPE, new MemoryConfig(), true));
            service.scheduleExtraction("sess-1", "msg-1", "chat-1");
            verifyNoInteractions(queue);
        }
    }

    /** 返回固定正文的假聊天客户端（不出网）。 */
    private static final class FakeChat implements LlmChatClient {
        private final String content;

        FakeChat(String content) {
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            ChatResponse response = new ChatResponse();
            response.setContent(content);
            return response;
        }

        @Override
        public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getModelName() {
            return "fake";
        }

        @Override
        public String getModelId() {
            return "fake";
        }
    }
}
