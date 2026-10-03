package com.ragagent.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.TokenEstimator;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

/**
 * 切点选择的录制常量断言。
 * 六个场景：轮内切分（ReAct 回归场景）、预算装得下、全 tool 无合法切点、
 * 轮边界切分、切在轮内（split）、start&gt;1 的窗口偏移。
 */
class CutPointTest {

    private final TokenEstimator estimator = new TokenEstimator();

    static String filler(int n) {
        return "some conversation content ".repeat(n);
    }

    /** 录制脚本 reactTurn：一条 user + N 组 assistant/tool（无第二个 user 轮次）。 */
    static List<ChatMessage> reactTurn(int rounds) {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage("system", "you are an agent"));
        msgs.add(new ChatMessage("user", "build me a deck"));
        for (int i = 0; i < rounds; i++) {
            ToolCall tc = new ToolCall();
            tc.setId("call-" + (char) ('a' + i % 26));
            tc.setFunction(new com.ragagent.llm.domain.FunctionCall("write_sandbox_file",
                    "{\"path\":\"/workspace/out.html\",\"content\":\"" + filler(40) + "\"}"));
            ChatMessage assistant = new ChatMessage("assistant", filler(20));
            assistant.setToolCalls(List.of(tc));
            msgs.add(assistant);
            msgs.add(ChatMessage.tool("call-" + (char) ('a' + i % 26), "write_sandbox_file", filler(30)));
        }
        return msgs;
    }

    /** 录制脚本 multiFixture。 */
    static List<ChatMessage> multiFixture() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage("system", "you are an agent"));
        msgs.add(new ChatMessage("user", "first question " + filler(400)));
        msgs.add(new ChatMessage("assistant", "first answer " + filler(400)));
        msgs.add(new ChatMessage("user", "second question " + filler(400)));
        msgs.add(new ChatMessage("assistant", "second answer " + filler(400)));
        msgs.add(new ChatMessage("user", "third question " + filler(5)));
        msgs.add(new ChatMessage("assistant", "third answer"));
        return msgs;
    }

    private static String fmt(CutPoint c) {
        return "FirstKeptIdx=%d TurnStartIdx=%d IsSplitTurn=%s"
                .formatted(c.getFirstKeptIdx(), c.getTurnStartIdx(), c.isSplitTurn());
    }

    @Test
    void scenarioA_reactTurnSplits() {
        assertThat(fmt(CutPoint.findCutPoint(reactTurn(12), 1, 2000, estimator)))
                .isEqualTo("FirstKeptIdx=14 TurnStartIdx=1 IsSplitTurn=true");
    }

    @Test
    void scenarioB_hugeBudgetKeepsEverything() {
        assertThat(fmt(CutPoint.findCutPoint(reactTurn(12), 1, 10_000_000, estimator)))
                .isEqualTo("FirstKeptIdx=1 TurnStartIdx=-1 IsSplitTurn=false");
    }

    @Test
    void scenarioC_toolOnlyRangeHasNoCutPoints() {
        List<ChatMessage> toolOnly = List.of(
                new ChatMessage("system", "sys"),
                ChatMessage.tool("id1", "t", "r1"),
                ChatMessage.tool("id2", "t", "r2"));
        assertThat(fmt(CutPoint.findCutPoint(toolOnly, 1, 2000, estimator)))
                .isEqualTo("FirstKeptIdx=1 TurnStartIdx=-1 IsSplitTurn=false");
    }

    @Test
    void scenarioD_cutLandsOnTurnBoundary() {
        List<ChatMessage> multi = multiFixture();
        // 预算 600：尾部一组（6+22=28）之外还能吃下 idx4？不能——1235>600 停在 idx5（user 边界）
        assertThat(fmt(CutPoint.findCutPoint(multi, 1, 600, estimator)))
                .isEqualTo("FirstKeptIdx=5 TurnStartIdx=-1 IsSplitTurn=false");
        // 每条消息的估算值逐条钉住（录音 cutD_tok1..6）
        long[] expected = {1207, 1207, 1207, 1207, 22, 6};
        for (int i = 1; i <= 6; i++) {
            assertThat(estimator.estimateMessage(multi.get(i))).as("tok%d", i).isEqualTo((int) expected[i - 1]);
        }
    }

    @Test
    void scenarioE_budgetSplitsSecondTurn() {
        // 预算 1500：28+1207=1235 ≤ 1500 → cutIdx=4（assistant）→ 轮内切分
        assertThat(fmt(CutPoint.findCutPoint(multiFixture(), 1, 1500, estimator)))
                .isEqualTo("FirstKeptIdx=4 TurnStartIdx=3 IsSplitTurn=true");
    }

    @Test
    void scenarioF_windowOffsetStart() {
        assertThat(fmt(CutPoint.findCutPoint(multiFixture(), 3, 250, estimator)))
                .isEqualTo("FirstKeptIdx=5 TurnStartIdx=-1 IsSplitTurn=false");
    }

    @Test
    void historyStartSkipsSystemPrompt() {
        assertThat(CutPoint.historyStart(multiFixture())).isEqualTo(1);
        assertThat(CutPoint.historyStart(multiFixture().subList(1, multiFixture().size()))).isZero();
        assertThat(CutPoint.historyStart(List.of())).isZero();
    }
}
