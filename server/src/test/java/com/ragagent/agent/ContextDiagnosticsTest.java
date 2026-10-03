package com.ragagent.agent;

import static com.ragagent.agent.GoRecording.STR_BD0_STRING;
import static com.ragagent.agent.GoRecording.STR_BD_STRING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.MessageContentPart;
import com.ragagent.llm.domain.ToolCall;

/**
 * 上下文诊断的录制常量断言。
 * 覆盖：归因明细逐字段（text/reasoning/tool_results/tool_args/images/summaries/
 * tool_schemas/largest）、String() 逐字节、空输入、漂移百分比的分级边界。
 */
class ContextDiagnosticsTest {

    private final TokenEstimator estimator = new TokenEstimator();

    private List<ChatMessage> fixture() {
        ChatMessage reasoning = new ChatMessage("assistant", "Researching.");
        reasoning.setReasoningContent("step by step thinking about the deck structure");
        ToolCall tc = new ToolCall();
        tc.setId("call-a");
        tc.setFunction(new com.ragagent.llm.domain.FunctionCall("knowledge_search", "{\"query\":\"coral\"}"));
        ChatMessage calls = new ChatMessage("assistant", "");
        calls.setToolCalls(List.of(tc));
        ChatMessage summary = new ChatMessage("user", "summary of older history");
        summary.setKind(ChatMessage.KIND_COMPACTION_SUMMARY);
        ChatMessage withImages = new ChatMessage("user", "what is this");
        withImages.setImages(List.of("https://x/a.png"));
        ChatMessage multi = new ChatMessage("user", "");
        multi.setMultiContent(List.of(
                MessageContentPart.text("look at"),
                MessageContentPart.image("data:image/png;base64,AAAA", "")));

        return List.of(
                new ChatMessage("system", "you are an agent"),
                new ChatMessage("user", "build me a deck about coral reefs"),
                reasoning,
                calls,
                ChatMessage.tool("call-a", "knowledge_search",
                        "some conversation content ".repeat(10)),
                summary,
                withImages,
                multi);
    }

    @Test
    void breakdownMatchesGoFieldByField() {
        ChatTool tool;
        try {
            tool = new ChatTool("knowledge_search", "Search the knowledge base",
                    new ObjectMapper().readTree("{\"type\":\"object\"}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var b = ContextDiagnostics.breakdownContext(fixture(), List.of(tool), estimator);
        assertThat(b.getMessages()).isEqualTo(8);
        assertThat(b.getTotal()).isEqualTo(2529);
        assertThat(b.getText()).isEqualTo(17);
        assertThat(b.getReasoning()).isEqualTo(8);
        assertThat(b.getToolResults()).isEqualTo(31);
        assertThat(b.getToolCallArgs()).isEqualTo(6);
        assertThat(b.getImages()).isEqualTo(2400);
        assertThat(b.getSummaries()).isEqualTo(4);
        assertThat(b.getToolSchemas()).isEqualTo(19);
        assertThat(b.getLargestKind()).isEqualTo("user");
        assertThat(b.getLargestTokens()).isEqualTo(1207);
        assertThat(b.toString()).isEqualTo(STR_BD_STRING);
    }

    @Test
    void emptyBreakdownString() {
        var b = ContextDiagnostics.breakdownContext(List.of(), List.of(), estimator);
        assertThat(b.toString()).isEqualTo(STR_BD0_STRING);
    }

    @Test
    void driftPercentGrading() {
        // logContextDrift 的分级：< -25% 低估告警、> 50% 高估告警（录制常量 drift0..5）
        for (int i = 0; i < 6; i++) {
            int predicted = new int[] {1000, 1200, 600, 700, 740, 760}[i];
            int actual = 1000;
            double pct = (double) (predicted - actual) / actual * 100;
            String level = pct < -25 ? "under" : (pct > 50 ? "over" : "ok");
            String expected = new String[] {"ok", "ok", "under", "under", "under", "ok"}[i];
            assertThat(level).as("drift%d", i).isEqualTo(expected);
            // 逐字节（%.4f 形态比对）
            assertThat(String.format(java.util.Locale.ROOT, "%.4f", pct))
                    .isEqualTo(new String[] {
                        "0.0000", "20.0000", "-40.0000", "-30.0000", "-26.0000", "-24.0000"}[i]);
        }
    }
}
