package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.CacheRetention;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.FunctionCall;
import com.ragagent.llm.domain.PromptCacheStatus;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.llm.ollama.OllamaChatRequest;
import com.ragagent.llm.ollama.OllamaChatResponse;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.llm.ollama.OllamaToolCall;

import org.junit.jupiter.api.Test;

/**
 * Ollama chat 路径的语义测试（按代码逐条建契约，覆盖最容易出错的几处）。
 *
 * <p>用 {@link FakeOllamaService} 替换真实服务，
 * 假服务返回的响应体从 JSON 解析，顺带把线上字段名也钉住了。</p>
 */
class OllamaChatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 以构造器注入的方式替换真实服务。 */
    private static final class FakeOllamaService extends OllamaService {

        private final List<OllamaChatResponse> responses = new ArrayList<>();
        private OllamaChatRequest capturedRequest;
        private RuntimeException failure;

        private FakeOllamaService() {
            super("http://127.0.0.1:1", false);
        }

        private void add(String json) {
            try {
                responses.add(MAPPER.readValue(json, OllamaChatResponse.class));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void ensureModelAvailable(String modelName) {
            // 测试不发网络请求
        }

        @Override
        public void chat(OllamaChatRequest request, ChatCallback fn) {
            capturedRequest = request;
            if (failure != null) {
                throw failure;
            }
            for (OllamaChatResponse response : responses) {
                fn.onResponse(response);
            }
        }
    }

    private static OllamaChat chat(FakeOllamaService service) {
        ChatConfig config = new ChatConfig();
        config.setModelName("qwen3:latest");
        config.setModelId("model-1");
        return new OllamaChat(config, service);
    }

    /**
     * 收满一个流。<b>注意不能以 done=true 作为结束条件</b>：Ollama 路径会在流中间补一个
     * thinking-done（type=thinking + done=true），真正的终态是 ANSWER/ERROR + done=true。
     */
    private static List<StreamResponse> drain(BlockingQueue<StreamResponse> queue) throws InterruptedException {
        List<StreamResponse> chunks = new ArrayList<>();
        while (true) {
            StreamResponse chunk = queue.poll(10, TimeUnit.SECONDS);
            if (chunk == null) {
                break;
            }
            chunks.add(chunk);
            if (chunk.isDone() && (chunk.getResponseType() == ResponseType.ANSWER
                    || chunk.getResponseType() == ResponseType.ERROR)) {
                break;
            }
        }
        return chunks;
    }

    // ------------------------------------------------------------------
    // 请求体构造
    // ------------------------------------------------------------------

    /**
     * 补全预算走 {@code options.num_predict}（不是 max_tokens）；think / format 各自透传；
     * temperature 无条件发送。
     */
    @Test
    void buildChatRequestUsesNumPredictThinkAndFormat() throws Exception {
        FakeOllamaService service = new FakeOllamaService();
        ChatOptions opts = new ChatOptions();
        opts.setTemperature(0.7);
        opts.setTopP(0.9);
        opts.setMaxCompletionTokens(64);
        opts.setThinking(Boolean.TRUE);
        opts.setFormat(MAPPER.readTree("{\"type\":\"json\"}"));

        OllamaChatRequest request = chat(service).buildChatRequest(
                List.of(ChatMessage.user("hi")), opts, false);
        JsonNode sent = MAPPER.valueToTree(request);

        assertEquals("qwen3:latest", sent.get("model").asText());
        assertEquals(false, sent.get("stream").asBoolean());
        assertEquals("hi", sent.get("messages").get(0).get("content").asText());
        assertEquals(0.7, sent.get("options").get("temperature").asDouble());
        assertEquals(0.9, sent.get("options").get("top_p").asDouble());
        assertEquals(64, sent.get("options").get("num_predict").asInt(), "补全预算 = num_predict");
        assertTrue(sent.get("options").get("max_tokens") == null, "Ollama 不接受 max_tokens");
        assertEquals(true, sent.get("think").asBoolean());
        assertEquals("json", sent.get("format").get("type").asText());
    }

    /** max_tokens 与 max_completion_tokens 是同一预算的两个别名，都设时后者优先。 */
    @Test
    void completionBudgetPrefersMaxCompletionTokens() throws Exception {
        FakeOllamaService service = new FakeOllamaService();
        ChatOptions opts = new ChatOptions();
        opts.setMaxTokens(11);
        opts.setMaxCompletionTokens(22);
        JsonNode sent = MAPPER.valueToTree(chat(service).buildChatRequest(List.of(ChatMessage.user("x")), opts, true));
        assertEquals(22, sent.get("options").get("num_predict").asInt());
        assertEquals(true, sent.get("stream").asBoolean());
    }

    /** opts 为 null 时只有空 options（输出 {}）。 */
    @Test
    void buildChatRequestWithoutOptions() {
        JsonNode sent = MAPPER.valueToTree(
                chat(new FakeOllamaService()).buildChatRequest(List.of(ChatMessage.user("x")), null, false));
        assertTrue(sent.get("options").isObject());
        assertEquals(0, sent.get("options").size());
        assertTrue(sent.get("think") == null);
    }

    /** 工具 schema 原样透传（不做 Go SDK 那套丢字段的强类型往返）。 */
    @Test
    void toolsSchemaPassesThroughVerbatim() throws Exception {
        FakeOllamaService service = new FakeOllamaService();
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"$defs\":{\"id\":{\"type\":\"string\"}},"
                + "\"properties\":{\"id\":{\"$ref\":\"#/$defs/id\"}},\"oneOf\":[{\"required\":[\"id\"]}],"
                + "\"additionalProperties\":false}");
        ChatOptions opts = new ChatOptions();
        opts.setTools(List.of(new ChatTool("lookup", "desc", schema)));

        JsonNode sent = MAPPER.valueToTree(
                chat(service).buildChatRequest(List.of(ChatMessage.user("x")), opts, false));
        assertEquals("function", sent.get("tools").get(0).get("type").asText());
        assertEquals("lookup", sent.get("tools").get(0).get("function").get("name").asText());
        assertEquals(schema, sent.get("tools").get(0).get("function").get("parameters"));
    }

    // ------------------------------------------------------------------
    // 消息与工具调用互转
    // ------------------------------------------------------------------

    /** tool 角色带 tool_name；只有 user 消息的图片会被解析成字节。 */
    @Test
    void convertMessagesCarriesToolNameAndResolvesUserImages() {
        FakeOllamaService service = new FakeOllamaService();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        String dataUri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);

        ChatMessage user = ChatMessage.user("看图");
        user.setImages(List.of(dataUri, "not-a-resolvable-url"));
        ChatMessage tool = ChatMessage.tool("0", "lookup", "result");
        ChatMessage assistantImages = new ChatMessage("assistant", "x");
        assistantImages.setImages(List.of(dataUri));

        List<com.ragagent.llm.ollama.OllamaMessage> converted =
                chat(service).convertMessages(List.of(user, tool, assistantImages));

        assertEquals(1, converted.get(0).getImages().size(), "解析不出的图片直接跳过");
        assertTrue(java.util.Arrays.equals(png, converted.get(0).getImages().get(0)));
        assertEquals("lookup", converted.get(1).getToolName());
        assertNull(converted.get(2).getImages(), "非 user 消息不带图片");
    }

    /** 出站：ID 解析回整数 index，arguments 变对象；入站：index 转字符串当 ID。 */
    @Test
    void toolCallIdMapsToAndFromFunctionIndex() throws Exception {
        FakeOllamaService service = new FakeOllamaService();
        OllamaChat client = chat(service);

        ToolCall call = new ToolCall();
        call.setId("3");
        call.setFunction(new FunctionCall("lookup", "{\"id\":\"42\"}"));

        List<OllamaToolCall> outbound = client.toolCallFrom(List.of(call));
        assertEquals(3, outbound.get(0).getFunction().getIndex());
        assertEquals("lookup", outbound.get(0).getFunction().getName());
        assertEquals(MAPPER.readTree("{\"id\":\"42\"}"), outbound.get(0).getFunction().getArguments());
        JsonNode outboundJson = MAPPER.valueToTree(outbound.get(0));
        assertTrue(outboundJson.get("id") == null, "Ollama 的工具调用没有语义化 ID");
        assertEquals(3, outboundJson.get("function").get("index").asInt());

        // 非数字 ID → index 0（解析失败按 0 处理）
        assertEquals(0, client.toolCallFrom(List.of(new ToolCall())).get(0).getFunction().getIndex());

        OllamaToolCall inbound = MAPPER.readValue(
                "{\"function\":{\"index\":2,\"name\":\"list\",\"arguments\":{\"x\":1}}}", OllamaToolCall.class);
        List<ToolCall> back = client.toolCallTo(List.of(inbound));
        assertEquals("2", back.get(0).getId());
        assertEquals("function", back.get(0).getType());
        assertEquals("list", back.get(0).getFunction().getName());
        assertEquals(MAPPER.readTree("{\"x\":1}"), MAPPER.readTree(back.get(0).getFunction().getArguments()));

        assertEquals("7", OllamaChat.tooli2s(7));
        assertEquals(7, OllamaChat.tools2i("7"));
        assertEquals(0, OllamaChat.tools2i("call-7"));
        assertEquals(0, OllamaChat.tools2i(null));
    }

    // ------------------------------------------------------------------
    // 非流式
    // ------------------------------------------------------------------

    /** 非流式补全量 = eval_count - prompt_eval_count；且永远标记缓存不可用、finish_reason 留空。 */
    @Test
    void chatUsesEvalCountMinusPromptEvalCount() {
        FakeOllamaService service = new FakeOllamaService();
        service.add("{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},"
                + "\"done\":true,\"prompt_eval_count\":10,\"eval_count\":25}");

        ChatResponse resp = chat(service).chat(List.of(ChatMessage.user("hi")), null);

        assertEquals("hello", resp.getContent());
        assertEquals(10, resp.getUsage().getPromptTokens());
        assertEquals(15, resp.getUsage().getCompletionTokens(), "非流式：eval_count - prompt_eval_count");
        assertEquals(25, resp.getUsage().getTotalTokens());
        assertEquals(PromptCacheStatus.UNSUPPORTED, resp.getUsage().getCacheStatus());
        assertNull(resp.getFinishReason(), "Ollama 路径不设置 finish_reason");
    }

    /** 推理模型没配好 thinking 参数时，Content 为空要用 Thinking 兜底。 */
    @Test
    void chatFallsBackToThinkingWhenContentEmpty() {
        FakeOllamaService service = new FakeOllamaService();
        service.add("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"思考结果\"},\"done\":true}");

        assertEquals("思考结果", chat(service).chat(List.of(ChatMessage.user("hi")), null).getContent());
    }

    /** 请求体透传 ChatOptions 的其余字段（CacheRetention 与 Ollama 无关，不该出现在线上）。 */
    @Test
    void chatRequestOmitsIrrelevantOptions() {
        FakeOllamaService service = new FakeOllamaService();
        service.add("{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"done\":true}");
        ChatOptions opts = new ChatOptions();
        opts.setCacheRetention(CacheRetention.LONG);
        chat(service).chat(List.of(ChatMessage.user("hi")), opts);

        assertNotNull(service.capturedRequest);
        assertNull(service.capturedRequest.getOptions().get("prompt_cache_key"));
    }

    // ------------------------------------------------------------------
    // 流式
    // ------------------------------------------------------------------

    /** 流式：思考 → 思考结束 → 答案 → 工具调用（整块）→ thinking 工具的思考 → done。 */
    @Test
    void chatStreamEmitsCompleteBlocksAndThinkingDone() throws Exception {
        FakeOllamaService service = new FakeOllamaService();
        service.add("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"let me think\"},\"done\":false}");
        service.add("{\"message\":{\"role\":\"assistant\",\"content\":\"answer\"},\"done\":false}");
        service.add("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                + "{\"function\":{\"index\":0,\"name\":\"thinking\",\"arguments\":{\"thought\":\"deep thought\"}}},"
                + "{\"function\":{\"index\":1,\"name\":\"lookup\",\"arguments\":{\"id\":\"42\"}}}]},\"done\":false}");
        service.add("{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                + "\"prompt_eval_count\":10,\"eval_count\":7}");

        List<StreamResponse> chunks = drain(chat(service).chatStream(List.of(ChatMessage.user("hi")), null));

        assertEquals(6, chunks.size());
        assertEquals(ResponseType.THINKING, chunks.get(0).getResponseType());
        assertEquals("let me think", chunks.get(0).getContent());
        assertEquals(ResponseType.THINKING, chunks.get(1).getResponseType());
        assertTrue(chunks.get(1).isDone(), "首个答案 token 之前必须补 thinking-done");
        assertEquals("", chunks.get(1).getContent());

        assertEquals(ResponseType.ANSWER, chunks.get(2).getResponseType());
        assertEquals("answer", chunks.get(2).getContent());

        // 工具调用整块到达（两个一起）
        assertEquals(ResponseType.TOOL_CALL, chunks.get(3).getResponseType());
        assertEquals(2, chunks.get(3).getToolCalls().size());
        assertEquals("0", chunks.get(3).getToolCalls().get(0).getId());
        assertEquals("lookup", chunks.get(3).getToolCalls().get(1).getFunction().getName());

        // thinking 工具的 thought 额外补一个 THINKING 块
        assertEquals(ResponseType.THINKING, chunks.get(4).getResponseType());
        assertEquals("deep thought", chunks.get(4).getContent());
        assertEquals("thinking_tool", chunks.get(4).getData().get("source"));
        assertEquals("0", chunks.get(4).getData().get("tool_call_id"));

        // 终态：流式补全量直接用 eval_count（与非流式口径不同）
        StreamResponse last = chunks.get(5);
        assertEquals(ResponseType.ANSWER, last.getResponseType());
        assertTrue(last.isDone());
        assertEquals(10, last.getUsage().getPromptTokens());
        assertEquals(7, last.getUsage().getCompletionTokens(), "流式：eval_count 原样");
        assertEquals(17, last.getUsage().getTotalTokens());
        assertEquals(PromptCacheStatus.UNSUPPORTED, last.getUsage().getCacheStatus());
        assertNull(last.getFinishReason());
    }

    /** 流式失败：只补一个 ERROR + done（内容 = 底层错误消息）。 */
    @Test
    void chatStreamEmitsErrorChunkOnFailure() throws Exception {
        FakeOllamaService service = new FakeOllamaService();
        service.failure = new IllegalStateException("model not found");

        List<StreamResponse> chunks = drain(chat(service).chatStream(List.of(ChatMessage.user("hi")), null));

        assertEquals(1, chunks.size());
        assertEquals(ResponseType.ERROR, chunks.get(0).getResponseType());
        assertEquals("model not found", chunks.get(0).getContent());
        assertTrue(chunks.get(0).isDone());
    }
}
