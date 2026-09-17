package com.ragagent.llm.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.CacheRetention;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.PromptCacheStatus;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.provider.ProviderName;

import org.springframework.http.HttpHeaders;

/**
 * prompt 缓存路由与账目归一化（对照 Go internal/models/chat/prompt_cache.go 全文）。
 *
 * <p>三块职责，缺一不可：</p>
 * <ol>
 *   <li><b>指纹与 cache key</b>：SHA-256 前 16 个 hex 字符；原始 prompt 绝不做 metric label。
 *       指纹只覆盖"稳定的前缀"（开头的 system 消息 + 确定性工具 schema），动态的对话/用户消息
 *       刻意不参与——否则每轮对话的 key 都会漂。</li>
 *   <li><b>策略分叉</b>：OpenAI / Azure / OpenRouter（含 baseURL 命中 api.openai.com 的其它厂商）
 *       发 {@code prompt_cache_key}（+ 长保留期时 {@code prompt_cache_retention:"24h"}）；
 *       Aliyun / Anthropic 改发 content 上的 {@code cache_control} 断点；其余厂商什么都不发。</li>
 *   <li><b>缓存账目</b>：把各厂商五花八门的计数字段（DeepSeek 的 hit/miss、Anthropic 的
 *       read/creation、OpenAI 的 prompt_tokens_details）归一化进 {@link TokenUsage}。</li>
 * </ol>
 *
 * <p><b>Java 侧简化（与 {@link ThinkingStrategy} 同类，已在约定文档登记）</b>：Go 的
 * {@code applyPromptCacheToJSONBody} 返回 {@code (body, forceRaw)}——非 nil 的重写 body 必须走裸 HTTP，
 * 因为 go-openai SDK 的 struct 带不了 {@code prompt_cache_key} 这类字段。Java 侧统一用 Jackson
 * {@link ObjectNode} 构造并发送请求体，SDK 限制不存在，故返回值退化为布尔
 * {@code rewritten}（其值与 Go 的 {@code forceRaw} 恒等：Go 里 rewritten 为真时 forceRaw 必为真）。
 * <b>"注入与否、注入什么、注入在哪"逐条保持一致</b>——那才是线上行为。</p>
 */
public final class PromptCache {

    /** 对照 Go openAIPromptCacheKeyMaxLength。 */
    public static final int OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptCache() {
    }

    // ------------------------------------------------------------------
    // 指纹与 cache key（对照 prompt_cache.go:17-56）
    // ------------------------------------------------------------------

    /**
     * 对照 Go FingerprintPromptPrefix：短、不可逆的标识，用于日志与缓存路由。
     * 逐段写入 UTF-8 字节，每段后跟一个 NUL 分隔符（防 "ab"+"c" 与 "a"+"bc" 撞车）。
     */
    public static String fingerprintPromptPrefix(String... parts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        if (parts != null) {
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    /**
     * 对照 Go PromptPrefixFingerprint：哈希普通 chat 与 agent 请求共有的稳定前缀——
     * 开头的 system 消息 + 确定性 tool schema。动态的对话/用户消息不参与。
     *
     * <p>序列化契约：Go 是 {@code json.Marshal(struct{System []Message json:"system,omitempty";
     * Tools []Tool json:"tools,omitempty"})}，字段序 system,tools，空切片省略。
     * Java 用 {@link ObjectNode} 手工按同序拼装（Jackson 的 valueToTree 会走各 DTO 上的
     * {@code @JsonPropertyOrder}/omitempty 注解，与 Go struct 声明序一致）。</p>
     */
    public static String promptPrefixFingerprint(List<ChatMessage> messages, ChatOptions opts) {
        List<ChatMessage> system = new ArrayList<>();
        if (messages != null) {
            for (ChatMessage message : messages) {
                if (!"system".equals(message.getRole())) {
                    break;
                }
                system.add(message);
            }
        }
        ObjectNode prefix = MAPPER.createObjectNode();
        if (!system.isEmpty()) {
            ArrayNode systemNode = prefix.putArray("system");
            for (ChatMessage m : system) {
                systemNode.add(MAPPER.valueToTree(m));
            }
        }
        if (opts != null && opts.getTools() != null && !opts.getTools().isEmpty()) {
            prefix.set("tools", MAPPER.valueToTree(opts.getTools()));
        }
        return fingerprintPromptPrefix(stringify(prefix));
    }

    /**
     * 对照 Go BuildPromptCacheKey：派生的、不透明的进程内协调 key。
     * 租户与模型标识只参与哈希，不以明文驻留。
     */
    public static String buildPromptCacheKey(long tenantId, String modelId, String purpose, String prefixFingerprint) {
        // Go 的 tenantID 是 uint64；Java long 用无符号十进制表示，避免高位租户 ID 输出成负数
        return "wk-" + fingerprintPromptPrefix(
                Long.toUnsignedString(tenantId), modelId, purpose, prefixFingerprint);
    }

    // ------------------------------------------------------------------
    // 缓存账目（对照 prompt_cache.go:58-147）
    // ------------------------------------------------------------------

    /**
     * 对照 Go providerCacheAccountingStatus：该 provider 是否会走缓存上报通道。
     * 会上报但本次没上报 → UNREPORTED；根本不走该通道 → UNSUPPORTED。
     * 把两者都当成 0 会让全 fleet 命中率看板失真。
     */
    public static PromptCacheStatus providerCacheAccountingStatus(ProviderName name) {
        if (name == ProviderName.OPENAI
                || name == ProviderName.AZURE_OPEN_AI
                || name == ProviderName.DEEPSEEK
                || name == ProviderName.ALIYUN
                || name == ProviderName.ANTHROPIC) {
            return PromptCacheStatus.UNREPORTED;
        }
        return PromptCacheStatus.UNSUPPORTED;
    }

    /**
     * 对照 Go tokenUsageFromOpenAI。
     *
     * <p>Go 入参是 go-openai 的 {@code openai.Usage} 结构体；Java 侧没有该 SDK，入参换成
     * 同源的 {@code usage} JSON 对象（线格式一致）。{@code usage} 为 null 时按 Go 的零值
     * Usage 处理（PromptTokens=0、PromptTokensDetails=nil）。</p>
     *
     * <p>分叉：带了 prompt_tokens_details（非 nil）→ 以 cached_tokens 为已读，未读部分
     * = max(0, prompt-read)，reported=true；否则按 provider 的账目通道能力落到
     * UNSUPPORTED 或 UNREPORTED。</p>
     */
    public static TokenUsage tokenUsageFromOpenAI(JsonNode usage, ProviderName providerName) {
        TokenUsage u = new TokenUsage();
        u.setPromptTokens(intOrZero(usage, "prompt_tokens"));
        u.setCompletionTokens(intOrZero(usage, "completion_tokens"));
        u.setTotalTokens(intOrZero(usage, "total_tokens"));
        JsonNode details = field(usage, "prompt_tokens_details");
        if (details != null) {
            int read = cachedTokens(details);
            u.setPromptCacheUsage(read, 0, Math.max(0, u.getPromptTokens() - read), true);
            return u;
        }
        if (providerCacheAccountingStatus(providerName) == PromptCacheStatus.UNSUPPORTED) {
            u.markPromptCacheUnsupported();
        } else {
            u.setPromptCacheUsage(0, 0, 0, false);
        }
        return u;
    }

    /**
     * 对照 Go cachedTokens：nil-safe 的原始取值（老调用方与聚焦测试用）。
     * 归一化发生在 {@link #tokenUsageFromOpenAI}。
     */
    public static int cachedTokens(JsonNode promptTokensDetails) {
        if (promptTokensDetails == null) {
            return 0;
        }
        return intOrZero(promptTokensDetails, "cached_tokens");
    }

    /**
     * 对照 Go applyRawPromptCacheUsage：抓回被 OpenAI 兼容 SDK 丢掉的厂商原生字段
     * （典型是 DeepSeek 的 hit/miss 计数）。
     *
     * <p>优先级与 Go 逐条一致，且都是"命中即返回"：</p>
     * <ol>
     *   <li>prompt_cache_hit_tokens / prompt_cache_miss_tokens</li>
     *   <li>cache_read_input_tokens / cache_creation_input_tokens（缺省一方按 0；
     *       miss 由 max(0, usage.promptTokens - read) 推出——注意用的是<b>入参</b>
     *       TokenUsage 里已经填好的 promptTokens，不是原始 JSON 里的）</li>
     *   <li>prompt_tokens_details.cached_tokens / cache_write_tokens</li>
     * </ol>
     * JSON 解析失败、空串、usage 为 null 一律静默返回（Go 的 unmarshal 错误直接 return）。
     */
    public static void applyRawPromptCacheUsage(String rawJson, TokenUsage usage) {
        if (usage == null || rawJson == null || rawJson.isEmpty()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawJson);
        } catch (Exception e) {
            return; // 对照 Go：json.Unmarshal 失败 → 放弃
        }
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode usageNode = root.get("usage");
        if (usageNode == null || !usageNode.isObject()) {
            return;
        }

        JsonNode hit = field(usageNode, "prompt_cache_hit_tokens");
        JsonNode miss = field(usageNode, "prompt_cache_miss_tokens");
        if (hit != null || miss != null) {
            int read = valueOrZero(hit);
            int missTokens = valueOrZero(miss);
            usage.setPromptCacheUsage(read, 0, missTokens, true);
            return;
        }
        JsonNode cacheRead = field(usageNode, "cache_read_input_tokens");
        JsonNode cacheCreation = field(usageNode, "cache_creation_input_tokens");
        if (cacheRead != null || cacheCreation != null) {
            int read = valueOrZero(cacheRead);
            int write = valueOrZero(cacheCreation);
            usage.setPromptCacheUsage(read, write, Math.max(0, usage.getPromptTokens() - read), true);
            return;
        }
        JsonNode details = field(usageNode, "prompt_tokens_details");
        if (details != null) {
            int read = valueOrZero(field(details, "cached_tokens"));
            int write = valueOrZero(field(details, "cache_write_tokens"));
            usage.setPromptCacheUsage(read, write, Math.max(0, usage.getPromptTokens() - read), true);
        }
    }

    // ------------------------------------------------------------------
    // 策略、key 截断、retention（对照 prompt_cache.go:149-225）
    // ------------------------------------------------------------------

    /**
     * 对照 Go clampPromptCacheKey：按 <b>rune</b>（= Java code point）截断到 64，
     * 不能按 UTF-16 char 或字节截——后者会把多字节字符劈成半个。
     */
    public static String clampPromptCacheKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.codePointCount(0, key.length()) <= OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH) {
            return key;
        }
        return key.substring(0, key.offsetByCodePoints(0, OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH));
    }

    /**
     * 对照 Go resolveCacheRetention：opts 未指定（null 或空）时取 SHORT（厂商默认 5 分钟缓存）。
     * 压缩/摘要轮用 NONE，避免不同的 prompt 前缀去占本会话的缓存槽位。
     */
    public static CacheRetention resolveCacheRetention(ChatOptions opts) {
        CacheRetention retention = opts == null ? null : opts.getCacheRetention();
        return retention == null ? CacheRetention.SHORT : retention;
    }

    /**
     * 对照 Go promptCacheSessionID：优先调用方显式指定的 key，其次上下文里的 session ID，
     * 两者都要过 64 字符截断；都没有则返回空串（= 不注入）。
     *
     * <p>Go 从 {@code types.SessionIDFromContext(ctx)} 取值；Java 侧没有 ctx，
     * session ID 由调用方显式传入。</p>
     */
    public static String promptCacheSessionID(String contextSessionId, ChatOptions opts) {
        String explicit = opts == null ? null : opts.getPromptCacheKey();
        if (explicit != null && !explicit.isEmpty()) {
            return clampPromptCacheKey(explicit);
        }
        if (contextSessionId != null && !contextSessionId.isEmpty()) {
            return clampPromptCacheKey(contextSessionId);
        }
        return "";
    }

    /**
     * 对照 Go promptCachePolicyFor：厂商策略分叉。
     *
     * <p>OpenAI / Azure / OpenRouter 走顶层 key + 会话亲和头；Aliyun / Anthropic 走
     * content 断点；其它厂商若 baseURL 命中 api.openai.com（自建网关回代到 OpenAI）
     * 也按 OpenAI 处理；再不然什么都不发。</p>
     */
    public static Policy promptCachePolicyFor(ProviderName name, String baseUrl) {
        if (name == ProviderName.OPENAI
                || name == ProviderName.AZURE_OPEN_AI
                || name == ProviderName.OPENROUTER) {
            return new Policy(true, false, true);
        }
        if (name == ProviderName.ALIYUN) {
            return new Policy(false, true, false);
        }
        if (name == ProviderName.ANTHROPIC) {
            return new Policy(false, true, false);
        }
        if (baseUrl != null && baseUrl.contains("api.openai.com")) {
            return new Policy(true, false, true);
        }
        return Policy.NONE;
    }

    /** 对照 Go promptCachePolicy：一次请求的缓存路由动作。 */
    public record Policy(boolean sendKey, boolean sendCacheControl, boolean sendAffinity) {

        /** 不发任何缓存字段（Go 的零值 promptCachePolicy）。 */
        public static final Policy NONE = new Policy(false, false, false);
    }

    /** 对照 Go cacheControlMarker（{@code type} 恒输出，{@code ttl} 空则省略）。 */
    public record CacheControlMarker(String type, String ttl) {

        /** 序列化为 content/tool 上的 {@code cache_control} 对象。 */
        public ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", type);
            if (ttl != null && !ttl.isEmpty()) {
                node.put("ttl", ttl);
            }
            return node;
        }
    }

    /**
     * 对照 Go cacheControlFor：NONE 返回 null（= 不打断点）；LONG 且给了 longTTL 时带上 TTL。
     * Anthropic 路径也要用，故与 Go 一样保持包内可见以上（public）。
     */
    public static CacheControlMarker cacheControlFor(CacheRetention retention, String longTtl) {
        if (retention == CacheRetention.NONE) {
            return null;
        }
        String ttl = null;
        if (retention == CacheRetention.LONG && longTtl != null && !longTtl.isEmpty()) {
            ttl = longTtl;
        }
        return new CacheControlMarker("ephemeral", ttl);
    }

    // ------------------------------------------------------------------
    // 请求体注入（对照 prompt_cache.go:227-374）
    // ------------------------------------------------------------------

    /**
     * 对照 Go applyPromptCacheToJSONBody：往已经成形的 OpenAI 兼容请求体里注入缓存路由与断点。
     *
     * <p>返回值对应关系：Go 是 {@code (body, forceRaw)}，Java 是布尔 {@code rewritten}
     * （Go 里 rewritten 为真 ⟺ forceRaw 为真，见类注释）。调用方按"是否就地改了 body"使用即可。</p>
     *
     * <p>语义要点：NONE 或策略什么都不发时<b>原样返回</b>；命中 sendKey 但 sessionID 为空时
     * 只跳过 key（不影响 sendCacheControl 分支）；sendCacheControl 命中时即使没找到任何可打
     * 断点的位置，也返回 {@code true}（与 Go 一致——它是在应用后无条件置 rewritten）。</p>
     *
     * @param body      出站请求体（就地修改）
     * @param policy    {@link #promptCachePolicyFor} 的结果
     * @param sessionId {@link #promptCacheSessionID} 的结果
     * @param retention 缓存保留期；null 视同 Go 的零值 ""（即不等于 none，照常注入）
     * @return 是否就地改写了 body（= Go 的 forceRaw）
     */
    public static boolean applyPromptCacheToJSONBody(ObjectNode body, Policy policy, String sessionId,
                                                     CacheRetention retention) {
        if (retention == CacheRetention.NONE) {
            return false;
        }
        if (policy == null || (!policy.sendKey() && !policy.sendCacheControl())) {
            return false;
        }
        if (body == null) {
            return false;
        }

        boolean rewritten = false;
        if (policy.sendKey() && sessionId != null && !sessionId.isEmpty()) {
            body.put("prompt_cache_key", sessionId);
            if (retention == CacheRetention.LONG) {
                body.put("prompt_cache_retention", "24h");
            }
            rewritten = true;
        }
        if (policy.sendCacheControl()) {
            CacheControlMarker marker = cacheControlFor(retention, "1h");
            if (marker != null) {
                applyCacheControlBreakpoints(body, marker);
                rewritten = true;
            }
        }
        return rewritten;
    }

    /**
     * 对照 Go applyCacheControlBreakpoints：三处断点，顺序敏感（先 system/developer 指令，
     * 再最后一个 tool，最后最后一条对话消息）。
     */
    public static void applyCacheControlBreakpoints(ObjectNode payload, CacheControlMarker marker) {
        if (payload == null || marker == null) {
            return;
        }
        applyCacheControlToInstructionMessages(payload.get("messages"), marker);
        applyCacheControlToLastTool(payload.get("tools"), marker);
        applyCacheControlToLastConversationMessage(payload.get("messages"), marker);
    }

    /** 对照 Go applyCacheControlToInstructionMessages：只打第一条 system/developer。 */
    private static void applyCacheControlToInstructionMessages(JsonNode raw, CacheControlMarker marker) {
        if (raw == null || !raw.isArray()) {
            return;
        }
        for (JsonNode item : raw) {
            if (!item.isObject()) {
                continue;
            }
            String role = textOrEmpty(item.get("role"));
            if ("system".equals(role) || "developer".equals(role)) {
                addCacheControlToMessageContent((ObjectNode) item, marker);
                return;
            }
        }
    }

    /**
     * 对照 Go applyCacheControlToLastConversationMessage：从尾部往前找第一条
     * user/assistant/tool 且内容可打断点的消息（打不上就继续往前找）。
     */
    private static void applyCacheControlToLastConversationMessage(JsonNode raw, CacheControlMarker marker) {
        if (raw == null || !raw.isArray()) {
            return;
        }
        for (int i = raw.size() - 1; i >= 0; i--) {
            JsonNode item = raw.get(i);
            if (!item.isObject()) {
                continue;
            }
            String role = textOrEmpty(item.get("role"));
            if ("user".equals(role) || "assistant".equals(role) || "tool".equals(role)) {
                if (addCacheControlToMessageContent((ObjectNode) item, marker)) {
                    return;
                }
            }
        }
    }

    /** 对照 Go applyCacheControlToLastTool：无论内容形态，最后一个 tool 对象直接挂断点。 */
    private static void applyCacheControlToLastTool(JsonNode raw, CacheControlMarker marker) {
        if (raw == null || !raw.isArray() || raw.isEmpty()) {
            return;
        }
        JsonNode last = raw.get(raw.size() - 1);
        if (!last.isObject()) {
            return;
        }
        ((ObjectNode) last).set("cache_control", marker.toJson());
    }

    /**
     * 对照 Go addCacheControlToMessageContent：把断点挂到消息内容上。
     * 字符串内容 → 升格为 {@code [{"type":"text","text":...,"cache_control":...}]}；
     * 数组内容 → 从尾部往前找第一个 {@code text} 或 {@code tool_result} part。
     * 返回是否真的挂上了。
     */
    private static boolean addCacheControlToMessageContent(ObjectNode msg, CacheControlMarker marker) {
        JsonNode content = msg.get("content");
        if (content == null || content.isNull()) {
            return false;
        }
        if (content.isTextual()) {
            String text = content.textValue();
            if (text.isEmpty()) {
                return false;
            }
            ObjectNode part = MAPPER.createObjectNode();
            part.put("type", "text");
            part.put("text", text);
            part.set("cache_control", marker.toJson());
            ArrayNode parts = MAPPER.createArrayNode();
            parts.add(part);
            msg.set("content", parts);
            return true;
        }
        if (!content.isArray()) {
            return false;
        }
        for (int i = content.size() - 1; i >= 0; i--) {
            JsonNode item = content.get(i);
            if (!item.isObject()) {
                continue;
            }
            String partType = textOrEmpty(item.get("type"));
            if ("text".equals(partType) || "tool_result".equals(partType)) {
                ((ObjectNode) item).set("cache_control", marker.toJson());
                return true;
            }
        }
        return false;
    }

    /**
     * 对照 Go attachPromptCacheHeaders：仅 sendAffinity 的厂商（OpenAI 系）设置会话亲和头，
     * 让同一 session 的请求落到同一缓存分片。三处都用 Set 语义（覆盖，不追加）。
     */
    public static void attachPromptCacheHeaders(HttpHeaders headers, Policy policy, String sessionId) {
        if (headers == null || policy == null || !policy.sendAffinity()) {
            return;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        headers.set("session_id", sessionId);
        headers.set("x-client-request-id", sessionId);
        headers.set("x-session-affinity", sessionId);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 对照 Go valueOrZero：JSON 指针字段缺省/显式 null 都按 0。 */
    private static int valueOrZero(JsonNode value) {
        if (value == null || value.isNull()) {
            return 0;
        }
        return value.asInt(0);
    }

    /** 取子字段；缺失或显式 null 都返回 null（= Go 的 nil 指针语义）。 */
    private static JsonNode field(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode child = node.get(name);
        return child == null || child.isNull() ? null : child;
    }

    private static int intOrZero(JsonNode node, String name) {
        return valueOrZero(field(node, name));
    }

    private static String textOrEmpty(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : "";
    }

    private static String stringify(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // ObjectNode 序列化不会失败；真失败时返回空串会让指纹退化，故直接抛出
            throw new IllegalStateException("failed to serialize prompt prefix", e);
        }
    }
}
