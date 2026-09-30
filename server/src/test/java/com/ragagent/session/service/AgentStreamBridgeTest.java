package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ragagent.common.llm.ResponseType;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.common.session.PipelineUsedMemoryView;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.AgentCompleteData;
import com.ragagent.event.payload.AgentFinalAnswerData;
import com.ragagent.event.payload.AgentReferencesData;
import com.ragagent.event.payload.AgentThoughtData;
import com.ragagent.event.payload.AgentToolCallData;
import com.ragagent.event.payload.AgentToolResultData;
import com.ragagent.event.payload.MemoryRecalledData;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.session.domain.Message;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * {@code AgentStreamBridge} 的 SSE 载荷契约。
 *
 * <p>补测背景：该类此前**零直接测试**（只在 {@code KnowledgeQaController} 的 agent 流里构造），
 * 而它是 17 种事件订阅 + final_answer 分片重组的最高危面。本测试只钉"进 {@link StreamManager}
 * 的事件形状"（与前端 SSE 契约同源）与桥内累积状态的对外可见部分（
 * {@link AgentStreamBridge#composedFinalAnswer()}、{@code assistantMessage} 的落库字段），
 * 不碰持久化（那在调用方）。</p>
 *
 * <p>已知不覆盖：handlers 里 append 失败的吞掉语义（{@link EventBus} 本就把 handler 异常隔离成
 * panic，从总线上无法区分），以及审批 / OAuth / 会话标题 / 上下文压缩等旁路事件的载荷。</p>
 */
class AgentStreamBridgeTest {

    private static final String SESSION = "s-1";
    private static final String MESSAGE = "m-1";

    private StreamManager streamManager;
    private EventBus bus;
    private Message assistantMessage;
    private AgentStreamBridge bridge;

    @BeforeEach
    void setUp() {
        streamManager = mock(StreamManager.class);
        bus = new EventBus();
        assistantMessage = new Message();
        assistantMessage.setId(MESSAGE);
        bridge = new AgentStreamBridge(SESSION, MESSAGE, "req-1", 7L, OffsetDateTime.now(),
                assistantMessage, streamManager, bus);
        bridge.subscribe();
    }

    /** Bridge 里没有测试探针，用带 id 的事件走总线（订阅序即回调序）。 */
    private void emit(String eventType, String eventId, Object data) {
        Event event = Event.newEvent(eventType, data);
        event.setId(eventId);
        bus.emitAndWait(event);
    }

    private List<StreamEvent> appendedOfType(ResponseType type) {
        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(streamManager, atLeastOnce()).appendEvent(eq(SESSION), eq(MESSAGE), captor.capture());
        List<StreamEvent> out = new ArrayList<>();
        for (StreamEvent event : captor.getAllValues()) {
            if (event.getType() == type) {
                out.add(event);
            }
        }
        return out;
    }

    private static AgentToolCallData toolCall(String toolCallId, String toolName) {
        AgentToolCallData data = new AgentToolCallData();
        data.setToolCallId(toolCallId);
        data.setToolName(toolName);
        data.setArguments(Map.of("query", "x"));
        return data;
    }

    private static AgentFinalAnswerData answer(String content, boolean done) {
        AgentFinalAnswerData data = new AgentFinalAnswerData();
        data.setContent(content);
        data.setDone(done);
        return data;
    }

    private static AgentCompleteData complete(String finalAnswer, Object usage) {
        return new AgentCompleteData(SESSION, 3, finalAnswer, null, null, usage, 1234L, MESSAGE,
                "req-1", null);
    }

    @Test
    void subscribeCoversEveryPublishedEventType() {
        for (String type : List.of(EventType.EVENT_AGENT_THOUGHT, EventType.EVENT_AGENT_TOOL_CALL,
                EventType.EVENT_AGENT_TOOL_RESULT, EventType.EVENT_AGENT_REFERENCES,
                EventType.EVENT_MEMORY_RECALLED, EventType.EVENT_AGENT_FINAL_ANSWER,
                EventType.EVENT_AGENT_COMPLETE, EventType.EVENT_ERROR,
                EventType.EVENT_TOOL_APPROVAL_REQUIRED, EventType.EVENT_MCP_OAUTH_REQUIRED)) {
            assertThat(bus.hasHandlers(type)).as(type).isTrue();
        }
        // 对外宣称的事件表与实际订阅一致（少订一条就是前端收不到的静默缺口）
        for (String type : AgentStreamBridge.subscribedEventTypes()) {
            assertThat(bus.hasHandlers(type)).as(type).isTrue();
        }
    }

    @Test
    void thoughtStreamsThinkingAndAddsDurationMetadataOnDone() {
        emit(EventType.EVENT_AGENT_THOUGHT, "e-thought", new AgentThoughtData("让我查一下", 1, false));
        StreamEvent first = appendedOfType(ResponseType.THINKING).get(0);
        assertThat(first.getContent()).isEqualTo("让我查一下");
        assertThat(first.isDone()).isFalse();
        assertThat(first.getData()).containsOnlyKeys("event_id");

        emit(EventType.EVENT_AGENT_THOUGHT, "e-thought", new AgentThoughtData("", 1, true));
        StreamEvent done = appendedOfType(ResponseType.THINKING).get(1);
        assertThat(done.isDone()).isTrue();
        assertThat(done.getData()).containsKeys("event_id", "duration_ms", "completed_at");
    }

    @Test
    void toolCallSupersedesStreamedPreambleAndLaterAnswerComposes() {
        emit(EventType.EVENT_AGENT_FINAL_ANSWER, "a-1", answer("让我先查一下", false));
        assertThat(bridge.composedFinalAnswer()).isEqualTo("让我先查一下");

        emit(EventType.EVENT_AGENT_TOOL_CALL, "e-call", toolCall("tc-1", "kb_search"));

        StreamEvent call = appendedOfType(ResponseType.TOOL_CALL).get(0);
        assertThat(call.getContent()).isEqualTo("Calling tool: kb_search");
        assertThat(call.isDone()).isFalse();
        assertThat(call.getData()).containsEntry("tool_call_id", "tc-1")
                .containsEntry("tool_name", "kb_search");
        // 工具调用前的流出文本是非终局轮的前导，不进持久化答案
        assertThat(bridge.composedFinalAnswer()).isEmpty();

        emit(EventType.EVENT_AGENT_FINAL_ANSWER, "a-2", answer("最终答案", true));
        assertThat(bridge.composedFinalAnswer()).isEqualTo("最终答案");
        assertThat(appendedOfType(ResponseType.ANSWER)).hasSize(2);
    }

    @Test
    void toolResultStreamsResultTypeOnSuccessAndErrorTypeOnFailure() {
        emit(EventType.EVENT_AGENT_TOOL_CALL, "e-call", toolCall("tc-9", "web_search"));
        AgentToolResultData ok = new AgentToolResultData("tc-9", "web_search", "out", "", true, 0, 1,
                Map.of("answer", "42"));
        emit(EventType.EVENT_AGENT_TOOL_RESULT, "e-ok", ok);
        StreamEvent okEvent = appendedOfType(ResponseType.TOOL_RESULT).get(0);
        assertThat(okEvent.getData()).containsEntry("success", true).containsEntry("tool_name", "web_search")
                .containsKey("duration_ms");

        AgentToolResultData failed = new AgentToolResultData("tc-10", "web_search", "", "boom", false, 0,
                2, Map.of());
        emit(EventType.EVENT_AGENT_TOOL_RESULT, "e-fail", failed);
        StreamEvent errEvent = appendedOfType(ResponseType.ERROR).get(0);
        assertThat(errEvent.getData()).containsEntry("success", false).containsEntry("error", "boom");
    }

    @Test
    void referencesAccumulateAndPersistOnAssistantMessage() {
        SearchResult hit = new SearchResult();
        hit.setId("k-1");
        emit(EventType.EVENT_AGENT_REFERENCES, "e-ref", new AgentReferencesData(List.of(hit), 1));

        assertThat(assistantMessage.getKnowledgeReferences()).hasSize(1);
        StreamEvent event = appendedOfType(ResponseType.REFERENCES).get(0);
        assertThat(event.getData()).containsKey("references");
        assertThat((List<?>) event.getData().get("references")).hasSize(1);
    }

    @Test
    void memoryRecalledPersistsEntitiesAndStreamsTheSameViews() {
        List<PipelineUsedMemoryView> views =
                List.of(new PipelineUsedMemoryView("m-1", "fact", "用户偏好中文回答"));
        emit(EventType.EVENT_MEMORY_RECALLED, "e-mem", new MemoryRecalledData(views));

        assertThat(assistantMessage.getUsedMemories()).hasSize(1);
        assertThat(assistantMessage.getUsedMemories().get(0).getContent()).isEqualTo("用户偏好中文回答");
        StreamEvent event = appendedOfType(ResponseType.MEMORY_RECALLED).get(0);
        assertThat(event.getData()).containsEntry("memories", views);
    }

    @Test
    void completeStreamsFallbackAnswerPairAndCompletionWithUsage() {
        TokenUsage usage = new TokenUsage();
        usage.setTotalTokens(42);
        emit(EventType.EVENT_AGENT_COMPLETE, "e-done", complete("最终答案", usage));

        // 本轮没有 answer 事件（模型走 thought 或非增量工具调用）→ 补发 fallback 对
        List<StreamEvent> answers = appendedOfType(ResponseType.ANSWER);
        assertThat(answers).hasSize(2);
        assertThat(answers.get(0).getContent()).isEqualTo("最终答案");
        assertThat(answers.get(0).isDone()).isFalse();
        assertThat(answers.get(0).getData()).containsEntry("is_fallback", true);
        assertThat(answers.get(1).isDone()).isTrue();

        StreamEvent event = appendedOfType(ResponseType.COMPLETE).get(0);
        assertThat(event.isDone()).isTrue();
        assertThat(event.getUsage()).isSameAs(usage);
        assertThat(event.getData()).containsEntry("total_steps", 3)
                .containsEntry("final_content", "最终答案");
        assertThat(assistantMessage.isCompleted()).isTrue();
        assertThat(assistantMessage.getContent()).isEqualTo("最终答案");
    }

    @Test
    void completeSkipsFallbackPairWhenAnAnswerWasStreamed() {
        emit(EventType.EVENT_AGENT_FINAL_ANSWER, "a-1", answer("真答案", true));
        emit(EventType.EVENT_AGENT_COMPLETE, "e-done", complete("真答案", null));

        assertThat(appendedOfType(ResponseType.ANSWER)).hasSize(1);
        assertThat(appendedOfType(ResponseType.COMPLETE)).hasSize(1);
        assertThat(assistantMessage.getContent()).isEqualTo("真答案");
    }

    @Test
    void unrelatedPayloadOnSubscribedTypeIsIgnored() {
        emit(EventType.EVENT_AGENT_THOUGHT, "e-x", "不是载荷对象");
        verify(streamManager, never()).appendEvent(eq(SESSION), eq(MESSAGE), any(StreamEvent.class));
    }
}
