package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.llm.provider.ProviderName;

import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/models/chat/provider_test.go（4 个测试）的语义对等翻译：
 * 注册表路由表、thinking 合并路径（buildOutbound）、参数整形、Gemini 工具元数据往返。
 *
 * <p>差异说明：Go 断言 {@code useRawHTTP}（SDK 路径 vs 裸 HTTP 路径）与 SDK 结构体字段；
 * Java 单路径没有该判定，故断言落在**出站 JSON** 上——那才是真正决定线上行为的东西。
 * 原先 {@code useRaw == true} 的用例在 Java 侧等价于"body 里出现了厂商特有的 thinking 字段"，
 * {@code useRaw == false} 的用例等价于"body 是干净的（没有任何 thinking 扩展字段）"。</p>
 */
class ProviderAdapterRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // 对照 Go TestResolveProvider
    // ------------------------------------------------------------------

    @Test
    void resolveProvider() {
        record Case(String name, ProviderName provider, String model, Class<?> want) {
        }
        List<Case> cases = List.of(
                new Case("deepseek", ProviderName.DEEPSEEK, "deepseek-chat", ProviderAdapters.Deepseek.class),
                new Case("lkeap v3", ProviderName.LKEAP, "deepseek-v3.1", ProviderAdapters.Lkeap.class),
                new Case("lkeap r1 falls back", ProviderName.LKEAP, "deepseek-r1", BaseProvider.class),
                new Case("qwen thinking", ProviderName.ALIYUN, "qwen3-32b", ProviderAdapters.QwenThinking.class),
                new Case("generic", ProviderName.GENERIC, "anything", ProviderAdapters.Generic.class),
                new Case("litellm", ProviderName.LITELLM, "anything", ProviderAdapters.LiteLlm.class),
                new Case("gemini", ProviderName.GEMINI, "gemini-3-flash-preview", ProviderAdapters.Gemini.class),
                new Case("nvidia", ProviderName.NVIDIA, "anything", ProviderAdapters.Nvidia.class),
                new Case("volcengine", ProviderName.VOLCENGINE, "doubao", ProviderAdapters.Volcengine.class),
                new Case("openai non-reasoning falls back", ProviderName.OPENAI, "gpt-4o", BaseProvider.class),
                new Case("openai reasoning", ProviderName.OPENAI, "gpt-5", ProviderAdapters.OpenAiReasoning.class),
                new Case("azure non-reasoning", ProviderName.AZURE_OPEN_AI, "gpt-4", ProviderAdapters.Azure.class),
                new Case("azure reasoning", ProviderName.AZURE_OPEN_AI, "gpt-5-mini",
                        ProviderAdapters.AzureReasoning.class),
                new Case("moonshot fixed temp", ProviderName.MOONSHOT, "moonshot-v1-8k",
                        ProviderAdapters.Moonshot.class),
                new Case("moonshot other falls back", ProviderName.MOONSHOT, "kimi-latest", BaseProvider.class),
                new Case("weknora cloud", ProviderName.WEKNORA_CLOUD, "anything",
                        ProviderAdapters.WeKnoraCloud.class),
                new Case("unknown falls back", null, "x", BaseProvider.class));

        for (Case tc : cases) {
            assertEquals(tc.want(), ProviderAdapters.resolve(tc.provider(), tc.model()).getClass(),
                    tc.name() + ": 注册表路由必须逐条保序命中");
        }
    }

    /** 补测：注册表顺序有语义——专有适配器必须排在兜底之前。 */
    @Test
    void registryOrderIsSignificant() {
        // azure 的 reasoning 变体在前，普通 azure 在后（gpt-5 命中前者，gpt-4 落到后者）
        assertEquals(ProviderAdapters.AzureReasoning.class,
                ProviderAdapters.resolve(ProviderName.AZURE_OPEN_AI, "gpt-5").getClass());
        assertEquals(ProviderAdapters.Azure.class,
                ProviderAdapters.resolve(ProviderName.AZURE_OPEN_AI, "gpt-4").getClass());
        // 带谓词的适配器排在兜底前：qwen3 命中 QwenThinking，qwen2 落回 BaseProvider
        assertEquals(ProviderAdapters.QwenThinking.class,
                ProviderAdapters.resolve(ProviderName.ALIYUN, "qwen3-32b").getClass());
        assertEquals(BaseProvider.class,
                ProviderAdapters.resolve(ProviderName.ALIYUN, "qwen2-72b").getClass());
        // moonshot-v1 命中固定温度变体，kimi-k2 落回 BaseProvider
        assertEquals(ProviderAdapters.Moonshot.class,
                ProviderAdapters.resolve(ProviderName.MOONSHOT, "moonshot-v1-128k").getClass());
        assertEquals(BaseProvider.class,
                ProviderAdapters.resolve(ProviderName.MOONSHOT, "kimi-k2-turbo").getClass());
        // LKEAP 只认 deepseek-v3（R1 默认开思维链，保持不动）
        assertEquals(ProviderAdapters.Lkeap.class,
                ProviderAdapters.resolve(ProviderName.LKEAP, "deepseek-v3").getClass());
        assertEquals(BaseProvider.class,
                ProviderAdapters.resolve(ProviderName.LKEAP, "deepseek-r1-0528").getClass());
    }

    // ------------------------------------------------------------------
    // 对照 Go newOutboundChat + TestBuildOutbound_Thinking
    // ------------------------------------------------------------------

    private static RemoteApiChat newOutboundChat(String providerName, String model, Map<String, String> extra) {
        ChatConfig config = new ChatConfig();
        config.setSource("remote");
        config.setModelName(model);
        config.setApiKey("k");
        config.setModelId(model);
        config.setProvider(providerName);
        config.setExtraConfig(extra);
        if (ProviderName.WEKNORA_CLOUD.value().equals(providerName)) {
            // 该厂商的构造期硬校验：AppID/AppSecret 必填（对照 Go NewRemoteAPIChat）
            config.setAppId("app-id");
            config.setAppSecret("app-secret");
        }
        return new RemoteApiChat(config);
    }

    private static List<ChatMessage> hi() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("hi"));
        return messages;
    }

    private static JsonNode outbound(RemoteApiChat chat, ChatOptions opts, boolean isStream) {
        return chat.buildOutbound(hi(), opts, isStream, null).body();
    }

    private static ChatOptions thinking(Boolean value) {
        ChatOptions opts = new ChatOptions();
        opts.setThinking(value);
        return opts;
    }

    @Test
    void buildOutboundThinking() {
        // 显式 thinking_type 覆盖历史 kwargs 默认
        RemoteApiChat generic = newOutboundChat("generic", "deepseek-v4-flash",
                Map.of("thinking_control", "thinking_type"));
        JsonNode body = outbound(generic, thinking(false), true);
        assertTrue(body.has("thinking"));
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertFalse(body.has("chat_template_kwargs"));

        // 未指定 thinking_control → 沿用 adapter 的 chat_template_kwargs
        RemoteApiChat legacy = newOutboundChat("generic", "qwen", null);
        JsonNode legacyBody = outbound(legacy, thinking(false), true);
        assertTrue(legacyBody.has("chat_template_kwargs"));
        assertFalse(legacyBody.path("chat_template_kwargs").path("enable_thinking").asBoolean());

        // thinking_control = none → 干净的请求体（Go 里对应 useRaw == false）
        RemoteApiChat none = newOutboundChat("generic", "x", Map.of("thinking_control", "none"));
        JsonNode noneBody = outbound(none, thinking(false), true);
        assertFalse(noneBody.has("chat_template_kwargs"));
        assertFalse(noneBody.has("enable_thinking"));
        assertFalse(noneBody.has("thinking"));

        // Qwen 非流式：强制 enable_thinking=false
        RemoteApiChat qwen = newOutboundChat("aliyun", "qwen3-32b", null);
        JsonNode qwenNonStream = outbound(qwen, thinking(true), false);
        assertFalse(qwenNonStream.path("enable_thinking").asBoolean());

        // Qwen 流式：尊重入参 true
        JsonNode qwenStream = outbound(qwen, thinking(true), true);
        assertTrue(qwenStream.path("enable_thinking").asBoolean());

        // Qwen thinking 模型即使没传 thinking，也固定发 enable_thinking（alwaysSend）
        JsonNode qwenDefault = outbound(qwen, new ChatOptions(), true);
        assertTrue(qwenDefault.has("enable_thinking"));
        assertFalse(qwenDefault.path("enable_thinking").asBoolean());

        // 火山引擎
        RemoteApiChat volc = newOutboundChat("volcengine", "doubao", null);
        JsonNode volcBody = outbound(volc, thinking(true), true);
        assertEquals("enabled", volcBody.path("thinking").path("type").asText());

        // LKEAP DeepSeek V3
        RemoteApiChat lkeap = newOutboundChat("lkeap", "deepseek-v3.1", null);
        assertTrue(outbound(lkeap, thinking(false), true).has("thinking"));

        // LKEAP R1 保持不动（baseProvider，无 thinking 字段）
        RemoteApiChat r1 = newOutboundChat("lkeap", "deepseek-r1", null);
        JsonNode r1Body = outbound(r1, thinking(false), true);
        assertFalse(r1Body.has("thinking"));
        assertFalse(r1Body.has("enable_thinking"));
        assertFalse(r1Body.has("chat_template_kwargs"));
    }

    // ------------------------------------------------------------------
    // 对照 Go TestBuildOutbound_ShapeRequest
    // ------------------------------------------------------------------

    @Test
    void buildOutboundShapeRequest() {
        // DeepSeek 剥掉 tool_choice
        RemoteApiChat deepseek = newOutboundChat("deepseek", "deepseek-chat", null);
        ChatOptions withToolChoice = new ChatOptions();
        withToolChoice.setToolChoice("auto");
        JsonNode deepseekBody = outbound(deepseek, withToolChoice, false);
        assertFalse(deepseekBody.has("tool_choice"));

        // Moonshot 钉死 temperature=1 并丢掉其它采样参数
        RemoteApiChat moonshot = newOutboundChat("moonshot", "moonshot-v1-8k", null);
        ChatOptions sampling = new ChatOptions();
        sampling.setTemperature(0.7);
        sampling.setTopP(0.9);
        JsonNode moonshotBody = outbound(moonshot, sampling, false);
        assertEquals(1, moonshotBody.path("temperature").asInt());
        assertFalse(moonshotBody.has("top_p"));
    }

    // ------------------------------------------------------------------
    // 对照 Go TestBuildOutbound_GeminiProviderMetadata
    // ------------------------------------------------------------------

    @Test
    void buildOutboundGeminiProviderMetadata() throws Exception {
        RemoteApiChat gemini = newOutboundChat("gemini", "gemini-3-flash-preview", null);

        ToolCall call = new ToolCall();
        call.setId("call_1");
        call.setType("function");
        call.getFunction().setName("wiki_search");
        call.getFunction().setArguments("{\"query\":\"MACS\"}");
        Map<String, JsonNode> metadata = new LinkedHashMap<>();
        metadata.put("google", MAPPER.readTree("{\"thought_signature\":\"gemini-signature\"}"));
        call.setProviderMetadata(metadata);

        ChatMessage assistant = new ChatMessage("assistant", "");
        assistant.setToolCalls(List.of(call));
        List<ChatMessage> messages = List.of(ChatMessage.user("find docs"), assistant);

        JsonNode body = gemini.buildOutbound(messages, new ChatOptions(), false, null).body();
        JsonNode toolCall = body.path("messages").get(1).path("tool_calls").get(0);
        assertEquals("gemini-signature",
                toolCall.path("extra_content").path("google").path("thought_signature").asText());
    }

    /** 补测：非 gemini 适配器不得往 tool_call 里写 extra_content。 */
    @Test
    void nonGeminiAdaptersDoNotInjectMetadata() throws Exception {
        RemoteApiChat openai = newOutboundChat("openai", "gpt-4o", null);
        ToolCall call = new ToolCall();
        call.setId("call_1");
        call.getFunction().setName("wiki_search");
        call.getFunction().setArguments("{}");
        Map<String, JsonNode> metadata = new LinkedHashMap<>();
        metadata.put("google", MAPPER.readTree("{\"thought_signature\":\"sig\"}"));
        call.setProviderMetadata(metadata);
        ChatMessage assistant = new ChatMessage("assistant", "");
        assistant.setToolCalls(List.of(call));

        JsonNode body = openai.buildOutbound(List.of(assistant), new ChatOptions(), false, null).body();
        assertFalse(body.path("messages").get(0).path("tool_calls").get(0).has("extra_content"));
    }

    /** 补测：Gemini 的 extra_content 抽取（含边界：没有 google 键 / extra_content 非对象）。 */
    @Test
    void geminiMetadataExtraction() throws Exception {
        ProviderAdapters.Gemini gemini = new ProviderAdapters.Gemini();
        assertNull(gemini.extractToolCallMetadata(MAPPER.readTree("{\"id\":\"1\"}")));
        assertNull(gemini.extractToolCallMetadata(MAPPER.readTree("{\"extra_content\":null}")));
        assertNull(gemini.extractToolCallMetadata(MAPPER.readTree("{\"extra_content\":{\"other\":1}}")));
        assertEquals("sig", gemini.extractToolCallMetadata(
                        MAPPER.readTree("{\"extra_content\":{\"google\":{\"thought_signature\":\"sig\"}}}"))
                .get("google").path("thought_signature").asText());
        // 显式 null 在 Go 里是 RawMessage("null")（非空）→ 仍然往返
        Map<String, JsonNode> nullGoogle = gemini.extractToolCallMetadata(
                MAPPER.readTree("{\"extra_content\":{\"google\":null}}"));
        assertTrue(nullGoogle.get("google").isNull());

        ObjectNode toolCall = MAPPER.createObjectNode();
        gemini.injectToolCallMetadata(toolCall, null);
        gemini.injectToolCallMetadata(toolCall, Map.of());
        assertFalse(toolCall.has("extra_content"));
        gemini.injectToolCallMetadata(toolCall, Map.of("google", MAPPER.readTree("{\"a\":1}")));
        assertEquals(1, toolCall.path("extra_content").path("google").path("a").asInt());
    }

    /**
     * 验收项：**全 provider 表下出站恰好一个 token 字段**（Issue #3014：同时带
     * max_tokens 与 max_completion_tokens 会被火山 Ark 之类的网关直接拒绝）。
     */
    @Test
    void exactlyOneTokenFieldForEveryProvider() {
        List<ProviderName> providers = new ArrayList<>(List.of(ProviderName.values()));
        assertFalse(providers.isEmpty());

        for (ProviderName provider : providers) {
            RemoteApiChat chat = newOutboundChat(provider.value(), "some-model", null);
            ChatOptions opts = new ChatOptions();
            opts.setMaxTokens(321);
            opts.setMaxCompletionTokens(654);

            JsonNode body = chat.buildOutbound(hi(), opts, false, null).body();
            boolean hasMaxTokens = body.hasNonNull("max_tokens");
            boolean hasMaxCompletion = body.hasNonNull("max_completion_tokens");
            assertTrue(hasMaxTokens ^ hasMaxCompletion,
                    provider.value() + ": 出站必须恰好一个 token 字段");
            assertEquals(654, (hasMaxTokens ? body.get("max_tokens") : body.get("max_completion_tokens")).asInt(),
                    provider.value() + ": MaxCompletionTokens 优先于 MaxTokens（同一个预算）");

            // 无预算时不写任何 token 字段
            JsonNode noBudget = chat.buildOutbound(hi(), new ChatOptions(), false, null).body();
            assertFalse(noBudget.hasNonNull("max_tokens"), provider.value() + ": 预算为 0 时不写");
            assertFalse(noBudget.hasNonNull("max_completion_tokens"), provider.value() + ": 预算为 0 时不写");
        }

        // 老字段用例：只在文档只认 max_tokens 的厂商上出现
        assertTrue(newOutboundChat("deepseek", "deepseek-chat", null)
                .buildOutbound(hi(), budget(100), false, null).body().hasNonNull("max_tokens"));
        assertTrue(newOutboundChat("openai", "gpt-4o", null)
                .buildOutbound(hi(), budget(100), false, null).body().hasNonNull("max_completion_tokens"));
    }

    private static ChatOptions budget(int value) {
        ChatOptions opts = new ChatOptions();
        opts.setMaxTokens(value);
        return opts;
    }

    /** 补测：WeKnoraCloud 的签名头齐全且签名可复算（对照 signer.go 的 X-Signature）。 */
    @Test
    void weKnoraCloudSignsRequest() {
        Map<String, String> headers = ProviderAdapters.WeKnoraCloud.sign(
                "app-id", "secret", "req-1", "{\"a\":1}");
        assertEquals("app-id", headers.get("X-APPID"));
        assertEquals("secret", headers.get("X-API-Key"));
        assertEquals("req-1", headers.get("X-Request-ID"));
        assertTrue(headers.get("X-Timestamp").matches("^\\d+$"));
        assertEquals(16, headers.get("X-Nonce").length());
        assertEquals(32, headers.get("X-Signature").length(), "MD5 十六进制");

        // 签名 = md5(排序后的 k=v 的 & 拼接)；用同一算法复算一遍（与 Go 的 signer.go 一致）
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String bodyMd5 = java.util.HexFormat.of().formatHex(
                digest.digest("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String canonical = "body=" + bodyMd5
                + "&x-api-key=secret&x-appid=app-id&x-nonce=" + headers.get("X-Nonce")
                + "&x-request-id=req-1&x-timestamp=" + headers.get("X-Timestamp");
        digest.reset();
        String expected = java.util.HexFormat.of().formatHex(
                digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected, headers.get("X-Signature"));

        // endpoint 覆写 + 空 body 按 "{}" 参与摘要
        ProviderAdapters.WeKnoraCloud adapter = new ProviderAdapters.WeKnoraCloud();
        assertEquals("https://cloud.example.com/api/v1/chat/completions",
                adapter.endpoint("https://cloud.example.com/", "m", true));
    }
}
