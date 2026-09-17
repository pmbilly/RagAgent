package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.FunctionCall;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.ToolCall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 对照 Go internal/models/chat/anthropic_tools_test.go 的三条用例。
 *
 * <p>Go 直接用零值结构体 {@code &AnthropicChat{modelName: "claude-test"}}；Java 侧必须走
 * 构造器（要过 API key 校验），故用一个不带 baseURL 的配置——构造器只做 SSRF 校验与
 * key 校验，不发任何网络请求。</p>
 */
class AnthropicToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AnthropicChat model() {
        ChatConfig config = new ChatConfig();
        config.setModelName("claude-test");
        config.setApiKey("test-key");
        return new AnthropicChat(config);
    }

    /**
     * 对照 Go TestAnthropicToolsPreserveParallelHistoryAndSchema：
     * 工具 schema 原样保留（$defs / $ref / oneOf / additionalProperties），
     * 并行工具调用在历史里保 ID、保 JSON、保空白、保空结果，且两条 tool 合进同一条 user 消息。
     */
    @Test
    void toolsPreserveParallelHistoryAndSchema() throws Exception {
        String schemaJson = "{\"type\":\"object\",\"$defs\":{\"id\":{\"type\":\"string\"}},"
                + "\"properties\":{\"id\":{\"$ref\":\"#/$defs/id\"}},"
                + "\"oneOf\":[{\"required\":[\"id\"]}],\"additionalProperties\":false}";
        JsonNode schema = MAPPER.readTree(schemaJson);

        ChatOptions opts = new ChatOptions();
        opts.setTools(List.of(new ChatTool("lookup", "Detailed usage ".repeat(40), schema)));
        opts.setToolChoice("required");
        opts.setParallelToolCalls(false);

        ToolCall callA = new ToolCall();
        callA.setId("a");
        callA.setFunction(new FunctionCall("lookup", "{\"id\":\"i1\"}"));
        ToolCall callB = new ToolCall();
        callB.setId("b");
        callB.setFunction(new FunctionCall("lookup", "{\"id\":\"i2\"}"));

        ChatMessage assistant = new ChatMessage("assistant", "");
        assistant.setToolCalls(List.of(callA, callB));

        AnthropicRequest req = model().buildRequest(List.of(
                ChatMessage.user("find both"),
                assistant,
                ChatMessage.tool("a", "lookup", "  keep whitespace  "),
                ChatMessage.tool("b", "lookup", "")), opts);

        // 工具定义：schema 逐字节等价地保留
        assertEquals(1, req.getTools().size());
        assertEquals(opts.getTools().get(0).getFunction().getDescription(), req.getTools().get(0).getDescription());
        assertEquals(schema, req.getTools().get(0).getInputSchema());
        assertEquals("any", req.getToolChoice().getType());
        assertTrue(req.getToolChoice().getDisableParallelToolUse(), "parallel=false 在 Anthropic 侧是 disable=true");

        // 消息：user / assistant(tool_use) / user(tool_result x2)
        assertEquals(3, req.getMessages().size());
        List<AnthropicContentBlock> assistantBlocks = blocks(req.getMessages().get(1));
        assertEquals(2, assistantBlocks.size());
        assertEquals("tool_use", assistantBlocks.get(0).getType());
        assertEquals("a", assistantBlocks.get(0).getId());
        assertEquals("lookup", assistantBlocks.get(0).getName());
        assertEquals(MAPPER.readTree("{\"id\":\"i1\"}"), assistantBlocks.get(0).getInput());

        AnthropicMessage results = req.getMessages().get(2);
        assertEquals("user", results.getRole());
        List<AnthropicContentBlock> resultBlocks = blocks(results);
        assertEquals(2, resultBlocks.size(), "并行工具结果必须合并进同一条 user 消息");
        assertEquals("  keep whitespace  ", resultBlocks.get(0).getContent());
        assertEquals("b", resultBlocks.get(1).getToolUseId());

        // 线上 JSON：空 tool 结果照样带 content 字段（顺序 tool_use_id → content）
        String raw = MAPPER.writeValueAsString(req);
        assertTrue(raw.contains("\"tool_use_id\":\"b\",\"content\":\"\""),
                "空 tool 结果必须序列化为 \"content\":\"\"，实际：" + raw);
    }

    /** tool_choice 的四条分支（go toolOptions 的 switch）。 */
    @Test
    void toolChoiceMapping() throws Exception {
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\"}");
        List<ChatTool> tools = List.of(new ChatTool("t", "", schema));

        assertEquals("auto", choiceType(null, tools));
        assertEquals("auto", choiceType("auto", tools));
        assertEquals("any", choiceType("required", tools));
        assertEquals("none", choiceType("none", tools));

        ChatOptions named = new ChatOptions();
        named.setTools(tools);
        named.setToolChoice("lookup");
        AnthropicRequest req = model().buildRequest(List.of(ChatMessage.user("x")), named);
        assertEquals("tool", req.getToolChoice().getType());
        assertEquals("lookup", req.getToolChoice().getName());

        // choice=none 时不发 disable_parallel_tool_use（对照 Go 的 choice.Type != "none" 判断）
        ChatOptions noneOpts = new ChatOptions();
        noneOpts.setTools(tools);
        noneOpts.setToolChoice("none");
        noneOpts.setParallelToolCalls(false);
        AnthropicRequest noneReq = model().buildRequest(List.of(ChatMessage.user("x")), noneOpts);
        assertEquals(null, noneReq.getToolChoice().getDisableParallelToolUse());
    }

    private static String choiceType(String toolChoice, List<ChatTool> tools) {
        ChatOptions opts = new ChatOptions();
        opts.setTools(tools);
        opts.setToolChoice(toolChoice);
        return model().buildRequest(List.of(ChatMessage.user("x")), opts).getToolChoice().getType();
    }

    /**
     * 对照 Go TestAnthropicToolStreamParallelFragmentsAndIncompleteCalls：
     * 并行工具片段累加；<b>没观察到 content_block_stop 的调用一律丢弃</b>。
     *
     * <p>四种尾部（完整 / 截断 / 缺 block stop / 撞 max_tokens）都要把 finish_reason
     * 与工具调用数对齐——丢掉未闭合调用是为了防止执行空的 {@code {}}。</p>
     */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> streamCases() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("complete",
                        "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n"
                                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n"
                                + "data: {\"type\":\"message_stop\"}\n\n",
                        "tool_use", 2, true),
                org.junit.jupiter.params.provider.Arguments.of("cut off", "", "incomplete", 1, false),
                org.junit.jupiter.params.provider.Arguments.of("missing block stop",
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n",
                        "incomplete", 1, false),
                org.junit.jupiter.params.provider.Arguments.of("token limit",
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}\n\n",
                        "length", 1, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streamCases")
    void toolStreamParallelFragmentsAndIncompleteCalls(String name, String tail, String reason, int expectedCalls,
                                                       boolean complete) throws Exception {
        String prefix = "data: {\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"lookup\","
                + "\"input\":{}}}\n\ndata: {\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"id\\\":\"}}\n\ndata: "
                + "{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"42\\\"}\"}}\n\ndata: "
                + "{\"type\":\"content_block_stop\",\"index\":1}\n\ndata: "
                + "{\"type\":\"content_block_start\",\"index\":2,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"b\",\"name\":\"list\","
                + "\"input\":{}}}\n\n";
        String body = prefix + (tail == null ? "" : tail);

        ChatResponse parsed = AnthropicChat.parseAnthropicSse(new ByteArrayInputStream(utf8(body)));
        assertEquals(reason, parsed.getFinishReason());
        assertEquals(MAPPER.readTree("{\"id\":\"42\"}"), MAPPER.readTree(parsed.getToolCalls().get(0).getFunction().getArguments()));
        assertEquals(expectedCalls, parsed.getToolCalls().size(),
                "未收尾的 tool_use 不得进入可执行列表");
        if (complete) {
            assertEquals("{}", parsed.getToolCalls().get(1).getFunction().getArguments());
        }

        // 流式路径必须给出同样的事实（工具调用 + finish_reason）
        BlockingQueue<StreamResponse> queue = new LinkedBlockingQueue<>();
        AnthropicChat.processAnthropicStream(new ByteArrayInputStream(utf8(body)), "test", queue);
        StreamResponse last = null;
        while (true) {
            StreamResponse chunk = queue.poll(10, TimeUnit.SECONDS);
            if (chunk == null) {
                break;
            }
            last = chunk;
            if (chunk.isDone()) {
                break;
            }
        }
        assertTrue(last != null && last.isDone());
        assertEquals(reason, last.getFinishReason());
        assertToolCallsEqual(parsed.getToolCalls(), last.getToolCalls());
    }

    /** 对照 Go TestAnthropicNonStreamingToolUse：非流式响应里的 tool_use block。 */
    @Test
    void nonStreamingToolUse() throws Exception {
        AnthropicResponse response = MAPPER.readValue(
                "{\"content\":[{\"type\":\"text\",\"text\":\"Checking\"},{\"type\":\"tool_use\","
                        + "\"id\":\"a\",\"name\":\"lookup\",\"input\":{\"id\":\"42\"}}],"
                        + "\"stop_reason\":\"tool_use\"}",
                AnthropicResponse.class);

        ChatResponse parsed = model().parseResponse(response);
        assertEquals("Checking", parsed.getContent());
        assertEquals(1, parsed.getToolCalls().size());
        assertEquals("a", parsed.getToolCalls().get(0).getId());
        assertEquals("lookup", parsed.getToolCalls().get(0).getFunction().getName());
        assertEquals(MAPPER.readTree("{\"id\":\"42\"}"),
                MAPPER.readTree(parsed.getToolCalls().get(0).getFunction().getArguments()));
    }

    /** 流式 tool_call 起始事件要把 ID / 名称透出（供前端先建卡片）。 */
    @Test
    void toolCallStartEventCarriesIdAndName() throws Exception {
        String body = "data: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"search\",\"input\":{}}}\n\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        BlockingQueue<StreamResponse> queue = new LinkedBlockingQueue<>();
        AnthropicChat.processAnthropicStream(new ByteArrayInputStream(utf8(body)), "test", queue);

        List<StreamResponse> chunks = new ArrayList<>();
        while (true) {
            StreamResponse chunk = queue.poll(10, TimeUnit.SECONDS);
            if (chunk == null) {
                break;
            }
            chunks.add(chunk);
            if (chunk.isDone()) {
                break;
            }
        }
        assertEquals(2, chunks.size());
        assertEquals("tool_call", chunks.get(0).getResponseType().value());
        assertEquals("call_1", chunks.get(0).getData().get("tool_call_id"));
        assertEquals("search", chunks.get(0).getData().get("tool_name"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<AnthropicContentBlock> blocks(AnthropicMessage message) {
        return (List<AnthropicContentBlock>) message.getContent();
    }

    private static void assertToolCallsEqual(List<ToolCall> expected, List<ToolCall> actual) throws Exception {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getId(), actual.get(i).getId());
            assertEquals(expected.get(i).getFunction().getName(), actual.get(i).getFunction().getName());
            assertEquals(MAPPER.readTree(expected.get(i).getFunction().getArguments()),
                    MAPPER.readTree(actual.get(i).getFunction().getArguments()));
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
