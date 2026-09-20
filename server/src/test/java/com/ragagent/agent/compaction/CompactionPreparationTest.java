package com.ragagent.agent.compaction;

import static com.ragagent.agent.GoRecording.STR_SUMMARY_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.TokenEstimator;
import com.ragagent.llm.domain.ChatMessage;

/**
 * 准备/重建的 Go 实录断言（/tmp/wave42rec：prepare.go 内嵌）。
 * 覆盖：split-turn 准备、边界切分准备、先前摘要的识别（摘要不进自身后继的输入）、
 * 无事可压返回 null、nil estimator、Apply 重建、SummaryMessage 信封、unwrap 边界。
 */
class CompactionPreparationTest {

    private final TokenEstimator estimator = new TokenEstimator();

    private static CompactionSettings testSettings() {
        return new CompactionSettings(true, 40000, 8000, 2000, 0);
    }

    static List<ChatMessage> multiFixture() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage("system", "you are an agent"));
        msgs.add(new ChatMessage("user", "first question " + CutPointTest.filler(400)));
        msgs.add(new ChatMessage("assistant", "first answer " + CutPointTest.filler(400)));
        msgs.add(new ChatMessage("user", "second question " + CutPointTest.filler(400)));
        msgs.add(new ChatMessage("assistant", "second answer " + CutPointTest.filler(400)));
        msgs.add(new ChatMessage("user", "third question " + CutPointTest.filler(5)));
        msgs.add(new ChatMessage("assistant", "third answer"));
        return msgs;
    }

    static List<ChatMessage> multiFixtureB() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage("system", "you are an agent"));
        msgs.add(new ChatMessage("user", "old question " + CutPointTest.filler(400)));
        msgs.add(new ChatMessage("assistant", "old answer " + CutPointTest.filler(400)));
        msgs.add(new ChatMessage("user", "fresh question"));
        msgs.add(new ChatMessage("assistant", "fresh answer"));
        return msgs;
    }

    @Test
    void p1_splitTurnPreparation() {
        CompactionPreparation p = CompactionPreparation.prepare(multiFixture(), testSettings(), estimator);
        assertThat(p).isNotNull();
        assertThat(p.getFirstKeptIdx()).isEqualTo(4);
        assertThat(p.getMessagesToSummarize()).hasSize(2);
        assertThat(p.getTurnPrefixMessages()).hasSize(1);
        assertThat(p.isSplitTurn()).isTrue();
        assertThat(p.getPreviousSummary()).isEmpty();
        assertThat(p.getTokensBefore()).isEqualTo(4867);
        assertThat(p.fileOps().format()).isEmpty();
    }

    @Test
    void p1b_boundaryCutOnUserMessage() {
        CompactionSettings sB = new CompactionSettings(true, 40000, 8000, 600, 0);
        CompactionPreparation p = CompactionPreparation.prepare(multiFixtureB(), sB, estimator);
        assertThat(p).isNotNull();
        assertThat(p.getFirstKeptIdx()).isEqualTo(3);
        assertThat(p.getMessagesToSummarize()).hasSize(2);
        assertThat(p.getTurnPrefixMessages()).isEmpty();
        assertThat(p.isSplitTurn()).isFalse();
        assertThat(p.getTokensBefore()).isEqualTo(2437);

        // Apply：system + summary + tail[3..]
        List<ChatMessage> applied = CompactionPreparation.apply(multiFixtureB(), p, "BOUNDARY SUMMARY");
        assertThat(applied).hasSize(4);
        assertThat(applied.get(0).getRole()).isEqualTo("system");
        assertThat(applied.get(0).getKind()).isNull();
        assertThat(applied.get(1).getRole()).isEqualTo("user");
        assertThat(applied.get(1).isCompactionSummary()).isTrue();
        assertThat(applied.get(1).getContent()).isEqualTo(
                "The conversation history before this point was compacted into the following summary:\n\n<summary>\nBOUNDARY SUMMARY\n</summary>");
        assertThat(applied.get(2).getRole()).isEqualTo("user");
        assertThat(applied.get(2).getContent()).isEqualTo("fresh question");
        assertThat(applied.get(3).getRole()).isEqualTo("assistant");
        assertThat(applied.get(3).getContent()).isEqualTo("fresh answer");
    }

    @Test
    void p2_previousSummaryIsRecognizedAndExcluded() {
        List<ChatMessage> withSummary = new ArrayList<>();
        withSummary.add(new ChatMessage("system", "you are an agent"));
        ChatMessage summary = CompactionPreparation.summaryMessage("prior checkpoint about rockets");
        withSummary.add(summary);
        withSummary.add(new ChatMessage("user", "tell me about rockets " + CutPointTest.filler(400)));
        withSummary.add(new ChatMessage("assistant", "rockets are " + CutPointTest.filler(400)));
        withSummary.add(new ChatMessage("user", "more " + CutPointTest.filler(400)));
        withSummary.add(new ChatMessage("assistant", "even more " + CutPointTest.filler(400)));

        CompactionPreparation p = CompactionPreparation.prepare(withSummary, testSettings(), estimator);
        assertThat(p).isNotNull();
        assertThat(p.getFirstKeptIdx()).isEqualTo(5);
        assertThat(p.getMessagesToSummarize()).hasSize(2);
        assertThat(p.getPreviousSummary()).isEqualTo("prior checkpoint about rockets");
        assertThat(p.getTokensBefore()).isEqualTo(4870);
    }

    @Test
    void smallConversationAndNilEstimatorReturnNull() {
        assertThat(CompactionPreparation.prepare(
                List.of(new ChatMessage("system", "sys"),
                        new ChatMessage("user", "hi"),
                        new ChatMessage("assistant", "hello")),
                testSettings(), estimator)).isNull();
        assertThat(CompactionPreparation.prepare(multiFixture(), testSettings(), null)).isNull();
    }

    @Test
    void p5_reactTurnPrepare() {
        CompactionPreparation p = CompactionPreparation.prepare(
                CutPointTest.reactTurn(12), testSettings(), estimator);
        assertThat(p).isNotNull();
        assertThat(p.getFirstKeptIdx()).isEqualTo(14);
        assertThat(p.getMessagesToSummarize()).isEmpty();
        assertThat(p.getTurnPrefixMessages()).hasSize(13);
        assertThat(p.isSplitTurn()).isTrue();

        // Apply 骨架（录音 apply5）
        List<ChatMessage> applied = CompactionPreparation.apply(CutPointTest.reactTurn(12), p, "SPLIT SUMMARY");
        assertThat(applied).hasSize(14);
        assertThat(applied.get(0).getRole()).isEqualTo("system");
        assertThat(applied.get(0).getKind()).isNull();
        assertThat(applied.get(1).getRole()).isEqualTo("user");
        assertThat(applied.get(1).isCompactionSummary()).isTrue();
    }

    @Test
    void apply1_rebuildsSystemSummaryTail() {
        CompactionPreparation p = CompactionPreparation.prepare(multiFixture(), testSettings(), estimator);
        List<ChatMessage> applied = CompactionPreparation.apply(multiFixture(), p, "THE SUMMARY");
        assertThat(applied).hasSize(5);
        assertThat(applied.get(0).getRole()).isEqualTo("system");
        assertThat(applied.get(1).isCompactionSummary()).isTrue();
        assertThat(applied.get(2).getRole()).isEqualTo("assistant"); // second answer（切分轮次的保留后缀）
        assertThat(applied.get(3).getRole()).isEqualTo("user"); // third question
        assertThat(applied.get(3).getContent()).isEqualTo("third question " + CutPointTest.filler(5));
        assertThat(applied.get(4).getRole()).isEqualTo("assistant");
        assertThat(applied.get(4).getContent()).isEqualTo("third answer");
    }

    @Test
    void summaryMessageEnvelopeMatchesGoByteForByte() {
        ChatMessage sm = CompactionPreparation.summaryMessage("checkpoint body");
        assertThat(sm.getRole()).isEqualTo("user");
        assertThat(sm.isCompactionSummary()).isTrue();
        assertThat(sm.getContent()).isEqualTo(STR_SUMMARY_CONTENT);
    }

    @Test
    void unwrapSummaryEdgeCases() {
        assertThat(CompactionPreparation.unwrapSummary(
                CompactionPreparation.SUMMARY_PREFIX + "inner text" + CompactionPreparation.SUMMARY_SUFFIX))
                .isEqualTo("inner text");
        assertThat(CompactionPreparation.unwrapSummary("  bare summary text  ")).isEqualTo("bare summary text");
        assertThat(CompactionPreparation.unwrapSummary("prefix <summary>open only no close"))
                .isEqualTo("open only no close");
        assertThat(CompactionPreparation.unwrapSummary("</summary>reversed<summary>")).isEmpty();
    }
}
