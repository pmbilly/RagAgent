package com.ragagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.BaseTool;
import com.ragagent.agent.tools.ToolRequest;
import com.ragagent.agent.tools.ToolRegistry;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventJson;
import com.ragagent.event.EventType;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.ToolCall;

/**
 * 4.6b 实录回放的替身与掩码工具（对照 Go 探针复用的 mockChat / countingTool /
 * fakeSteerSink 与 mask46b）。期望值全部是 {@link GoRecording46B} 的 Go 实录常量。
 *
 * <p>掩码约定（探针在录制侧做了同款替换，两侧掩码后逐字节可比）：事件 id 的 uuid
 * 前缀 → {@code xxxxxxxx-}；duration/duration_ms/total_duration_ms → 0；
 * {@code "timestamp":"..."} → {@code "TS"}；{@code <current_time>} 日期段 → DATE。</p>
 */
final class Engine46bStubSupport {

    private Engine46bStubSupport() {
    }

    private static final Pattern DUR = Pattern.compile(",\"(duration_ms|duration|total_duration_ms)\":\\d+");
    private static final Pattern TS = Pattern.compile("\"timestamp\":\"[^\"]*\"");
    private static final Pattern DATE = Pattern.compile("<current_time>[^<]*</current_time>");
    private static final Pattern DATE_ESC = Pattern.compile(
            "(\\\\u003ccurrent_time\\\\u003e)[0-9-]{10}(\\\\u003c/current_time\\\\u003e)");
    private static final Pattern EVENT_ID = Pattern.compile("^[0-9a-f]{8}-");

    static String mask(String s) {
        s = DUR.matcher(s).replaceAll("");
        s = TS.matcher(s).replaceAll("\"timestamp\":\"TS\"");
        s = DATE.matcher(s).replaceAll("<current_time>DATE</current_time>");
        s = DATE_ESC.matcher(s).replaceAll("$1DATE$2");
        return s;
    }

    static String maskEventId(String id) {
        return EVENT_ID.matcher(id).replaceFirst("xxxxxxxx-");
    }

    static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------
    // 事件收集器（对照 rec46bSink）
    // ------------------------------------------------------------------

    /** 订阅引擎会发的全部事件类型；按发射序记 (type, id, session, payload JSON)。 */
    static final class EventRecorder {
        private record Rec(String type, String id, String sessionId, String dataJson) {
        }

        private final List<Rec> events = new ArrayList<>();

        EventRecorder attach(EventBus bus) {
            String[] types = {
                    EventType.EVENT_AGENT_THOUGHT,
                    EventType.EVENT_AGENT_FINAL_ANSWER,
                    EventType.EVENT_AGENT_TOOL_CALL,
                    EventType.EVENT_AGENT_TOOL_RESULT,
                    EventType.EVENT_AGENT_TOOL,
                    EventType.EVENT_AGENT_COMPLETE,
                    EventType.EVENT_CONTEXT_COMPACTED,
                    EventType.EVENT_USER_MESSAGE_INJECTED,
                    EventType.EVENT_ERROR,
            };
            for (String type : types) {
                bus.on(type, evt -> events.add(new Rec(evt.getType(), maskEventId(evt.getId()),
                        evt.getSessionId(), mask(EventJson.write(evt.getData())))));
            }
            return this;
        }

        /** 与 Go 探针同构的事件序列 JSON（掩码后逐字节可比）。 */
        String toJson() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < events.size(); i++) {
                Rec e = events.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"type\":").append(jsonStr(e.type()))
                        .append(",\"id\":").append(jsonStr(e.id()))
                        .append(",\"session_id\":").append(jsonStr(e.sessionId()))
                        .append(",\"data\":").append(e.dataJson()).append('}');
            }
            return sb.append(']').toString();
        }
    }

    // ------------------------------------------------------------------
    // mockChat 的 Java 对应
    // ------------------------------------------------------------------

    /**
     * 脚本化 LLM 替身（对照 mockChat）。收到的消息在调用时刻即序列化成 JSON 存储
     * （对照 Go 切片按值拷贝的语义——引擎后续的就地改动不回写记录）。
     */
    static final class StubChat implements LlmChatClient {

        final List<List<StreamResponse>> responses;
        final List<String> callJson = new ArrayList<>();
        final List<ChatOptions> opts = new ArrayList<>();
        int callCount;
        /** 非流式响应（对照 summarizerChat.Chat 的覆写；null = 报 not implemented）。 */
        private ChatResponse nonStreamResponse;
        int nonStreamCalls;

        StubChat(List<List<StreamResponse>> responses) {
            this.responses = responses;
        }

        void setNonStreamResponse(ChatResponse resp) {
            this.nonStreamResponse = resp;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            nonStreamCalls++;
            if (nonStreamResponse == null) {
                throw new AgentEngineException("not implemented");
            }
            return nonStreamResponse;
        }

        @Override
        public java.util.concurrent.BlockingQueue<StreamResponse> chatStream(
                List<ChatMessage> messages, ChatOptions options) {
            if (callCount >= responses.size()) {
                throw new AgentEngineException("unexpected ChatStream call #" + callCount
                        + " (only " + responses.size() + " responses prepared)");
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < messages.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(EventJson.write(messages.get(i)));
            }
            sb.append(']');
            callJson.add(mask(sb.toString()));
            opts.add(options);
            callCount++;
            LinkedBlockingQueue<StreamResponse> queue = new LinkedBlockingQueue<>();
            queue.addAll(responses.get(callCount - 1));
            return queue;
        }

        @Override
        public String getModelName() {
            return "mock-model";
        }

        @Override
        public String getModelId() {
            return "mock-id";
        }
    }

    /**
     * mockChat 收到的消息与选项（对照 rec46bChatShape；opts 键按 Go map 字母序）。
     */
    static String chatShape(StubChat chat) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < chat.callCount; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"messages\":").append(i < chat.callJson.size()
                    ? chat.callJson.get(i) : "[]").append(",\"opts\":{");
            ChatOptions o = i < chat.opts.size() ? chat.opts.get(i) : null;
            sb.append("\"max_completion_tokens\":").append(o == null ? 0 : o.getMaxCompletionTokens());
            sb.append(",\"max_tokens\":").append(o == null ? 0 : o.getMaxTokens());
            sb.append(",\"parallel_tool_calls\":")
                    .append(o == null || o.getParallelToolCalls() == null
                            ? "null" : o.getParallelToolCalls());
            sb.append(",\"prompt_cache_key\":")
                    .append(jsonStr(o == null || o.getPromptCacheKey() == null ? ""
                            : o.getPromptCacheKey()));
            sb.append(",\"temperature\":").append(o == null ? 0
                    : goDouble(o.getTemperature()));
            sb.append(",\"thinking\":").append(o == null || o.getThinking() == null
                    ? "null" : o.getThinking());
            sb.append(",\"tool_choice\":").append(jsonStr(o == null || o.getToolChoice() == null
                    ? "" : o.getToolChoice()));
            sb.append(",\"tool_names\":[");
            if (o != null && o.getTools() != null) {
                for (int j = 0; j < o.getTools().size(); j++) {
                    if (j > 0) {
                        sb.append(',');
                    }
                    sb.append(jsonStr(o.getTools().get(j).getFunction().getName()));
                }
            }
            sb.append("]}}");
        }
        return mask(sb.append(']').toString());
    }

    /** Go float64 的 json 形态（测试温度值用 GoDoubleSerializer 语义）。 */
    private static String goDouble(double v) {
        return com.ragagent.common.web.GoDoubleSerializer.format(v);
    }

    // ------------------------------------------------------------------
    // fakeSteerSink 的 Java 对应
    // ------------------------------------------------------------------

    /** 对照 Go fakeSteerSink：队列由测试喂；持久化调用被记录。 */
    static final class FakeSteerSink implements SteerSink {
        final List<Map<String, Object>> queued = new ArrayList<>();
        final List<String> persisted = new ArrayList<>();
        final List<String> userIDs = new ArrayList<>();
        final List<Object> mentions = new ArrayList<>();
        boolean failPersist;
        final Map<String, Boolean> consumed = new java.util.HashMap<>();

        FakeSteerSink(Map<String, Object>... entries) {
            queued.addAll(List.of(entries));
        }

        @Override
        public List<Map<String, Object>> pollSteer(String sessionId, String messageId, int lastOffset) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> evt : queued) {
                String id = evt.get("id") instanceof String s ? s : "";
                if (consumed.containsKey(id)) {
                    continue;
                }
                out.add(evt);
            }
            return out;
        }

        @Override
        public String persistSteerMessage(String sessionId, String messageId, String steerId,
                String content, Object mentionedItems, String channel) {
            if (failPersist) {
                return "";
            }
            persisted.add(content);
            String id = "user-row-for-" + content;
            userIDs.add(id);
            mentions.add(mentionedItems);
            if (steerId != null && !steerId.isEmpty()) {
                consumed.put(steerId, Boolean.TRUE);
            }
            return id;
        }
    }

    static Map<String, Object> steerEntry(String id, String content) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("content", content);
        return m;
    }

    // ------------------------------------------------------------------
    // 脚本化 chunk 构造（对照探针 msgChunk / toolCallChunk / assembledToolCallChunk）
    // ------------------------------------------------------------------

    static StreamResponse msgChunk(String content, boolean done, String finish) {
        StreamResponse r = new StreamResponse();
        r.setResponseType(ResponseType.ANSWER);
        r.setContent(content);
        r.setDone(done);
        r.setFinishReason(finish);
        return r;
    }

    /** UI 分片（pending/progress 事件的 Data 载体；不含真正执行的 ToolCalls）。 */
    static StreamResponse toolCallChunk(String id, String name, String argsJSON) {
        StreamResponse r = new StreamResponse();
        r.setResponseType(ResponseType.TOOL_CALL);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("tool_call_id", id);
        data.put("tool_name", name);
        if (!argsJSON.isEmpty()) {
            data.put("arguments", argsMap(argsJSON));
        }
        r.setData(data);
        return r;
    }

    /** provider 末块给整装调用（真正执行的那份；不带 Data）。 */
    static StreamResponse assembledToolCallChunk(String id, String name, String argsJSON) {
        StreamResponse r = new StreamResponse();
        r.setResponseType(ResponseType.ANSWER);
        ToolCall call = new ToolCall();
        call.setId(id);
        call.setType("function");
        call.setFunction(new com.ragagent.llm.domain.FunctionCall(name, argsJSON));
        r.setToolCalls(new ArrayList<>(List.of(call)));
        return r;
    }

    /** 错误块（对照 stream error 分片）。 */
    static StreamResponse errorChunk(String content, String finish) {
        StreamResponse r = new StreamResponse();
        r.setResponseType(ResponseType.ERROR);
        r.setContent(content);
        r.setDone(true);
        r.setFinishReason(finish);
        return r;
    }

    /** 思考通道分片（对照 reasoning_content 通道）。 */
    static StreamResponse thinkingChunk(String content, boolean done) {
        StreamResponse r = new StreamResponse();
        r.setResponseType(ResponseType.THINKING);
        r.setContent(content);
        r.setDone(done);
        return r;
    }

    /** JsonNode/JSON → Map（工具 args 解析用；LinkedHashMap 保持插入序）。 */
    static Map<String, Object> argsMap(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.databind.ObjectMapper().getTypeFactory()
                            .constructMapType(java.util.LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // 引擎构造（对照 newRec46bEngine：Custom 模板 + registry）与计数工具
    // ------------------------------------------------------------------

    static AgentEngine newEngine(StubChat chat) {
        AgentConfig cfg = new AgentConfig();
        cfg.setMaxIterations(10);
        cfg.setTemperature(0.7);
        // 默认模板含 {{current_week}} 等墙钟占位符（4.2 已钉），这里用固定模板保持逐字节确定。
        return new AgentEngine(cfg, chat, new ToolRegistry(), null, null, null, "test-session",
                "CUSTOM_AGENT_TEMPLATE");
    }

    static AgentConfig configWith(StubChat chat, java.util.function.Consumer<AgentConfig> fn) {
        AgentConfig cfg = new AgentConfig();
        cfg.setMaxIterations(10);
        cfg.setTemperature(0.7);
        fn.accept(cfg);
        return cfg;
    }

    static AgentEngine newEngine(AgentConfig cfg, StubChat chat) {
        return new AgentEngine(cfg, chat, new ToolRegistry(), null, null, null, "test-session",
                "CUSTOM_AGENT_TEMPLATE");
    }

    /** 计数工具（对照 countingTool）。 */
    static final class StubTool extends BaseTool {
        int calls;

        StubTool(String name) {
            super(name, "test", "{\"type\":\"object\"}");
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            calls++;
            ToolResult r = new ToolResult();
            r.setSuccess(true);
            r.setOutput("executed");
            return r;
        }
    }

    static List<ChatMessage> emptyMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "You are a test agent."));
        messages.add(new ChatMessage("user", "test query"));
        return messages;
    }

    static JsonNode rec(String constant) {
        return GoRecording46B.rec(constant);
    }
}
