package com.ragagent.agent.compaction;

import static com.ragagent.agent.GoRecording.STR_E2E_LENGTH_STOP_SUMMARY;
import static com.ragagent.agent.GoRecording.STR_E2E_REACT12_SUMMARY;
import static com.ragagent.agent.GoRecording.STR_GROWN_PROMPT0;
import static com.ragagent.agent.GoRecording.STR_PROMPT_INITIAL;
import static com.ragagent.agent.GoRecording.STR_PROMPT_UPDATE;
import static com.ragagent.agent.GoRecording.STR_SUMMARIZATIONSYSTEMPROMPT;
import static com.ragagent.agent.GoRecording.STR_SUMMARYFORMAT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.TokenEstimator;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.StreamResponse;

/**
 * 压缩器端到端的 Go 实录断言（/tmp/wave42rec：compactor.go + stub 内嵌）。
 * 场景：ReAct 轮压缩（12/40 round）、length 停止拒绝→档案回退、LLM 失败→档案回退、
 * 二次压缩无事可压、增量更新路径、小对话不调 LLM、validateSummary 判定表、
 * buildSummarizationPrompt 完整输出、Reason/ErrNothingToCompact 文案。
 */
class CompactorTest {

    /** 对照录制脚本的 stubChat：记调用次数与 prompt、返回罐装回复。 */
    private static class StubChat implements LlmChatClient {
        String response;
        String finishReason;
        RuntimeException err;
        int calls;
        final List<String> prompts = new java.util.ArrayList<>();
        ChatOptions lastOptions;

        StubChat(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            calls++;
            lastOptions = options;
            if (messages.size() > 1) {
                prompts.add(messages.get(messages.size() - 1).getContent());
            }
            if (err != null) {
                throw err;
            }
            ChatResponse r = new ChatResponse();
            r.setContent(response);
            r.setFinishReason(finishReason);
            return r;
        }

        @Override
        public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
            return null;
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public String getModelId() {
            return "stub";
        }
    }

    private final TokenEstimator estimator = new TokenEstimator();

    private static CompactionSettings testSettings() {
        return new CompactionSettings(true, 40000, 8000, 2000, 0);
    }

    private record RunResult(CompactionResult result, String errIsNothing, int llmCalls) {
    }

    private RunResult run(StubChat llm, List<ChatMessage> msgs) {
        Compactor c = Compactor.create(llm, estimator, testSettings());
        assertThat(c).isNotNull();
        try {
            CompactionResult res = c.compact(msgs, CompactionReason.THRESHOLD);
            return new RunResult(res, null, llm.calls);
        } catch (NothingToCompactException e) {
            return new RunResult(null, e.getMessage(), llm.calls);
        }
    }

    @Test
    void e2eReactTurnCompactsInsideASingleTurn() {
        for (int rounds : new int[] {12, 40}) {
            StubChat llm = new StubChat("## Goal\nbuild a deck");
            RunResult run = run(llm, CutPointTest.reactTurn(rounds));
            CompactionResult res = run.result();
            assertThat(res).isNotNull();
            assertThat(res.getTokensBefore()).isEqualTo(rounds == 12 ? 3667 : 12179);
            assertThat(res.getTokensAfter()).isEqualTo(1891);
            assertThat(res.getMessagesBefore()).isEqualTo(rounds == 12 ? 26 : 82);
            assertThat(res.getMessagesAfter()).isEqualTo(14);
            assertThat(res.isDegraded()).isFalse();
            assertThat(res.isSplitTurn()).isTrue();
            assertThat(run.llmCalls()).isEqualTo(1);
            // 摘要逐字节（含 split-turn 前缀段与 modified-files 块）
            if (rounds == 12) {
                assertThat(res.getSummary()).isEqualTo(STR_E2E_REACT12_SUMMARY);
            }
            // freed > 0 且终态远低于阈值
            assertThat(res.freed()).isGreaterThan(0);
            if (rounds == 40) {
                // Go TestCompactsInsideASingleTurn：40 轮的终态必须低于阈值一半
                assertThat(res.getTokensAfter()).isLessThan(res.getTokensBefore() / 2);
            }
            // 消息列表骨架：system + summary + 6 组 assistant/tool
            assertThat(res.getMessages()).hasSize(14);
            assertThat(res.getMessages().get(0).getRole()).isEqualTo("system");
            assertThat(res.getMessages().get(1).isCompactionSummary()).isTrue();
            for (int i = 2; i < 14; i += 2) {
                assertThat(res.getMessages().get(i).getRole()).isEqualTo("assistant");
                assertThat(res.getMessages().get(i + 1).getRole()).isEqualTo("tool");
                // 保留的 tool 结果必须能找到发起调用（无孤儿）
                assertThat(res.getMessages().get(i).getToolCalls().get(0).getId())
                        .isEqualTo(res.getMessages().get(i + 1).getToolCallId());
            }
        }
    }

    @Test
    void truncatedSummaryRejectedAndArchiveFallback() {
        StubChat llm = new StubChat("## Goal\nhalf a sum");
        llm.finishReason = "length";
        RunResult run = run(llm, CutPointTest.reactTurn(12));
        CompactionResult res = run.result();
        assertThat(res.isDegraded()).isTrue();
        assertThat(res.getTokensAfter()).isEqualTo(3198);
        assertThat(run.llmCalls()).isEqualTo(2); // 仅前缀摘要的两次 attempt
        assertThat(res.getSummary()).isEqualTo(STR_E2E_LENGTH_STOP_SUMMARY);
        assertThat(res.getSummary()).contains("Raw conversation archive");
        assertThat(res.getSummary()).doesNotContain("half a sum");
    }

    @Test
    void summarizerFailureFallsBackToArchive() {
        StubChat llm = new StubChat("x");
        llm.err = new RuntimeException("boom");
        RunResult run = run(llm, CutPointTest.reactTurn(12));
        CompactionResult res = run.result();
        assertThat(res.isDegraded()).isTrue();
        assertThat(res.freed()).isGreaterThan(0);
        assertThat(res.getSummary()).contains("Raw conversation archive");
        assertThat(run.llmCalls()).isEqualTo(2);
    }

    @Test
    void secondCompactionReportsNothingToDo() {
        StubChat llm = new StubChat("## Goal\nfirst pass");
        Compactor c = Compactor.create(llm, estimator, testSettings());
        CompactionResult first = c.compact(CutPointTest.reactTurn(12), CompactionReason.THRESHOLD);
        assertThat(llm.calls).isEqualTo(1);
        // 二次压缩：null 准备 → 停止信号，且不烧 LLM 调用
        try {
            c.compact(first.getMessages(), CompactionReason.THRESHOLD);
            org.junit.jupiter.api.Assertions.fail("expected NothingToCompactException");
        } catch (NothingToCompactException e) {
            assertThat(e.getMessage())
                    .isEqualTo("compaction: nothing outside the keep-recent budget");
        }
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    void previousSummaryIsUpdatedNotResummarized() {
        StubChat llm = new StubChat("## Goal\nfirst pass");
        Compactor c = Compactor.create(llm, estimator, testSettings());
        CompactionResult first = c.compact(CutPointTest.reactTurn(12), CompactionReason.THRESHOLD);

        // 压缩之后来了新工作 → 有东西可折入
        List<ChatMessage> grown = new java.util.ArrayList<>(first.getMessages());
        grown.addAll(CutPointTest.reactTurn(10).subList(2, CutPointTest.reactTurn(10).size()));
        StubChat llm2 = new StubChat("## Goal\nsecond pass");
        Compactor c2 = Compactor.create(llm2, estimator, testSettings());
        c2.compact(grown, CompactionReason.THRESHOLD);
        String prompt = llm2.prompts.get(0);
        // 完整 prompt 逐字节（含 <previous-summary> 段与 update 指令）
        assertThat(prompt).isEqualTo(STR_GROWN_PROMPT0);
        // 生存的摘要恰一条——绝不堆叠
        long summaries = grown.stream().filter(ChatMessage::isCompactionSummary).count();
        assertThat(summaries).isEqualTo(1);
    }

    @Test
    void smallConversationIsLeftAlone() {
        StubChat llm = new StubChat("s");
        Compactor c = Compactor.create(llm, estimator, testSettings());
        try {
            c.compact(List.of(
                    new ChatMessage("system", "sys"),
                    new ChatMessage("user", "hi"),
                    new ChatMessage("assistant", "hello")), CompactionReason.THRESHOLD);
            org.junit.jupiter.api.Assertions.fail("expected NothingToCompactException");
        } catch (NothingToCompactException e) {
            // 预期
        }
        assertThat(llm.calls).isZero();
    }

    @Test
    void nilCompactorSemantics() {
        assertThat(Compactor.create(null, estimator, testSettings())).isNull();
        assertThat(Compactor.create(new StubChat("x"), estimator,
                new CompactionSettings(true, 0, 0, 0, 0))).isNull();
        assertThat(CompactionReason.THRESHOLD).isEqualTo("threshold");
        assertThat(CompactionReason.OVERFLOW).isEqualTo("overflow");
    }

    @Test
    void validateSummaryTable() {
        assertThat(Compactor.validateSummary(resp("a fine summary", "stop"))).isNull();
        assertThat(Compactor.validateSummary(null)).isEqualTo("empty response from LLM");
        assertThat(Compactor.validateSummary(resp("", "stop"))).isEqualTo("empty response from LLM");
        assertThat(Compactor.validateSummary(resp("   \n\t ", "stop"))).isEqualTo("empty response from LLM");
        assertThat(Compactor.validateSummary(resp("cut off", "length")))
                .isEqualTo("generation hit the token cap and the summary is incomplete");
        assertThat(Compactor.validateSummary(resp("cut off", "max_tokens")))
                .isEqualTo("generation hit the token cap and the summary is incomplete");
        assertThat(Compactor.validateSummary(resp("cut off", "max_output_tokens")))
                .isEqualTo("generation hit the token cap and the summary is incomplete");
        assertThat(Compactor.validateSummary(resp("cut off", " LENGTH ")))
                .isEqualTo("generation hit the token cap and the summary is incomplete");
        assertThat(Compactor.validateSummary(resp("summary", "tool_calls"))).isNull();
    }

    private static ChatResponse resp(String content, String finishReason) {
        ChatResponse r = new ChatResponse();
        r.setContent(content);
        r.setFinishReason(finishReason);
        return r;
    }

    @Test
    void summarizationPromptMatchesGoByteForByte() {
        ChatMessage rocketAnswer = new ChatMessage("assistant", "A rocket is a vehicle.");
        rocketAnswer.setReasoningContent("user wants basics");
        List<ChatMessage> convo = List.of(
                new ChatMessage("user", "what is a rocket"),
                rocketAnswer);
        assertThat(Compactor.buildSummarizationPrompt(convo, "",
                CompactionPrompts.INITIAL_SUMMARIZATION_INSTRUCTIONS)).isEqualTo(STR_PROMPT_INITIAL);

        List<ChatMessage> convo2 = List.of(
                new ChatMessage("user", "now about orbit"),
                new ChatMessage("assistant", "Orbits are ellipses."));
        assertThat(Compactor.buildSummarizationPrompt(convo2, "prior summary about rockets",
                CompactionPrompts.UPDATE_SUMMARIZATION_INSTRUCTIONS)).isEqualTo(STR_PROMPT_UPDATE);

        // 常量逐字节（与 Go 源码常量对照实录）
        assertThat(CompactionPrompts.SUMMARIZATION_SYSTEM_PROMPT).isEqualTo(STR_SUMMARIZATIONSYSTEMPROMPT);
        assertThat(CompactionPrompts.SUMMARY_FORMAT).isEqualTo(STR_SUMMARYFORMAT);
    }

    @Test
    void summarizationCallOptionsAreLowTemperatureAndBudget() {
        StubChat llm = new StubChat("## Goal\nbuild a deck");
        Compactor c = Compactor.create(llm, estimator, testSettings());
        c.compact(CutPointTest.reactTurn(12), CompactionReason.THRESHOLD);
        assertThat(llm.lastOptions.getTemperature()).isEqualTo(0.3);
        assertThat(llm.lastOptions.getMaxTokens()).isEqualTo(1000);
        assertThat(llm.lastOptions.getCacheRetention())
                .isEqualTo(com.ragagent.llm.domain.CacheRetention.NONE);
    }
}
