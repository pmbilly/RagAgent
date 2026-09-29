package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.CacheRetention;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.PromptCacheStatus;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.provider.ProviderName;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * 对照 Go internal/models/chat/prompt_cache_apply_test.go
 * （TestClampPromptCacheKey / TestApplyPromptCacheToJSONBody_* / TestAttachPromptCacheHeaders），
 * 并按 prompt_cache.go 补齐 Go 侧由 remote_api_test.go 覆盖的指纹与缓存账目断言。
 *
 * <p>与 Go 测试的对应差异：Go 用 {@code openai.ChatCompletionRequest} 做入参再 marshal，
 * Java 直接构造等价的请求体 {@link ObjectNode}（同一份线格式）。</p>
 */
class PromptCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode body(String model) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        return body;
    }

    /** 对照 Go TestClampPromptCacheKey */
    @Test
    void clampPromptCacheKey() {
        assertEquals("", PromptCache.clampPromptCacheKey(""));
        assertEquals("sess-1", PromptCache.clampPromptCacheKey("sess-1"));
        String longKey = "a".repeat(80);
        String got = PromptCache.clampPromptCacheKey(longKey);
        assertEquals(64, got.codePointCount(0, got.length()));
        assertEquals("a".repeat(64), got);
    }

    /** 补测：截断按 rune（code point）而不是 UTF-16 char，多字节字符不能被劈成半个 */
    @Test
    void clampPromptCacheKeyCountsRunes() {
        String cjk = "世".repeat(80);
        String got = PromptCache.clampPromptCacheKey(cjk);
        assertEquals(64, got.codePointCount(0, got.length()));
        assertEquals(64, got.length()); // 64 个 BMP 字符 = 64 个 char
        assertEquals("世".repeat(64), got);

        String emoji = "🌏".repeat(80); // 代理对：1 code point / 2 char
        String emojiGot = PromptCache.clampPromptCacheKey(emoji);
        assertEquals(64, emojiGot.codePointCount(0, emojiGot.length()));
        assertEquals(128, emojiGot.length(), "64 个代理对 = 128 个 char");
        assertEquals("🌏".repeat(64), emojiGot);
    }

    /** 对照 Go TestApplyPromptCacheToJSONBody_OpenAIKey */
    @Test
    void applyPromptCacheToJsonBodyOpenAiKey() {
        ObjectNode body = body("gpt-4o");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", "sys");
        messages.addObject().put("role", "user").put("content", "hi");

        PromptCache.Policy policy = PromptCache.promptCachePolicyFor(ProviderName.OPENAI, "");
        boolean rewritten = PromptCache.applyPromptCacheToJSONBody(body, policy, "sess-abc", CacheRetention.SHORT);

        assertTrue(rewritten, "forceRaw 必须为真（Go 里 rewritten ⟺ forceRaw）");
        assertEquals("sess-abc", body.get("prompt_cache_key").asText());
        assertFalse(body.has("prompt_cache_retention"));
    }

    /** 对照 Go TestApplyPromptCacheToJSONBody_OpenAILongRetention */
    @Test
    void applyPromptCacheToJsonBodyOpenAiLongRetention() {
        ObjectNode body = body("gpt-4o");
        PromptCache.Policy policy = PromptCache.promptCachePolicyFor(ProviderName.OPENAI, "");
        boolean rewritten = PromptCache.applyPromptCacheToJSONBody(body, policy, "sess-abc", CacheRetention.LONG);

        assertTrue(rewritten);
        assertEquals("24h", body.get("prompt_cache_retention").asText());
    }

    /** 对照 Go TestApplyPromptCacheToJSONBody_NoneLeavesBodyUntouched */
    @Test
    void applyPromptCacheToJsonBodyNoneLeavesBodyUntouched() {
        ObjectNode body = body("gpt-4o");
        PromptCache.Policy policy = PromptCache.promptCachePolicyFor(ProviderName.OPENAI, "");
        boolean rewritten = PromptCache.applyPromptCacheToJSONBody(body, policy, "sess-abc", CacheRetention.NONE);

        assertFalse(rewritten);
        // body 一字未动（Go 里返回原 body 对象本身）
        assertEquals("gpt-4o", body.get("model").asText());
        assertEquals(1, body.size());
        assertFalse(body.has("prompt_cache_key"));
    }

    /**
     * 对照 Go TestApplyPromptCacheToJSONBody_AliyunCacheControlBreakpoints：
     * system 指令消息、最后一条对话消息、最后一个 tool 三处都要挂上 cache_control。
     */
    @Test
    void applyPromptCacheToJsonBodyAliyunCacheControlBreakpoints() {
        ObjectNode body = body("qwen-plus");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", "stable system");
        messages.addObject().put("role", "user").put("content", "turn question");
        ArrayNode tools = body.putArray("tools");
        tools.addObject().put("type", "function").putObject("function")
                .put("name", "search").put("description", "d");

        PromptCache.Policy policy = PromptCache.promptCachePolicyFor(ProviderName.ALIYUN, "");
        boolean rewritten = PromptCache.applyPromptCacheToJSONBody(body, policy, "", CacheRetention.SHORT);
        assertTrue(rewritten);

        JsonNode sys = body.get("messages").get(0);
        JsonNode sysParts = sys.get("content");
        assertEquals("ephemeral", sysParts.get(0).get("cache_control").get("type").asText());
        assertEquals("stable system", sysParts.get(0).get("text").asText());

        JsonNode user = body.get("messages").get(1);
        JsonNode userParts = user.get("content");
        assertEquals("ephemeral", userParts.get(0).get("cache_control").get("type").asText());

        JsonNode lastTool = body.get("tools").get(body.get("tools").size() - 1);
        assertEquals("ephemeral", lastTool.get("cache_control").get("type").asText());
    }

    /** LONG 保留期时，cache_control 带上 1h TTL（对照 Go cacheControlFor(retention, "1h")）。 */
    @Test
    void applyPromptCacheToJsonBodyLongRetentionAddsTtl() {
        ObjectNode body = body("qwen-plus");
        body.putArray("messages").addObject().put("role", "user").put("content", "hi");
        PromptCache.Policy policy = PromptCache.promptCachePolicyFor(ProviderName.ALIYUN, "");
        PromptCache.applyPromptCacheToJSONBody(body, policy, "", CacheRetention.LONG);

        JsonNode part = body.get("messages").get(0).get("content").get(0);
        assertEquals("ephemeral", part.get("cache_control").get("type").asText());
        assertEquals("1h", part.get("cache_control").get("ttl").asText());
    }

    /** 数组形态的 content：断点落在最后一个 text/tool_result part 上（不是第一个）。 */
    @Test
    void cacheControlBreakpointTargetsLastTextPart() {
        ObjectNode body = body("qwen-plus");
        ArrayNode messages = body.putArray("messages");
        ArrayNode parts = messages.addObject().put("role", "user").putArray("content");
        parts.addObject().put("type", "text").put("text", "first");
        parts.addObject().put("type", "image_url").putObject("image_url").put("url", "data:x");
        parts.addObject().put("type", "text").put("text", "last");

        PromptCache.Policy policy = PromptCache.promptCachePolicyFor(ProviderName.ALIYUN, "");
        PromptCache.applyPromptCacheToJSONBody(body, policy, "", CacheRetention.SHORT);

        assertFalse(body.get("messages").get(0).get("content").get(0).has("cache_control"));
        assertFalse(body.get("messages").get(0).get("content").get(1).has("cache_control"));
        assertEquals("ephemeral",
                body.get("messages").get(0).get("content").get(2).get("cache_control").get("type").asText());
    }

    /** 对照 Go TestAttachPromptCacheHeaders */
    @Test
    void attachPromptCacheHeaders() {
        HttpHeaders headers = new HttpHeaders();
        PromptCache.attachPromptCacheHeaders(headers, new PromptCache.Policy(false, false, true), "sess-1");
        assertEquals("sess-1", headers.getFirst("session_id"));
        assertEquals("sess-1", headers.getFirst("x-client-request-id"));
        assertEquals("sess-1", headers.getFirst("x-session-affinity"));
    }

    /** 只有 sendAffinity 的厂商才设头；sessionID 为空不设。 */
    @Test
    void attachPromptCacheHeadersSkipsWhenNotAffinity() {
        HttpHeaders headers = new HttpHeaders();
        PromptCache.attachPromptCacheHeaders(headers,
                PromptCache.promptCachePolicyFor(ProviderName.ALIYUN, ""), "sess-1");
        assertNull(headers.getFirst("session_id"));

        PromptCache.attachPromptCacheHeaders(headers, new PromptCache.Policy(false, false, true), "");
        assertNull(headers.getFirst("session_id"));
    }

    /** 策略分叉逐条对齐 Go promptCachePolicyFor。 */
    @Test
    void promptCachePolicyForBranches() {
        assertEquals(new PromptCache.Policy(true, false, true),
                PromptCache.promptCachePolicyFor(ProviderName.OPENAI, ""));
        assertEquals(new PromptCache.Policy(true, false, true),
                PromptCache.promptCachePolicyFor(ProviderName.AZURE_OPEN_AI, ""));
        assertEquals(new PromptCache.Policy(true, false, true),
                PromptCache.promptCachePolicyFor(ProviderName.OPENROUTER, ""));
        assertEquals(new PromptCache.Policy(false, true, false),
                PromptCache.promptCachePolicyFor(ProviderName.ALIYUN, ""));
        assertEquals(new PromptCache.Policy(false, true, false),
                PromptCache.promptCachePolicyFor(ProviderName.ANTHROPIC, ""));
        // 其它厂商命中 api.openai.com 时按 OpenAI 处理
        assertEquals(new PromptCache.Policy(true, false, true),
                PromptCache.promptCachePolicyFor(ProviderName.GENERIC, "https://api.openai.com/v1"));
        assertEquals(PromptCache.Policy.NONE,
                PromptCache.promptCachePolicyFor(ProviderName.GENERIC, "https://example.com/v1"));
    }

    /**
     * 对照 Go TestBuildOutbound_OpenAIPromptCacheKeyFromSession 的缓存部分：
     * 会话 ID 从调用上下文取出后注入 prompt_cache_key。
     */
    @Test
    void promptCacheKeyFromSession() {
        ChatOptions opts = new ChatOptions();
        String sessionId = PromptCache.promptCacheSessionID("sess-live", opts);
        assertEquals("sess-live", sessionId);

        ObjectNode body = body("gpt-4o");
        PromptCache.Policy policy = PromptCache.promptCachePolicyFor(ProviderName.OPENAI, "");
        assertTrue(PromptCache.applyPromptCacheToJSONBody(body, policy, sessionId, PromptCache.resolveCacheRetention(opts)));
        assertEquals("sess-live", body.get("prompt_cache_key").asText());
    }

    /** opts.promptCacheKey 优先于上下文 session ID，且两者都过 64 截断。 */
    @Test
    void promptCacheSessionIdPrecedence() {
        ChatOptions opts = new ChatOptions();
        opts.setPromptCacheKey("explicit-key");
        assertEquals("explicit-key", PromptCache.promptCacheSessionID("sess-live", opts));

        opts.setPromptCacheKey("k".repeat(80));
        assertEquals("k".repeat(64), PromptCache.promptCacheSessionID("sess-live", opts));

        opts.setPromptCacheKey("");
        assertEquals("s".repeat(64), PromptCache.promptCacheSessionID("s".repeat(80), opts));

        assertEquals("", PromptCache.promptCacheSessionID(null, new ChatOptions()));
        assertEquals("", PromptCache.promptCacheSessionID("", new ChatOptions()));
        assertEquals(CacheRetention.SHORT, PromptCache.resolveCacheRetention(null));
        assertEquals(CacheRetention.SHORT, PromptCache.resolveCacheRetention(new ChatOptions()));
        opts.setCacheRetention(CacheRetention.LONG);
        assertEquals(CacheRetention.LONG, PromptCache.resolveCacheRetention(opts));
    }

    /**
     * 指纹：SHA-256 前 16 个 hex，逐段写入并各自带一个 NUL 分隔符。
     * 黄金值由 Go 侧同源实现（crypto/sha256 + hex + [:16]）实算得出。
     */
    @Test
    void fingerprintPromptPrefixGolden() {
        assertEquals("6e340b9cffb37a98", PromptCache.fingerprintPromptPrefix(""));
        assertEquals("ffe9aaeaa2a2d504", PromptCache.fingerprintPromptPrefix("a"));
        // 分隔符防拼接歧义：("a","b") 与 ("ab") 的结果必须不同
        assertEquals("8fb20ef63ced4145", PromptCache.fingerprintPromptPrefix("a", "b"));
        assertEquals("dc1114cd074914bd", PromptCache.fingerprintPromptPrefix("abc"));
        assertEquals("dcbca2c9ac488a84",
                PromptCache.fingerprintPromptPrefix("1", "gpt-4o", "chat", "0123456789abcdef"));
        assertEquals(16, PromptCache.fingerprintPromptPrefix("a").length());
    }

    /** buildPromptCacheKey 形如 wk-<16hex>，tenantID 按 uint64 无符号十进制参与。 */
    @Test
    void buildPromptCacheKeyShape() {
        String key = PromptCache.buildPromptCacheKey(1L, "gpt-4o", "chat", "0123456789abcdef");
        assertEquals("wk-dcbca2c9ac488a84", key);
        // Go 的 uint64 语义：高位为 1 的租户 ID 必须按无符号十进制参与哈希，不能编码成负数
        assertEquals("wk-" + PromptCache.fingerprintPromptPrefix(
                        "18446744073709551615", "m", "p", "f"),
                PromptCache.buildPromptCacheKey(-1L, "m", "p", "f"));
    }

    /**
     * PromptPrefixFingerprint 的序列化契约：只含开头的 system 消息与 tools，
     * 字段序 system,tools，空集省略——黄金 JSON 取自 Go struct 的 marshal 结果。
     */
    @Test
    void promptPrefixFingerprintSerializationContract() {
        ChatMessage system = ChatMessage.system("sys");
        ChatOptions opts = new ChatOptions();
        ChatTool tool = new ChatTool("search", "d", MAPPER.createObjectNode());
        opts.setTools(List.of(tool));

        String systemOnlyJson = "{\"system\":[{\"role\":\"system\",\"content\":\"sys\"}]}";
        assertEquals(PromptCache.fingerprintPromptPrefix(systemOnlyJson),
                PromptCache.promptPrefixFingerprint(List.of(system), new ChatOptions()));
        assertEquals("725c673c25d27d0c", PromptCache.promptPrefixFingerprint(List.of(system), new ChatOptions()));

        // 带 tools 时字段序仍是 system,tools（黄金值同样由 Go 侧 marshal 后实算）
        String withToolsJson = "{\"system\":[{\"role\":\"system\",\"content\":\"sys\"}],"
                + "\"tools\":[{\"type\":\"function\",\"function\":"
                + "{\"name\":\"search\",\"description\":\"d\",\"parameters\":{}}}]}";
        assertEquals(PromptCache.fingerprintPromptPrefix(withToolsJson),
                PromptCache.promptPrefixFingerprint(List.of(system), opts));
        assertEquals("c694944b4fce8140", PromptCache.promptPrefixFingerprint(List.of(system), opts));

        // 首条非 system 消息之后的一切都不参与（动态对话/用户消息不参与指纹）
        assertEquals(PromptCache.promptPrefixFingerprint(List.of(system), new ChatOptions()),
                PromptCache.promptPrefixFingerprint(
                        List.of(system, ChatMessage.user("dynamic turn")), new ChatOptions()));
        // 非 system 打头时 system 数组为空 → 整个键省略
        assertEquals(PromptCache.fingerprintPromptPrefix("{}"),
                PromptCache.promptPrefixFingerprint(List.of(ChatMessage.user("hi")), new ChatOptions()));
    }

    /** 对照 Go tokenUsageFromOpenAI 的三个分支。 */
    @Test
    void tokenUsageFromOpenAi() {
        JsonNode withDetails = parse("""
                {"promptTokens":4096,"completionTokens":10,"totalTokens":4106,
                 "prompt_tokens_details":{"cachedTokens":3072}}""");
        TokenUsage u = PromptCache.tokenUsageFromOpenAI(withDetails, ProviderName.OPENAI);
        assertEquals(4096, u.getPromptTokens());
        assertEquals(3072, u.getCacheReadTokens());
        assertEquals(1024, u.getCacheMissTokens());
        assertEquals(PromptCacheStatus.HIT, u.getCacheStatus());

        // 无 details 且厂商走缓存上报通道 → UNREPORTED
        TokenUsage unreported = PromptCache.tokenUsageFromOpenAI(
                parse("{\"promptTokens\":5,\"totalTokens\":5}"), ProviderName.OPENAI);
        assertEquals(PromptCacheStatus.UNREPORTED, unreported.getCacheStatus());
        assertFalse(unreported.isCacheReported());

        // 无 details 且厂商根本不上报 → UNSUPPORTED
        TokenUsage unsupported = PromptCache.tokenUsageFromOpenAI(
                parse("{\"promptTokens\":5,\"totalTokens\":5}"), ProviderName.ZHIPU);
        assertEquals(PromptCacheStatus.UNSUPPORTED, unsupported.getCacheStatus());

        // usage 缺失时按 Go 的零值 Usage 处理
        TokenUsage empty = PromptCache.tokenUsageFromOpenAI(null, ProviderName.OPENAI);
        assertEquals(0, empty.getPromptTokens());
        assertEquals(PromptCacheStatus.UNREPORTED, empty.getCacheStatus());
    }

    /** 对照 Go cachedTokens 的 nil-safe 语义。 */
    @Test
    void cachedTokensNilSafe() {
        assertEquals(0, PromptCache.cachedTokens(null));
        assertEquals(0, PromptCache.cachedTokens(parse("{}")));
        assertEquals(1234, PromptCache.cachedTokens(parse("{\"cachedTokens\":1234}")));
    }

    /** 对照 remote_api_test.go:526 的 DeepSeek hit/miss 抓取。 */
    @Test
    void applyRawPromptCacheUsageDeepSeek() {
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(4096);
        PromptCache.applyRawPromptCacheUsage(
                "{\"usage\":{\"promptTokens\":4096,\"prompt_cache_hit_tokens\":3072,\"prompt_cache_miss_tokens\":1024}}",
                usage);
        assertEquals(3072, usage.getCacheReadTokens());
        assertEquals(1024, usage.getCacheMissTokens());
        assertEquals(PromptCacheStatus.HIT, usage.getCacheStatus());
        assertTrue(usage.isCacheReported());
    }

    /** Anthropic 的 read/creation 字段：miss 由入参的 promptTokens 推出。 */
    @Test
    void applyRawPromptCacheUsageAnthropic() {
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(1000);
        PromptCache.applyRawPromptCacheUsage(
                "{\"usage\":{\"cache_read_input_tokens\":600,\"cache_creation_input_tokens\":100}}", usage);
        assertEquals(600, usage.getCacheReadTokens());
        assertEquals(100, usage.getCacheWriteTokens());
        assertEquals(400, usage.getCacheMissTokens());
    }

    /** prompt_tokens_details 分支；纯文本（无 usage）与坏 JSON 一律静默返回。 */
    @Test
    void applyRawPromptCacheUsageFallbacks() {
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(100);
        PromptCache.applyRawPromptCacheUsage(
                "{\"usage\":{\"prompt_tokens_details\":{\"cachedTokens\":80,\"cacheWriteTokens\":5}}}", usage);
        assertEquals(80, usage.getCacheReadTokens());
        assertEquals(5, usage.getCacheWriteTokens());
        assertEquals(20, usage.getCacheMissTokens());

        // 坏 JSON / 空串 / 无 usage → 静默返回，一个字段都不动（Go 的零值 status = ""）
        TokenUsage untouched = new TokenUsage();
        PromptCache.applyRawPromptCacheUsage("not json", untouched);
        PromptCache.applyRawPromptCacheUsage("", untouched);
        PromptCache.applyRawPromptCacheUsage(null, untouched);
        PromptCache.applyRawPromptCacheUsage("{\"usage\":{}}", untouched);
        assertEquals(0, untouched.getCacheReadTokens());
        assertFalse(untouched.isCacheReported());
        assertNull(untouched.getCacheStatus());
    }

    /** providerCacheAccountingStatus 的厂商白名单。 */
    @Test
    void providerCacheAccountingStatusMapping() {
        for (ProviderName name : List.of(ProviderName.OPENAI, ProviderName.AZURE_OPEN_AI,
                ProviderName.DEEPSEEK, ProviderName.ALIYUN, ProviderName.ANTHROPIC)) {
            assertEquals(PromptCacheStatus.UNREPORTED, PromptCache.providerCacheAccountingStatus(name));
        }
        assertEquals(PromptCacheStatus.UNSUPPORTED,
                PromptCache.providerCacheAccountingStatus(ProviderName.ZHIPU));
        assertEquals(PromptCacheStatus.UNSUPPORTED,
                PromptCache.providerCacheAccountingStatus(ProviderName.GENERIC));
    }

    /** cacheControlFor：NONE → null；LONG + longTTL → 带 TTL。 */
    @Test
    void cacheControlForBranches() {
        assertNull(PromptCache.cacheControlFor(CacheRetention.NONE, "1h"));
        assertNotNull(PromptCache.cacheControlFor(CacheRetention.SHORT, "1h"));
        assertNull(PromptCache.cacheControlFor(CacheRetention.SHORT, "1h").ttl());
        assertEquals("1h", PromptCache.cacheControlFor(CacheRetention.LONG, "1h").ttl());
        assertNull(PromptCache.cacheControlFor(CacheRetention.LONG, "").ttl());
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
