package com.ragagent.sandbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ragagent.event.AgentCompleteData;
import com.ragagent.event.AgentFinalAnswerData;
import com.ragagent.event.AgentThoughtData;
import com.ragagent.event.AgentToolCallData;
import com.ragagent.event.AgentToolResultData;
import com.ragagent.event.CommandOutputData;
import com.ragagent.event.Event;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.session.domain.Message;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.stream.MemoryStreamManager;
import com.ragagent.stream.StreamBatch;
import com.ragagent.stream.StreamEvent;

/**
 * install transcript 纯单测（对照 Go tenant_skill_transcript 的纯函数面 + 事件回调
 * 序列）。流面用 {@link MemoryStreamManager}（内存替身），消息面用 Mockito mock 的
 * 裸仓储（Go 的 interfaces.MessageRepository nil 语义：null 时静默跳过）。
 */
class SkillInstallTranscriptTest {

    // ── 渐近进度（Go asymptoticInstallPercent L554-563 的表驱动面） ──────

    @Test
    void asymptoticInstallPercentTable() {
        assertEquals(35, SkillInstallTranscript.asymptoticInstallPercent(0));
        assertEquals(35, SkillInstallTranscript.asymptoticInstallPercent(-3));
        assertEquals(39, SkillInstallTranscript.asymptoticInstallPercent(1),
                "第一条命令推进约 4 个点");
        assertEquals(60, SkillInstallTranscript.asymptoticInstallPercent(10));
        assertEquals(71, SkillInstallTranscript.asymptoticInstallPercent(20));
        assertEquals(75, SkillInstallTranscript.asymptoticInstallPercent(30),
                "跑满 max_iterations=30 仍低于 79 天花板");
        assertEquals(79, SkillInstallTranscript.asymptoticInstallPercent(1000));
    }

    @Test
    void asymptoticProgressIsMonotonicAndNeverPassesAnchor() {
        int prev = 0;
        for (int k = 0; k <= 200; k++) {
            int p = SkillInstallTranscript.asymptoticInstallPercent(k);
            assertTrue(p >= prev, "k=" + k + " 应单调");
            assertTrue(p <= 79, "k=" + k + " 不越过 agent_done 锚点 80 之下的天花板");
            prev = p;
        }
    }

    // ── installToolCallSummary（Go L516-530） ────────────────────────────

    @Test
    void toolCallSummaryPrefersEngineHint() {
        var data = new AgentToolCallData("tc-1", "shell_exec", Map.of("command", "uv venv .venv"),
                1, "shell_exec: uv venv .venv");
        assertEquals("shell_exec: uv venv .venv",
                SkillInstallTranscript.installToolCallSummary(data));
    }

    @Test
    void toolCallSummaryFallsBackToCommand() {
        var data = new AgentToolCallData("tc-1", "shell_exec", Map.of("command", " pip install x "),
                1, "");
        assertEquals("shell_exec: pip install x",
                SkillInstallTranscript.installToolCallSummary(data));
    }

    @Test
    void toolCallSummaryWithoutCommandIsToolName() {
        var data = new AgentToolCallData("tc-1", "web_search", Map.of(), 1, "");
        assertEquals("web_search", SkillInstallTranscript.installToolCallSummary(data));
    }

    @Test
    void toolCallSummaryFlattensNewlinesAndCapsRunes() {
        var data = new AgentToolCallData("tc-1", "shell_exec",
                Map.of("command", "echo one\necho two"), 1, "");
        assertEquals("shell_exec: echo one echo two",
                SkillInstallTranscript.installToolCallSummary(data), "换行压平");

        var longCmd = new AgentToolCallData("tc-1", "shell_exec",
                Map.of("command", "a".repeat(100)), 1, "");
        String out = SkillInstallTranscript.installToolCallSummary(longCmd);
        assertEquals(81, out.codePoints().count(), "80 rune 截断 + 省略号");
        assertTrue(out.endsWith("…"));
    }

    // ── 事件回放面（MemoryStreamManager 直读） ───────────────────────────

    @Test
    void createWritesPromptEventAndTwoRows() {
        MemoryStreamManager streams = new MemoryStreamManager();
        MessageRepository messages = mock(MessageRepository.class);
        SkillInstallTranscript tr = new SkillInstallTranscript(null, streams, messages,
                "sess-1", "msg-a1", null);

        tr.create("install this skill");

        ArgumentCaptor<Message> rows = ArgumentCaptor.forClass(Message.class);
        verify(messages, times(2)).create(rows.capture());
        List<Message> all = rows.getAllValues();
        assertEquals("user", all.get(0).getRole());
        assertEquals("install this skill", all.get(0).getContent());
        assertTrue(all.get(0).isCompleted());
        assertEquals("assistant", all.get(1).getRole());
        assertEquals("msg-a1", all.get(1).getId(),
                "assistant 行以定位符 ID 提交（落库时被 create 覆写，与 Go BeforeCreate 同）");

        StreamBatch batch = streams.getEvents("sess-1", "msg-a1", 0);
        assertEquals(1, batch.events().size());
        StreamEvent evt = batch.events().get(0);
        assertEquals(ResponseType.INSTALL_PROMPT, evt.getType());
        assertEquals("install this skill", evt.getContent());
        assertTrue(evt.isDone());
    }

    @Test
    void thoughtAndAnswerChunksCarrySpanMeta() {
        MemoryStreamManager streams = new MemoryStreamManager();
        SkillInstallTranscript tr = new SkillInstallTranscript(null, streams, null,
                "sess-1", "msg-a1", null);

        tr.onThought(new Event("e1", "thought", "sess-1",
                new AgentThoughtData("thi", 1, false), null, ""));
        tr.onAnswer(new Event("e2", "final_answer", "sess-1",
                new AgentFinalAnswerData("ans", false, false), null, ""));
        tr.onAnswer(new Event("e2", "final_answer", "sess-1",
                new AgentFinalAnswerData("wer", true, false), null, ""));

        StreamBatch batch = streams.getEvents("sess-1", "msg-a1", 0);
        assertEquals(3, batch.events().size());
        assertEquals(ResponseType.THINKING, batch.events().get(0).getType());
        assertEquals(Map.of("event_id", "e1"), batch.events().get(0).getData(),
                "未闭合 span 只有 event_id");
        assertEquals(ResponseType.ANSWER, batch.events().get(1).getType());
        assertEquals(ResponseType.ANSWER, batch.events().get(2).getType());
        var doneMeta = batch.events().get(2).getData();
        assertEquals("e2", doneMeta.get("event_id"));
        assertTrue(doneMeta.containsKey("duration_ms"));
        assertTrue(doneMeta.containsKey("completed_at"), "闭合 span 带 completed_at");
    }

    @Test
    void finishWritesErrorAndCompleteOnceAndPersistsVerdict() {
        MemoryStreamManager streams = new MemoryStreamManager();
        MessageRepository messages = mock(MessageRepository.class);
        SkillInstallTranscript tr = new SkillInstallTranscript(null, streams, messages,
                "sess-1", "msg-a1", null);
        tr.onAnswer(new Event("e2", "final_answer", "sess-1",
                new AgentFinalAnswerData("done work", true, false), null, ""));

        tr.finish("verification failed: pip not found");
        tr.finish("verification failed: pip not found"); // 二次 Finish 是 no-op

        List<StreamEvent> events = streams.getEvents("sess-1", "msg-a1", 0).events();
        assertEquals(3, events.size(), "answer + error + complete；第二次 finish 不追加");
        assertEquals(ResponseType.ERROR, events.get(1).getType());
        assertEquals("verification failed: pip not found", events.get(1).getContent());
        assertTrue(events.get(1).isDone());
        assertEquals(Map.of("stage", "install", "error", "verification failed: pip not found"),
                events.get(1).getData());
        assertEquals(ResponseType.COMPLETE, events.get(2).getType());
        assertEquals(Map.of("total_steps", 0, "total_duration_ms", 0L),
                events.get(2).getData());

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).update(saved.capture());
        assertEquals("done work\n\nverification failed: pip not found", saved.getValue().getContent(),
                "prose + \\n\\n + 裁决（failure 段不可被撤回）");
        assertTrue(saved.getValue().isCompleted());
    }

    @Test
    void toolCallRetractsPreambleAndDedupesByCallId() {
        MemoryStreamManager streams = new MemoryStreamManager();
        MessageRepository messages = mock(MessageRepository.class);
        List<Integer> steps = new ArrayList<>();
        SkillInstallTranscript tr = new SkillInstallTranscript(null, streams, messages,
                "sess-1", "msg-a1", (s, cmd) -> steps.add(s));
        tr.onAnswer(new Event("e1", "final_answer", "sess-1",
                new AgentFinalAnswerData("I will install", false, false), null, ""));
        tr.onToolCall(new Event("e2", "tool_call", "sess-1",
                new AgentToolCallData("tc-1", "shell_exec", Map.of("command", "ls"), 1, ""),
                null, ""));
        tr.onToolCall(new Event("e2", "tool_call", "sess-1",
                new AgentToolCallData("tc-1", "shell_exec", Map.of("command", "ls"), 1, ""),
                null, ""));
        tr.onAnswer(new Event("e3", "final_answer", "sess-1",
                new AgentFinalAnswerData("installed.", true, false), null, ""));

        assertEquals(List.of(1), steps, "同一 tool_call_id 去重，只计一次活动");
        List<StreamEvent> events = streams.getEvents("sess-1", "msg-a1", 0).events();
        assertEquals(3, events.size(), "两条 answer + 一条 tool_call（重复调用不追加）");
        assertTrue(events.stream()
                .anyMatch(e -> "Calling tool: shell_exec".equals(e.getContent())));

        tr.finish(null);
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).update(saved.capture());
        assertEquals("installed.", saved.getValue().getContent(),
                "preamble 被工具调用撤回，只有结束轮的散文留存");
    }

    @Test
    void onCompleteAccumulatesTotalsAcrossRoundsAndTakesSummary() {
        MemoryStreamManager streams = new MemoryStreamManager();
        MessageRepository messages = mock(MessageRepository.class);
        SkillInstallTranscript tr = new SkillInstallTranscript(null, streams, messages,
                "sess-1", "msg-a1", null);
        tr.onComplete(new Event("e1", "agent.complete", "sess-1",
                new AgentCompleteData("sess-1", 2, "", null, List.of(), null, 300L,
                        "msg-a1", "", null), null, ""));
        tr.onComplete(new Event("e2", "agent.complete", "sess-1",
                new AgentCompleteData("sess-1", 5, "summary text", null, List.of(), null, 400L,
                        "msg-a1", "", null), null, ""));
        tr.finish(null);

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).update(saved.capture());
        assertEquals("summary text", saved.getValue().getContent(),
                "引擎未流答案分片时，摘要取自完成载荷（修复轮=两个引擎轮次）");
        assertTrue(saved.getValue().isCompleted());
        assertEquals(400L, saved.getValue().getAgentDurationMs());
        assertEquals(Map.of("total_steps", 7, "total_duration_ms", 700L),
                streams.getEvents("sess-1", "msg-a1", 0).events().get(0).getData(),
                "跨轮累加（complete 帧由 finish 发出）");
    }

    @Test
    void muteActivityProgressStopsCounting() {
        MemoryStreamManager streams = new MemoryStreamManager();
        List<Integer> steps = new ArrayList<>();
        SkillInstallTranscript tr = new SkillInstallTranscript(null, streams, null,
                "sess-1", "msg-a1", (s, cmd) -> steps.add(s));
        tr.onToolCall(new Event("e1", "tool_call", "sess-1",
                new AgentToolCallData("tc-1", "shell_exec", Map.of("command", "ls"), 1, ""),
                null, ""));
        tr.muteActivityProgress();
        tr.onToolCall(new Event("e2", "tool_call", "sess-1",
                new AgentToolCallData("tc-2", "shell_exec", Map.of("command", "ls"), 2, ""),
                null, ""));
        assertEquals(List.of(1), steps, "静音后修复轮不再发布渐近进度");
    }

    @Test
    void failedToolResultSurfacesAsError() {
        MemoryStreamManager streams = new MemoryStreamManager();
        SkillInstallTranscript tr = new SkillInstallTranscript(null, streams, null,
                "sess-1", "msg-a1", null);
        tr.onToolResult(new Event("e1", "tool_result", "sess-1",
                new AgentToolResultData("tc-1", "shell_exec", "", "exit 1", false, 0, 1,
                        Map.of("exit_code", 1)), null, ""));

        StreamEvent evt = streams.getEvents("sess-1", "msg-a1", 0).events().get(0);
        assertEquals(ResponseType.ERROR, evt.getType());
        assertEquals("exit 1", evt.getContent(), "content 为空时回落 error 文本");
        assertEquals("shell_exec", evt.getData().get("tool_name"));
        assertEquals(false, evt.getData().get("success"));
    }

    @Test
    void onInstallOutputSuppressedAfterClose() {
        MemoryStreamManager streams = new MemoryStreamManager();
        SkillInstallTranscript tr = new SkillInstallTranscript(null, streams, null,
                "sess-1", "msg-a1", null);
        tr.finish(null);
        tr.onInstallOutput(new Event("e1", "command_output", "sess-1",
                new CommandOutputData("tc-1", "ls", null, "out", true), null, ""));
        assertEquals(1, streams.getEvents("sess-1", "msg-a1", 0).events().size(),
                "closed 后 install_output 不再追加（流里只有 finish 的 complete 帧）");
    }
}
