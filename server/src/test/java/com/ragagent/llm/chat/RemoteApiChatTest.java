package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.domain.ToolCall;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/models/chat/remote_api_test.go（12 个测试）的语义对等翻译，
 * 外加针对 Java 单路径（裸 HTTP + SseReader）的端到端补测。
 *
 * <p>与 Go 测试的两处结构性差异：</p>
 * <ol>
 *   <li>Go 断言在 go-openai 的 {@code ChatCompletionRequest} 结构体字段上；Java 断言在
 *       {@link ObjectNode} 请求体的 JSON 上——后者才是线上契约（Go 的字段经 marshal 后等价）。</li>
 *   <li>Go 的 {@code useRawHTTP} 判定在 Java 侧不存在（单路径），故相关断言换成
 *       "body 里有没有该厂商的 thinking 字段"（见 {@link ProviderAdapterRegistryTest}）。</li>
 * </ol>
 */
class RemoteApiChatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 Go newTestRemoteChat：无 provider、无 baseURL 的默认实例。 */
    private static RemoteApiChat newTestRemoteChat() {
        ChatConfig config = new ChatConfig();
        config.setSource("remote");
        config.setModelName("test-model");
        config.setApiKey("test-key");
        config.setModelId("test-model");
        return new RemoteApiChat(config);
    }

    private static List<ChatMessage> userMessage(String content) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user(content));
        return messages;
    }

    private static JsonNode body(RemoteApiChat chat, List<ChatMessage> messages,
                                 ChatOptions opts, boolean isStream) {
        return chat.buildChatCompletionRequest(messages, opts, isStream);
    }

    // ------------------------------------------------------------------
    // 对照 Go TestBuildChatCompletionRequest_ParallelToolCalls
    // ------------------------------------------------------------------

    @Test
    void parallelToolCalls() throws Exception {
        RemoteApiChat chat = newTestRemoteChat();
        List<ChatMessage> messages = userMessage("hello");

        // nil ParallelToolCalls 不写字段（go-openai 是 any 类型，nil → omitempty 省略）
        ChatOptions unset = new ChatOptions();
        unset.setTemperature(0.7);
        assertNull(body(chat, messages, unset, false).get("parallel_tool_calls"));

        ChatOptions enabled = new ChatOptions();
        enabled.setTemperature(0.7);
        enabled.setParallelToolCalls(true);
        ChatTool tool = new ChatTool();
        tool.getFunction().setName("mcp_weather_getforecast");
        tool.getFunction().setDescription("Get weather");
        tool.getFunction().setParameters(MAPPER.readTree("{\"type\":\"object\"}"));
        enabled.setTools(List.of(tool));
        JsonNode enabledBody = body(chat, messages, enabled, true);
        assertNotNull(enabledBody.get("parallel_tool_calls"));
        assertTrue(enabledBody.get("parallel_tool_calls").asBoolean());
        assertEquals(1, enabledBody.get("tools").size());
        assertEquals("mcp_weather_getforecast", enabledBody.get("tools").get(0).path("function").path("name").asText());

        ChatOptions disabled = new ChatOptions();
        disabled.setTemperature(0.7);
        disabled.setParallelToolCalls(false);
        JsonNode disabledBody = body(chat, messages, disabled, false);
        assertNotNull(disabledBody.get("parallel_tool_calls"));
        assertFalse(disabledBody.get("parallel_tool_calls").asBoolean());
    }

    // ------------------------------------------------------------------
    // 对照 Go TestBuildChatCompletionRequest_MCPToolsFormat
    // ------------------------------------------------------------------

    @Test
    void mcpToolsFormat() throws IOException {
        RemoteApiChat chat = newTestRemoteChat();
        List<ChatMessage> messages = userMessage("查询乙醇的理化性质");

        ChatTool first = new ChatTool();
        first.getFunction().setName("mcp_hazardous_chemicals_gethazardouschemicals");
        first.getFunction().setDescription(
                "[MCP Service: hazardous_chemicals (external)] Get hazardous chemicals list");
        first.getFunction().setParameters(MAPPER.readTree("{\"type\":\"object\",\"properties\":{}}"));

        ChatTool second = new ChatTool();
        second.getFunction().setName("mcp_hazardous_chemicals_gethazardouschemicalbybizid");
        second.getFunction().setDescription(
                "[MCP Service: hazardous_chemicals (external)] Get hazardous chemical by biz ID");
        second.getFunction().setParameters(MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"bizId\":{\"type\":\"string\"}},\"required\":[\"bizId\"]}"));

        ChatOptions opts = new ChatOptions();
        opts.setTemperature(0.7);
        opts.setTools(List.of(first, second));
        opts.setParallelToolCalls(true);

        JsonNode node = body(chat, messages, opts, true);
        JsonNode tools = node.get("tools");
        assertEquals(2, tools.size());
        assertEquals("mcp_hazardous_chemicals_gethazardouschemicals",
                tools.get(0).path("function").path("name").asText());
        assertEquals("mcp_hazardous_chemicals_gethazardouschemicalbybizid",
                tools.get(1).path("function").path("name").asText());
        assertTrue(node.get("parallel_tool_calls").asBoolean());
        assertTrue(node.get("stream").asBoolean());

        for (JsonNode tool : tools) {
            String name = tool.path("function").path("name").asText();
            assertFalse(name.contains("ed606721"), "tool name must use service name, not UUID");
            assertTrue(name.matches("^[a-zA-Z0-9_-]+$"), "tool name must match OpenAI pattern");
            assertTrue(name.length() <= 64, "tool name must be <= 64 chars");
        }
    }

    // ------------------------------------------------------------------
    // 对照 Go TestBuildChatCompletionRequest_GPT5MaxCompletionTokens
    // ------------------------------------------------------------------

    @Test
    void gpt5MaxCompletionTokens() {
        record Case(String provider, String model, boolean shouldRewriteMaxTokens) {
        }
        List<Case> cases = List.of(
                new Case("azure_openai", "gpt-5.2", true),
                new Case("azure_openai", "gpt-5-mini", true),
                new Case("openai", "gpt-5", true),
                new Case("openai", "o1-mini", true),
                new Case("openai", "o3", true),
                new Case("openai", "o4-mini", true),
                new Case("openai", "gpt-4o", false),
                new Case("azure_openai", "gpt-4", false));
        List<ChatMessage> messages = userMessage("test");

        for (Case tc : cases) {
            RemoteApiChat chat = azureOrOpenAiChat(tc.provider(), tc.model());
            ChatOptions opts = new ChatOptions();
            opts.setTemperature(0.7);
            opts.setTopP(0.9);
            opts.setMaxTokens(128);
            opts.setFrequencyPenalty(0.1);
            opts.setPresencePenalty(0.2);

            JsonNode node = chat.shapedRequest(messages, opts, false);
            String label = tc.provider() + "/" + tc.model();
            assertNull(node.get("max_tokens"), label + ": MaxTokens must NOT be sent");
            assertEquals(128, node.path("max_completion_tokens").asInt(),
                    label + ": MaxCompletionTokens should be populated from MaxTokens");
            if (tc.shouldRewriteMaxTokens()) {
                assertNull(node.get("temperature"), label + ": temperature must be omitted");
                assertNull(node.get("top_p"), label + ": top_p must be omitted");
                assertNull(node.get("frequency_penalty"), label + ": frequency_penalty must be omitted");
                assertNull(node.get("presence_penalty"), label + ": presence_penalty must be omitted");
            } else {
                assertEquals(0.7, node.path("temperature").asDouble(), 1e-6, label);
            }
        }

        // MaxCompletionTokens takes precedence over MaxTokens
        RemoteApiChat chat = azureOrOpenAiChat("openai", "gpt-5.2");
        ChatOptions opts = new ChatOptions();
        opts.setMaxTokens(128);
        opts.setMaxCompletionTokens(2048);
        JsonNode node = chat.shapedRequest(messages, opts, false);
        assertNull(node.get("max_tokens"));
        assertEquals(2048, node.path("max_completion_tokens").asInt());
    }

    /** 对照 Go 测试里的 build(t, provider, model)：带 api_version 的 Azure/OpenAI 实例。 */
    private static RemoteApiChat azureOrOpenAiChat(String providerName, String modelName) {
        ChatConfig config = new ChatConfig();
        config.setSource("remote");
        config.setBaseUrl("https://example.openai.azure.com");
        config.setModelName(modelName);
        config.setApiKey("test-key");
        config.setModelId(modelName);
        config.setProvider(providerName);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("api_version", "2025-04-01-preview");
        config.setExtraConfig(extra);
        return new RemoteApiChat(config);
    }

    // ------------------------------------------------------------------
    // 对照 Go TestBuildChatCompletionRequest_ToolChoice
    // ------------------------------------------------------------------

    @Test
    void toolChoice() {
        RemoteApiChat chat = newTestRemoteChat();
        List<ChatMessage> messages = userMessage("test");

        ChatOptions auto = new ChatOptions();
        auto.setToolChoice("auto");
        assertEquals("auto", body(chat, messages, auto, false).path("tool_choice").asText());

        ChatOptions specific = new ChatOptions();
        specific.setToolChoice("mcp_svc_tool");
        JsonNode choice = body(chat, messages, specific, false).get("tool_choice");
        assertNotNull(choice);
        assertEquals("function", choice.path("type").asText());
        assertEquals("mcp_svc_tool", choice.path("function").path("name").asText());
    }

    // ------------------------------------------------------------------
    // 对照 Go TestConvertMessages_ReasoningContentRoundTrip（issue #1302）
    // ------------------------------------------------------------------

    @Test
    void reasoningContentRoundTrip() {
        RemoteApiChat chat = newTestRemoteChat();

        ChatMessage user1 = ChatMessage.user("hi");
        ChatMessage assistant = new ChatMessage("assistant", "the answer");
        assistant.setReasoningContent("let me think about this carefully");
        ChatMessage user2 = ChatMessage.user("follow-up");
        List<ChatMessage> out = chat.convertMessages(List.of(user1, assistant, user2));

        assertEquals(3, out.size());
        assertEquals("let me think about this carefully", out.get(1).getReasoningContent(),
                "assistant reasoning_content must be retained for multi-turn replay");
        assertTrue(isEmpty(out.get(0).getReasoningContent()), "user message must not carry reasoning_content");
        assertTrue(isEmpty(out.get(2).getReasoningContent()), "user message must not carry reasoning_content");

        // 非 assistant 角色即使设了也要丢掉
        ChatMessage sneaky = ChatMessage.user("hi");
        sneaky.setReasoningContent("should be dropped");
        List<ChatMessage> dropped = chat.convertMessages(List.of(sneaky));
        assertEquals(1, dropped.size());
        assertTrue(isEmpty(dropped.get(0).getReasoningContent()),
                "non-assistant roles must never carry reasoning_content upstream");

        // assistant 的 reasoning_content 为空时保持为空
        List<ChatMessage> empty = chat.convertMessages(List.of(new ChatMessage("assistant", "no thinking")));
        assertEquals(1, empty.size());
        assertTrue(isEmpty(empty.get(0).getReasoningContent()));

        // 线上 JSON：reasoning_content 只出现在 assistant 消息上
        JsonNode node = chat.buildChatCompletionRequest(List.of(user1, assistant), new ChatOptions(), false);
        assertNull(node.get("messages").get(0).get("reasoning_content"));
        assertEquals("let me think about this carefully",
                node.get("messages").get(1).path("reasoning_content").asText());
    }

    /** 补测：ConvertMessages 的 Images → MultiContent 展开（仅 user 角色，detail=auto，content 收尾）。 */
    @Test
    void convertMessagesExpandsImagesForUserOnly() {
        RemoteApiChat chat = newTestRemoteChat();
        ChatMessage withImage = ChatMessage.user("描述这张图");
        withImage.setImages(List.of("data:image/png;base64,AAAA"));

        JsonNode messages = chat.buildChatCompletionRequest(List.of(withImage), new ChatOptions(), false)
                .get("messages");
        JsonNode content = messages.get(0).get("content");
        assertTrue(content.isArray(), "images 应降级为 multi-content 数组");
        assertEquals(2, content.size());
        assertEquals("image_url", content.get(0).path("type").asText());
        assertEquals("data:image/png;base64,AAAA", content.get(0).path("image_url").path("url").asText());
        assertEquals("auto", content.get(0).path("image_url").path("detail").asText());
        assertEquals("text", content.get(1).path("type").asText());
        assertEquals("描述这张图", content.get(1).path("text").asText());

        // assistant 带 images 时不展开（对照 Go 的 msg.Role == "user" 条件）
        ChatMessage assistant = new ChatMessage("assistant", "ok");
        assistant.setImages(List.of("data:image/png;base64,AAAA"));
        JsonNode assistantMsg = chat.buildChatCompletionRequest(List.of(assistant), new ChatOptions(), false)
                .get("messages").get(0);
        assertTrue(assistantMsg.get("content").isTextual());
        assertEquals("ok", assistantMsg.get("content").asText());
    }

    /** 补测：tool 角色消息带上 tool_call_id / name，assistant 的 tool_calls 原样回传。 */
    @Test
    void convertMessagesKeepsToolProtocol() {
        RemoteApiChat chat = newTestRemoteChat();
        ChatMessage assistant = new ChatMessage("assistant", "");
        ToolCall call = new ToolCall();
        call.setId("call_1");
        call.setType("function");
        call.getFunction().setName("wiki_search");
        call.getFunction().setArguments("{\"query\":\"MACS\"}");
        assistant.setToolCalls(List.of(call));
        ChatMessage toolResult = ChatMessage.tool("call_1", "wiki_search", "42");

        JsonNode messages = chat.buildChatCompletionRequest(List.of(assistant, toolResult),
                new ChatOptions(), false).get("messages");
        assertNull(messages.get(0).get("content"), "go-openai 的 content 带 omitempty，空串不上线");
        JsonNode toolCall = messages.get(0).path("tool_calls").get(0);
        assertEquals("call_1", toolCall.path("id").asText());
        assertEquals("function", toolCall.path("type").asText());
        assertEquals("wiki_search", toolCall.path("function").path("name").asText());
        assertEquals("{\"query\":\"MACS\"}", toolCall.path("function").path("arguments").asText());
        assertEquals("call_1", messages.get(1).path("tool_call_id").asText());
        assertEquals("wiki_search", messages.get(1).path("name").asText());
    }

    /** 补测：tools 的 parameters 无 omitempty——nil 时上线 null（对照 go-openai 的 struct tag）。 */
    @Test
    void toolParametersEmitNullWhenMissing() {
        RemoteApiChat chat = newTestRemoteChat();
        ChatTool tool = new ChatTool();
        tool.getFunction().setName("no_params");
        tool.getFunction().setDescription("d");
        ChatOptions opts = new ChatOptions();
        opts.setTools(List.of(tool));

        JsonNode fn = body(chat, userMessage("x"), opts, false).get("tools").get(0).path("function");
        assertTrue(fn.has("parameters"));
        assertTrue(fn.get("parameters").isNull());
    }

    /**
     * 出站体键序**分路径**（2026-09-24 排查批修正 2c 的单一 map 序认知）：
     * prompt-cache 改写路径 = Go map 序（字母序，goSorted）；SDK 直出/thinking
     * 包装路径 = openai-go 结构体声明序（structSorted，包装字段尾随）。
     * 工具 parameters 子树两路径分别是「map 字母序」/「jsonschema 结构体序=录入序」。
     */
    @Test
    void structPathKeysFollowOpenaiGoStructOrder() throws Exception {
        RemoteApiChat chat = newTestRemoteChat();
        ChatTool tool = new ChatTool();
        tool.getFunction().setName("wiki_read_page");
        tool.getFunction().setDescription("d");
        tool.getFunction().setParameters(MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"slugs\":{\"type\":\"array\","
                        + "\"items\":{\"type\":\"string\"},\"description\":\"list\"}},"
                        + "\"required\":[\"slugs\"]}"));
        ChatOptions opts = new ChatOptions();
        opts.setTools(List.of(tool));

        // sessionId=null → prompt-cache 不改写 → Go 走 SDK 结构体直出
        String json = new String(chat.buildOutbound(userMessage("hi"), opts, false, null)
                .bodyBytes(), java.nio.charset.StandardCharsets.UTF_8);

        // 顶层结构体序：model 在 messages 前（map 序会相反）；tools 在 messages 后
        assertTrue(json.indexOf("\"model\"") < json.indexOf("\"messages\""),
                "顶层键序必须结构体序（model 先于 messages）：" + json);
        assertTrue(json.indexOf("\"messages\"") < json.indexOf("\"tools\""),
                "messages 在 tools 之前（结构体声明序）：" + json);
        // tools 元素：type 先于 function（map 序相反）
        assertTrue(json.contains("\"tools\":[{\"type\":\"function\",\"function\":{"),
                "tools 元素必须 type 先行：" + json);
        // function 内：name/description/parameters 结构体序
        assertTrue(json.contains("\"function\":{\"name\":\"wiki_read_page\",\"description\":\"d\","),
                "function 键序必须结构体序：" + json);
        // parameters 子树 = 录入序（jsonschema 结构体序），type 仍在首位
        assertTrue(json.contains("\"parameters\":{\"type\":\"object\",\"properties\":"),
                "parameters 子树保持 jsonschema 结构体序：" + json);
    }

    @Test
    void mapPathKeysStayAlphabetical() throws Exception {
        com.fasterxml.jackson.databind.JsonNode body = MAPPER.readTree(
                "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"stream\":true}");
        String json = RemoteApiChat.goSorted(body).toString();
        // map 改写路径：每层对象按键字节序
        assertTrue(json.indexOf("\"messages\"") < json.indexOf("\"model\"")
                        && json.indexOf("\"model\"") < json.indexOf("\"stream\""),
                "map 路径顶层必须字母序：" + json);
        assertTrue(json.contains("{\"content\":\"hi\",\"role\":\"user\"}"),
                "map 路径 messages 元素必须字母序：" + json);
    }

    // ------------------------------------------------------------------
    // 对照 Go TestApplyCompletionToolCallMetadata
    // ------------------------------------------------------------------

    @Test
    void applyCompletionToolCallMetadata() throws IOException {
        RemoteApiChat chat = newTestRemoteChat();
        chat.setAdapter(new ProviderAdapters.Gemini());

        ChatResponse resp = new ChatResponse();
        ToolCall call = new ToolCall();
        call.setId("call_1");
        call.setType("function");
        call.getFunction().setName("wiki_search");
        call.getFunction().setArguments("{\"query\":\"MACS\"}");
        resp.setToolCalls(new ArrayList<>(List.of(call)));

        JsonNode body = MAPPER.readTree("""
                {
                  "choices":[{
                    "message":{
                      "toolCalls":[{
                        "id":"call_1",
                        "type":"function",
                        "function":{"name":"wiki_search","arguments":"{\\"query\\":\\"MACS\\"}"},
                        "extra_content":{"google":{"thought_signature":"sig-from-gemini"}}
                      }]
                    }
                  }]
                }""");

        chat.applyCompletionToolCallMetadata(body, resp);
        assertEquals(1, resp.getToolCalls().size());
        assertEquals("sig-from-gemini", resp.getToolCalls().get(0).getProviderMetadata()
                .get("google").path("thought_signature").asText());
    }

    // ------------------------------------------------------------------
    // 对照 Go TestApplyStreamToolCallMetadata
    // ------------------------------------------------------------------

    @Test
    void applyStreamToolCallMetadata() throws IOException {
        RemoteApiChat chat = newTestRemoteChat();
        chat.setAdapter(new ProviderAdapters.Gemini());
        OpenAiStreamState state = new OpenAiStreamState();

        JsonNode chunk = MAPPER.readTree("""
                {
                  "choices":[{
                    "delta":{
                      "toolCalls":[{
                        "index":0,
                        "id":"call_1",
                        "type":"function",
                        "function":{"name":"wiki_search","arguments":"{\\"query\\":\\"MACS\\"}"},
                        "extra_content":{"google":{"thought_signature":"stream-sig-from-gemini"}}
                      }]
                    }
                  }]
                }""");

        chat.applyStreamToolCallMetadata(chunk, state);
        List<ToolCall> toolCalls = state.buildOrderedToolCalls();
        assertEquals(1, toolCalls.size());
        assertEquals("stream-sig-from-gemini", toolCalls.get(0).getProviderMetadata()
                .get("google").path("thought_signature").asText());
    }

    // ------------------------------------------------------------------
    // 对照 Go TestRemoteAPIChat（真实上游调用；Go 在无 API Key 时 Skip）
    // ------------------------------------------------------------------

    /**
     * 对照 Go TestRemoteAPIChat：需要 DEEPSEEK_API_KEY / ALIYUN_API_KEY 才能跑，
     * Go 侧无 key 时 {@code t.Skip}，Java 侧同样不参与默认测试运行。
     */
    @Disabled("需要 DEEPSEEK_API_KEY / ALIYUN_API_KEY 环境变量（对照 Go 的 t.Skip 分支）")
    @Test
    void liveRemoteApiChat() {
    }

    // ------------------------------------------------------------------
    // 对照 Go TestCachedTokensHelper / TestParseCompletionResponse_CachedTokens /
    // TestApplyRawPromptCacheUsage_DeepSeekNativeFields / TestTokenUsage_CachedTokensJSONOmitempty
    // ------------------------------------------------------------------

    /** 对照 Go TestCachedTokensHelper：nil 安全的 cached_tokens 读取。 */
    @Test
    void cachedTokensHelper() throws IOException {
        assertEquals(0, PromptCache.cachedTokens(null), "nil details must return zero");
        assertEquals(0, PromptCache.cachedTokens(MAPPER.readTree("{}")), "empty details must return zero");
        assertEquals(1234, PromptCache.cachedTokens(MAPPER.readTree("{\"cachedTokens\":1234}")),
                "populated cached_tokens must round-trip");
    }

    /** 对照 Go TestParseCompletionResponse_CachedTokens。 */
    @Test
    void parseCompletionResponseCachedTokens() throws IOException {
        RemoteApiChat chat = newTestRemoteChat();

        JsonNode withDetails = MAPPER.readTree("""
                {
                  "choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                  "usage":{"promptTokens":6929,"completionTokens":42,"totalTokens":6971,
                           "prompt_tokens_details":{"cachedTokens":6900}}
                }""");
        ChatResponse got = chat.parseCompletionResponse(withDetails);
        assertEquals(6929, got.getUsage().getPromptTokens());
        assertEquals(42, got.getUsage().getCompletionTokens());
        assertEquals(6971, got.getUsage().getTotalTokens());
        assertEquals(6900, got.getUsage().getCachedTokens(),
                "cached_tokens must mirror prompt_tokens_details.cached_tokens");
        assertEquals(6900, got.getUsage().getCacheReadTokens());
        assertEquals(29, got.getUsage().getCacheMissTokens());
        assertTrue(got.getUsage().isCacheReported());
        assertEquals("hit", got.getUsage().getCacheStatus().value());

        // 缺 prompt_tokens_details（Ollama / 老后端）→ 0，且不 panic
        JsonNode withoutDetails = MAPPER.readTree("""
                {
                  "choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                  "usage":{"promptTokens":100,"completionTokens":10,"totalTokens":110}
                }""");
        ChatResponse plain = chat.parseCompletionResponse(withoutDetails);
        assertEquals(0, plain.getUsage().getCachedTokens());
        assertFalse(plain.getUsage().isCacheReported());
        assertEquals("unsupported", plain.getUsage().getCacheStatus().value(),
                "generic provider 不上报缓存账目");
    }

    /** 对照 Go applyRawPromptCacheUsage 的 DeepSeek 原生字段。 */
    @Test
    void applyRawPromptCacheUsageDeepSeekNativeFields() {
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(4096);
        usage.setCompletionTokens(10);
        usage.setTotalTokens(4106);
        PromptCache.applyRawPromptCacheUsage(
                "{\"usage\":{\"promptTokens\":4096,\"prompt_cache_hit_tokens\":3072,"
                        + "\"prompt_cache_miss_tokens\":1024}}",
                usage);
        assertEquals(3072, usage.getCacheReadTokens());
        assertEquals(1024, usage.getCacheMissTokens());
        assertTrue(usage.isCacheReported());
        assertEquals("hit", usage.getCacheStatus().value());
    }

    /** 对照 Go TestTokenUsage_CachedTokensJSONOmitempty。 */
    @Test
    void tokenUsageCachedTokensJsonOmitempty() throws IOException {
        TokenUsage zero = new TokenUsage();
        zero.setPromptTokens(10);
        zero.setCompletionTokens(5);
        zero.setTotalTokens(15);
        assertFalse(MAPPER.writeValueAsString(zero).contains("cached_tokens"));

        TokenUsage nonZero = new TokenUsage();
        nonZero.setPromptTokens(10);
        nonZero.setCompletionTokens(5);
        nonZero.setTotalTokens(15);
        nonZero.setPromptCacheUsage(7, 0, 3, true);
        assertTrue(MAPPER.writeValueAsString(nonZero).contains("\"cachedTokens\":7"));
    }

    /** 对照 Go removeThinkingContent 的四个分支（>think< 开头才剥、取最后一个闭标签、截断返空）。 */
    @Test
    void removeThinkingContent() {
        assertEquals("answer", RemoteApiChat.removeThinkingContent("<think>step</think>answer"));
        assertEquals("", RemoteApiChat.removeThinkingContent("<think>only thinking</think>"));
        assertEquals("", RemoteApiChat.removeThinkingContent("<think>truncated thinking"));
        assertEquals("plain", RemoteApiChat.removeThinkingContent("plain"));
        assertEquals("b", RemoteApiChat.removeThinkingContent(" <think>a</think>b "));
    }

    // ------------------------------------------------------------------
    // Java 侧补测：端到端走真实 HTTP（裸 HTTP 单路径 + SseReader + 出站 body）
    // ------------------------------------------------------------------

    private HttpServer server;
    private SsrfGuard previousGuard;
    private SsrfGuard.Whitelist whitelistSnapshot;

    @BeforeEach
    void allowLoopback() {
        // 被测代码会对 endpoint 做 SSRF 校验；测试把回环地址加入白名单（对照 Go 测试用 httptest）
        whitelistSnapshot = SsrfGuard.snapshotWhitelist();
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1,example.openai.azure.com");
        LlmTransport.setSsrfGuard(guard);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        SsrfGuard.restoreWhitelist(whitelistSnapshot);
        LlmTransport.setSsrfGuard(new SsrfGuard());
    }

    private String startServer(com.sun.net.httpserver.HttpHandler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static RemoteApiChat chatFor(String baseUrl) {
        ChatConfig config = new ChatConfig();
        config.setSource("remote");
        config.setBaseUrl(baseUrl);
        config.setModelName("test-model");
        config.setApiKey("test-key");
        config.setModelId("test-model");
        config.setProvider("generic");
        return new RemoteApiChat(config);
    }

    /** 非流式：出站 body 正确（恰好一个 token 字段 + stream 缺省），回程解析带 usage 与工具调用。 */
    @Test
    void chatEndToEnd() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            try {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] out = """
                        {
                          "choices":[{"message":{"role":"assistant","content":"<think>x</think>hello",
                                     "toolCalls":[{"id":"call_1","type":"function",
                                       "function":{"name":"wiki_search","arguments":"{}"}}]},
                                     "finish_reason":"tool_calls"}],
                          "usage":{"promptTokens":11,"completionTokens":2,"totalTokens":13}
                        }""".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RemoteApiChat chat = chatFor(baseUrl);
        ChatOptions opts = new ChatOptions();
        opts.setTemperature(0.3);
        opts.setMaxTokens(64);
        ChatResponse resp = chat.chat(userMessage("hi"), opts);

        assertEquals("hello", resp.getContent(), "<think> 包裹的思考过程要被剥掉");
        assertEquals("tool_calls", resp.getFinishReason());
        assertEquals(13, resp.getUsage().getTotalTokens());
        assertEquals(1, resp.getToolCalls().size());
        assertEquals("wiki_search", resp.getToolCalls().get(0).getFunction().getName());

        JsonNode sent = MAPPER.readTree(requestBody.get());
        assertEquals("test-model", sent.path("model").asText());
        assertEquals("hi", sent.path("messages").get(0).path("content").asText());
        assertEquals(64, sent.path("max_tokens").asInt(), "generic provider 用 max_tokens");
        assertNull(sent.get("max_completion_tokens"));
        assertNull(sent.get("stream"), "非流式不带 stream");
        assertEquals("Bearer test-key", authorization.get());
        assertEquals("application/json", contentType.get());
    }

    /** 流式：逐块产出顺序与 Go processStreamDelta 逐条对齐。 */
    @Test
    void chatStreamEndToEnd() throws Exception {
        String sse = """
                data: {"choices":[{"index":0,"delta":{"reasoningContent":"think-1"}}]}

                data: {"choices":[{"index":0,"delta":{"toolCalls":[{"index":0,"id":"call_1","type":"function","function":{"name":"wiki_search"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"toolCalls":[{"index":0,"function":{"arguments":"{\\"query\\":"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"toolCalls":[{"index":0,"function":{"arguments":"\\"MACS\\"}"}}]}}]}

                data: {"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"promptTokens":7,"completionTokens":3,"totalTokens":10}}

                data: [DONE]

                """;
        String baseUrl = startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                byte[] out = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RemoteApiChat chat = chatFor(baseUrl);
        ChatOptions opts = new ChatOptions();
        BlockingQueue<StreamResponse> stream = chat.chatStream(userMessage("hi"), opts);

        List<StreamResponse> events = drainAll(stream);

        // 1) reasoning 分片
        assertEquals(ResponseType.THINKING, events.get(0).getResponseType());
        assertEquals("think-1", events.get(0).getContent());
        assertFalse(events.get(0).isDone());
        // 2) 名字稳定 + 有 arguments + 有 id 后，tool_call 只发一次
        assertEquals(ResponseType.TOOL_CALL, events.get(1).getResponseType());
        assertEquals("wiki_search", events.get(1).getData().get("tool_name"));
        assertEquals("call_1", events.get(1).getData().get("tool_call_id"));
        assertFalse(events.get(1).isDone());
        // 3) 首个答案分片前补 thinking-done
        assertEquals(ResponseType.THINKING, events.get(2).getResponseType());
        assertTrue(events.get(2).isDone());
        assertEquals("", events.get(2).getContent());
        // 4) 答案分片（done=true，带当前工具调用与 finish_reason）
        assertEquals(ResponseType.ANSWER, events.get(3).getResponseType());
        assertEquals("hi", events.get(3).getContent());
        assertTrue(events.get(3).isDone());
        assertEquals("stop", events.get(3).getFinishReason());
        assertEquals(1, events.get(3).getToolCalls().size());
        assertEquals("wiki_search", events.get(3).getToolCalls().get(0).getFunction().getName());
        assertEquals("{\"query\":\"MACS\"}", events.get(3).getToolCalls().get(0).getFunction().getArguments());
        // 5) isDone 且有工具调用 → 再发一条终态 answer
        assertEquals(ResponseType.ANSWER, events.get(4).getResponseType());
        assertEquals("", events.get(4).getContent());
        assertTrue(events.get(4).isDone());
        // 6) EOF 终态 answer：带工具调用 + usage
        assertEquals(ResponseType.ANSWER, events.get(5).getResponseType());
        assertTrue(events.get(5).isDone());
        assertEquals(1, events.get(5).getToolCalls().size());
        assertEquals(10, events.get(5).getUsage().getTotalTokens());
        assertEquals(6, events.size(), "产出块数必须与 Go 的逐块规则一致");
    }

    /** 流式：tool_call 在"名字还没稳定"时不得发出（首发名字的那个 delta 不发标记）。 */
    @Test
    void toolCallMarkerWaitsForStableName() throws Exception {
        RemoteApiChat chat = newTestRemoteChat();
        OpenAiStreamState state = new OpenAiStreamState();
        BlockingQueue<StreamResponse> ch = new java.util.concurrent.LinkedBlockingQueue<>();

        // 第一个 delta：名字首次出现 + 已有 arguments + 已有 id → 名字还没稳定，不发
        chat.processStreamDelta(MAPPER.readTree("""
                {"index":0,"delta":{"toolCalls":[{"index":0,"id":"call_1","type":"function",
                 "function":{"name":"wiki_search","arguments":"{\\"query\\":\\"MA"}}]},
                 "finish_reason":""}"""), state, ch, "");
        assertTrue(ch.isEmpty(), "名字首次出现的那一块不能发 tool_call 标记");

        // 第二个 delta：同一名字重复发全名（vLLM 行为）+ arguments 增量 → 这回发一次
        chat.processStreamDelta(MAPPER.readTree("""
                {"index":0,"delta":{"toolCalls":[{"index":0,"function":{"name":"wiki_search",
                 "arguments":"CS\\"}"}}]},"finish_reason":""}"""), state, ch, "");
        assertEquals(1, ch.size());
        StreamResponse marker = ch.poll();
        assertEquals(ResponseType.TOOL_CALL, marker.getResponseType());
        assertEquals("wiki_search", marker.getData().get("tool_name"), "重复全名不得叠加");
        assertEquals("{\"query\":\"MACS\"}", state.toolCallMap.get(0).getFunction().getArguments());

        // 第三个 delta：同名再来一次 → 不再重复发
        chat.processStreamDelta(MAPPER.readTree("""
                {"index":0,"delta":{"toolCalls":[{"index":0,"function":{"arguments":" "}}]},
                 "finish_reason":""}"""), state, ch, "");
        assertTrue(ch.isEmpty(), "tool_call 标记每个 index 只发一次");
    }

    /** 流式：thinking 工具的 thought 参数字段按 thinking 分片增量下发。 */
    @Test
    void thinkingToolStreamsThoughtField() throws Exception {
        RemoteApiChat chat = newTestRemoteChat();
        OpenAiStreamState state = new OpenAiStreamState();
        BlockingQueue<StreamResponse> ch = new java.util.concurrent.LinkedBlockingQueue<>();

        chat.processStreamDelta(MAPPER.readTree("""
                {"delta":{"toolCalls":[{"index":0,"id":"call_9","type":"function",
                 "function":{"name":"thinking","arguments":"{\\"thought\\":\\"step "}}]}}"""),
                state, ch, "");
        chat.processStreamDelta(MAPPER.readTree("""
                {"delta":{"toolCalls":[{"index":0,"function":{"arguments":"one\\"}"}}]}}"""),
                state, ch, "");

        List<StreamResponse> thoughtChunks = new ArrayList<>();
        for (StreamResponse r : ch) {
            if (r.getResponseType() == ResponseType.THINKING) {
                thoughtChunks.add(r);
            }
        }
        assertEquals(2, thoughtChunks.size());
        assertEquals("step ", thoughtChunks.get(0).getContent());
        assertEquals("one", thoughtChunks.get(1).getContent());
        assertEquals("thinking_tool", thoughtChunks.get(0).getData().get("source"));
        assertEquals("call_9", thoughtChunks.get(0).getData().get("tool_call_id"));
    }

    /** 流式：读错误（连接中途断开且非 EOF）→ error{Done:true, FinishReason:"incomplete"}。 */
    @Test
    void streamEofEmitsTerminalAnswer() throws Exception {
        String baseUrl = startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                byte[] out = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RemoteApiChat chat = chatFor(baseUrl);
        BlockingQueue<StreamResponse> stream = chat.chatStream(userMessage("hi"), new ChatOptions());

        List<StreamResponse> events = drainAll(stream);
        // 服务端正常收尾（EOF）→ 终态 answer，而不是 error（对照 Go 裸 HTTP 路径的 io.EOF 分支）
        assertEquals(ResponseType.ANSWER, events.get(events.size() - 1).getResponseType());
        assertTrue(events.get(events.size() - 1).isDone());
    }

    /** 非 200：抛出携带上游 body 的错误（对照 Go 的 "API request failed with status %d: %s"）。 */
    @Test
    void nonOkStatusThrows() {
        String baseUrl = startServer(exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                byte[] out = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RemoteApiChat chat = chatFor(baseUrl);
        BizException err = assertThrows(BizException.class,
                () -> chat.chat(userMessage("hi"), new ChatOptions()));
        assertTrue(err.getMessage().contains("API request failed with status 400"), err.getMessage());
    }

    /** 构造期校验：WeKnoraCloud 缺 AppID/AppSecret 直接拒绝（对照 Go 的构造期 error）。 */
    @Test
    void weKnoraCloudRequiresCredentials() {
        ChatConfig config = new ChatConfig();
        config.setModelName("m");
        config.setProvider("weknoracloud");
        config.setApiKey("k");
        assertTrue(assertThrows(BizException.class, () -> new RemoteApiChat(config))
                .getMessage().contains("AppID is required"));

        config.setAppId("app");
        assertTrue(assertThrows(BizException.class, () -> new RemoteApiChat(config))
                .getMessage().contains("AppSecret is required"));

        config.setAppSecret("secret");
        new RemoteApiChat(config); // 两个都齐了就不抛
    }

    /** Azure 的 endpoint 需要自己拼（Go 走 SDK 的 fullURL，Java 单路径复刻同一公式）。 */
    @Test
    void azureEndpointUsesDeploymentPath() {
        ChatConfig config = new ChatConfig();
        config.setModelName("gpt-5-mini");
        config.setModelId("my-deployment");
        config.setProvider("azure_openai");
        config.setApiKey("k");
        config.setBaseUrl("https://example.openai.azure.com");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("api_version", "2025-04-01-preview");
        config.setExtraConfig(extra);

        RemoteApiChat chat = new RemoteApiChat(config);
        RemoteApiChat.Outbound out = chat.buildOutbound(userMessage("hi"), new ChatOptions(), false, null);
        assertEquals("https://example.openai.azure.com/openai/deployments/my-deployment"
                + "/chat/completions?api-version=2025-04-01-preview", out.endpoint());
    }

    /** remote_model_name 覆盖出站模型名（对照 Go 的 extraConfig 处理）。 */
    @Test
    void remoteModelNameOverride() {
        ChatConfig config = new ChatConfig();
        config.setModelName("display-name");
        config.setModelId("id");
        config.setProvider("generic");
        config.setApiKey("k");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("remote_model_name", " upstream-model ");
        config.setExtraConfig(extra);

        RemoteApiChat chat = new RemoteApiChat(config);
        JsonNode sent = chat.buildChatCompletionRequest(userMessage("hi"), new ChatOptions(), false);
        assertEquals("upstream-model", sent.path("model").asText());
    }

    /** 收干队列（生产者结束后 poll 到 null 为止）——断言逐块顺序需要拿到全部元素。 */
    private static List<StreamResponse> drainAll(BlockingQueue<StreamResponse> stream)
            throws InterruptedException {
        List<StreamResponse> events = new ArrayList<>();
        StreamResponse event;
        while ((event = stream.poll(2, TimeUnit.SECONDS)) != null) {
            events.add(event);
            if (events.size() > 64) {
                break;
            }
        }
        return events;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** 便于断言用的小工具：把请求体序列化成 JSON 文本（未用到时不会执行）。 */
    static String json(ObjectNode node) throws IOException {
        return MAPPER.writeValueAsString(node);
    }
}
