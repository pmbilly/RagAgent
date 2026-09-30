package com.ragagent.llm.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.error.BizException;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.CacheRetention;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.llm.provider.ProviderBaseURLs;
import com.ragagent.llm.provider.ProviderName;
import com.ragagent.llm.provider.ProviderRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

/**
 * OpenAI 兼容 API 的聊天客户端（对照 Go internal/models/chat/remote_api.go 的
 * {@code RemoteAPIChat}，以及 openai_request.go / openai_stream.go 的请求构造与流式状态机）。
 *
 * <p>职责分界与 Go 一致：本类只做通用的请求/响应/流式处理，所有厂商特有行为交给
 * {@link ProviderAdapter}（见 {@link ProviderAdapters}），thinking 编码交给
 * {@link ThinkingStrategy}。</p>
 *
 * <p><b>Java 侧简化（已决策，见 ProviderAdapter 类注释）</b>：Go 的「go-openai SDK 路径 vs 裸 HTTP 路径」
 * 双实现合并为一条——请求体统一由 Jackson {@link ObjectNode} 组装后直接 POST。因此
 * {@code useRawHTTP}/{@code ForceRawHTTP} 的判定消失，出站流水线固定为：</p>
 * <ol>
 *   <li>{@link #convertMessages} 转消息 → {@code adapter.transformMessages} 改写；</li>
 *   <li>{@link #buildChatCompletionRequest} 组装标准 OpenAI 请求体（含采样参数与完成预算）；</li>
 *   <li>{@code adapter.shapeRequest} 厂商整形；</li>
 *   <li>thinking：{@code extra_config.thinking_control} 覆盖优先，其次 {@code adapter.thinking()}；</li>
 *   <li>{@link PromptCache#applyPromptCacheToJSONBody} 注入缓存字段；</li>
 *   <li>{@code adapter.endpoint} 覆写 URL（空则 {@code <baseUrl>/chat/completions}）；</li>
 *   <li>{@code adapter.auth} 设鉴权头，追加自定义头与缓存亲和头；发请求，流式走 {@link SseReader}。</li>
 * </ol>
 *
 * <p><b>与 Go 的两处已知差异（需主会话知悉，见报告）</b>：</p>
 * <ol>
 *   <li>Azure OpenAI 在 Go 里走 SDK，URL 由 go-openai 的 {@code fullURL} 拼成
 *       {@code <base>/openai/deployments/<model>/chat/completions?api-version=...}；
 *       Java 单路径必须自己拼（见 {@link #resolveEndpoint}），api-version 取
 *       {@code extra_config.api_version}，缺省 {@value #DEFAULT_AZURE_API_VERSION}
 *       （= go-openai DefaultAzureConfig 的默认值）。</li>
 *   <li>流结束（EOF / {@code data: [DONE]}）的终态 answer 带上 {@code finish_reason}
 *       （Go 裸 HTTP 路径不带、SDK 路径带；按主会话指定的合并语义取后者）。</li>
 * </ol>
 *
 * <p>超时：{@link LlmTransport#withLlmTimeout} 只在调用方未给 deadline 时套兜底值。
 * Java 侧把超时施加在请求头阶段（JDK 的 {@code HttpRequest.timeout}），流式响应体读取过程中
 * 不像 Go 的 ctx 那样会被 deadline 掐断——长流依赖上游自行收尾。</p>
 */
public class RemoteApiChat implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteApiChat.class);

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 出站请求体序列化器：Go json.Marshal 等价（HTML 转义 < > &，见 §9 差分排查）。 */
    private static final com.fasterxml.jackson.databind.json.JsonMapper GO_MARSHAL =
            goMarshal();

    private static com.fasterxml.jackson.databind.json.JsonMapper goMarshal() {
        com.fasterxml.jackson.databind.json.JsonMapper mapper =
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
        mapper.getFactory().setCharacterEscapes(new com.ragagent.common.web.GoJsonEscapes());
        return mapper;
    }

    /** 对照 Go remote_api.go 的 remote_model_name / api_version 两个 extra_config 键。 */
    private static final String EXTRA_REMOTE_MODEL_NAME = "remote_model_name";
    private static final String EXTRA_API_VERSION = "api_version";

    /** go-openai DefaultAzureConfig 的默认 api-version（Go 走 SDK 时由它兜底）。 */
    static final String DEFAULT_AZURE_API_VERSION = "2023-05-15";

    /** 对照 Go 的 thinking 工具名特例（thought 参数增量转成 thinking 分片）。 */
    private static final String THINKING_TOOL_NAME = "thinking";

    /** 日志里 data URL 的预览长度与整行上限（对照 log_sanitize.go）。 */
    private static final int MAX_DATA_URL_PREVIEW = 128;
    private static final int MAX_LOG_CHARS = 2000;
    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("data:([^;\"\\s]*);base64,[A-Za-z0-9+/=]+");

    /** 对照 Go utils.reservedHeaderKeys：不允许被用户自定义头覆盖的关键头。 */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "api-key", "x-api-key", "x-goog-api-key", "content-type",
            "content-length", "accept-encoding", "host", "connection", "transfer-encoding");

    final String modelName;
    private final String modelId;
    private final String baseUrl;
    private final String apiKey;
    /** provider 名；未知厂商（Go 允许任意字符串，Java 枚举表达不了）为 null = Go 的 default 分支。 */
    final ProviderName provider;
    private final String appId;
    private final String appSecret;
    /** 用户在模型配置里指定的自定义 HTTP 头（类 OpenAI Python SDK 的 extra_headers）。 */
    private final Map<String, String> customHeaders;
    /** 仅 Azure 使用：URL 上的 api-version（对照 go-openai config.APIVersion）。 */
    private final String azureApiVersion;

    /** 承载全部厂商特有行为；非 final 以便测试注入（对照 Go 测试直接改 c.adapter）。 */
    private ProviderAdapter adapter;
    /** 来自 extra_config.thinking_control，非 null 时覆盖 adapter.thinking()。 */
    private final ThinkingStrategy thinkingOverride;

    /** 出站组装协作者（对照 openai_request.go 段）。 */
    final RemoteApiRequestOps requestOps;

    /**
     * 对照 Go NewRemoteAPIChat：校验 baseURL（SSRF）、解析 provider、确定 baseURL 与模型名。
     *
     * <p>错误映射：Go 的构造期 error → {@link BizException#badRequest}（配置类问题，返回 400）。</p>
     */
    public RemoteApiChat(ChatConfig chatConfig) {
        if (chatConfig == null) {
            throw BizException.badRequest("chat config is required");
        }
        String rawBaseUrl = chatConfig.getBaseUrl() == null ? "" : chatConfig.getBaseUrl();
        if (!rawBaseUrl.isEmpty()) {
            try {
                LlmTransport.validateUrlForSsrf(rawBaseUrl);
            } catch (RuntimeException e) {
                throw BizException.badRequest("baseURL SSRF check failed: " + e.getMessage());
            }
        }

        String rawProvider = chatConfig.getProvider() == null ? "" : chatConfig.getProvider();
        ProviderName providerName = ProviderName.fromValue(rawProvider);
        if (rawProvider.isEmpty()) {
            // 对照 Go：providerName == "" 时才从 baseURL 探测；非空但未知的名字保持"未知"（Java 为 null）
            providerName = ProviderRegistry.detectProvider(rawBaseUrl);
        }

        String resolvedBaseUrl = rawBaseUrl;
        if (resolvedBaseUrl.isEmpty()) {
            if (providerName == ProviderName.DEEPSEEK) {
                resolvedBaseUrl = ProviderBaseURLs.DEEPSEEK_BASE_URL;
            } else if (providerName != ProviderName.AZURE_OPEN_AI) {
                // 对照 go-openai DefaultConfig 的默认 BaseURL
                resolvedBaseUrl = ProviderBaseURLs.OPENAI_BASE_URL;
            }
        }

        String modelName = chatConfig.getModelName();
        Map<String, String> extraConfig = chatConfig.getExtraConfig();
        if (extraConfig != null) {
            String override = extraConfig.get(EXTRA_REMOTE_MODEL_NAME);
            if (override != null && !override.trim().isEmpty()) {
                modelName = override.trim();
            }
        }
        if (providerName == ProviderName.WEKNORA_CLOUD) {
            if (isBlank(chatConfig.getAppId())) {
                throw BizException.badRequest("WeKnoraCloud provider: AppID is required");
            }
            if (isBlank(chatConfig.getAppSecret())) {
                throw BizException.badRequest("WeKnoraCloud provider: AppSecret is required");
            }
        }

        this.modelName = modelName;
        this.modelId = chatConfig.getModelId();
        this.baseUrl = ProviderAdapters.trimRightSlash(resolvedBaseUrl);
        this.apiKey = chatConfig.getApiKey();
        this.provider = providerName;
        this.appId = chatConfig.getAppId();
        this.appSecret = chatConfig.getAppSecret();
        this.customHeaders = chatConfig.getCustomHeaders();
        this.adapter = ProviderAdapters.resolve(providerName, modelName);
        this.thinkingOverride = ThinkingStrategies.parseThinkingOverride(extraConfig);
        String apiVersion = extraConfig == null ? null : extraConfig.get(EXTRA_API_VERSION);
        this.azureApiVersion = providerName == ProviderName.AZURE_OPEN_AI
                ? (isBlank(apiVersion) ? DEFAULT_AZURE_API_VERSION : apiVersion)
                : null;
        this.requestOps = new RemoteApiRequestOps(this);
    }

    /**
     * 薄委托：见 {@link RemoteApiRequestOps#convertMessages}。
     */
    public List<ChatMessage> convertMessages(List<ChatMessage> messages) {
        return requestOps.convertMessages(messages);
    }

    /** 薄委托：见 {@link RemoteApiRequestOps#buildChatCompletionRequest}。 */
    public ObjectNode buildChatCompletionRequest(List<ChatMessage> messages, ChatOptions opts, boolean isStream) {
        return requestOps.buildChatCompletionRequest(messages, opts, isStream);
    }

    /** 薄委托：见 {@link RemoteApiRequestOps#shapedRequest}。 */
    public ObjectNode shapedRequest(List<ChatMessage> messages, ChatOptions opts, boolean isStream) {
        return requestOps.shapedRequest(messages, opts, isStream);
    }

    // ------------------------------------------------------------------
    // 出站流水线（对照 go buildOutbound + chatWithRawHTTP 的请求头部分）
    // ------------------------------------------------------------------

    /**
     * 对照 Go buildOutbound：组装最终出站请求（body + endpoint + 缓存策略）。
     * 这是适配器与 thinking 的唯一交汇点（取代 Go 的 buildRequestCustomizer 管线）。
     */
    Outbound buildOutbound(List<ChatMessage> messages, ChatOptions opts, boolean isStream, String sessionId) {
        ObjectNode body = shapedRequest(messages, opts, isStream);

        ThinkingStrategy thinking = thinkingOverride != null ? thinkingOverride : adapter.thinking();
        boolean thinkingEmitted = thinking.apply(body, opts, isStream);

        CacheRetention retention = PromptCache.resolveCacheRetention(opts);
        PromptCache.Policy policy = PromptCache.promptCachePolicyFor(provider, baseUrl);
        boolean cacheRewritten = PromptCache.applyPromptCacheToJSONBody(
                body, policy, PromptCache.promptCacheSessionID(sessionId, opts), retention);

        // 对照 Go useRawHTTP = useRaw || ForceRawHTTP() || endpoint != "" || forceRaw：
        // Java 传输层不分流，但 Go 裸 HTTP 路径的流终态事件不带 finish_reason（SDK 路径带），
        // 该标记原样保留这一可观察差异（ForceRawHTTP 已删除，等价于恒 false）。
        String adapterEndpoint = adapter.endpoint(baseUrl, modelId, isStream);
        boolean rawPath = thinkingEmitted || cacheRewritten
                || (adapterEndpoint != null && !adapterEndpoint.isEmpty());
        String endpoint = resolveEndpoint(adapterEndpoint);
        return new Outbound(body, endpoint, policy, sessionId, rawPath, cacheRewritten);
    }

    /**
     * 最终 URL：适配器覆写优先，否则 {@code <baseUrl>/chat/completions}。
     *
     * <p>Azure 特例（Java 单路径新增）：go-openai 的 {@code fullURL} 会为 Azure 拼成
     * {@code <base>/openai/deployments/<deployment>/chat/completions?api-version=...}，
     * 这里按同一公式复刻（deployment 名 = modelId，AzureModelMapperFunc 在 Go 里是恒等函数）。</p>
     */
    private String resolveEndpoint(String adapterEndpoint) {
        if (adapterEndpoint != null && !adapterEndpoint.isEmpty()) {
            return adapterEndpoint;
        }
        if (provider == ProviderName.AZURE_OPEN_AI) {
            return baseUrl + "/openai/deployments/" + modelId
                    + "/chat/completions?api-version=" + azureApiVersion;
        }
        return baseUrl + "/chat/completions";
    }

    /** 出站请求（对照 Go buildOutbound 的三个返回值 + 发送时需要的策略/会话 + 路径标记）。 */
    record Outbound(ObjectNode body, String endpoint, PromptCache.Policy policy, String sessionId,
                    boolean rawPath, boolean cacheRewritten) {

        byte[] bodyBytes() {
            try {
                // Go json.Marshal 等价（分路径，2026-09-24 排查批坐实）：
                // ① prompt-cache 改写路径：Go 把 body 转 map → 每层对象按 map 键字节序
                //    （goSorted）；
                // ② 其余（SDK 结构体直出 / thinking 包装结构体）：openai-go 结构体字段序
                //    （structSorted），未知键（包装字段如 enable_thinking）尾随。
                // ③ HTML 转义两路径一致（< > & 转小写十六进制反斜杠 u 形式，§9 差分排查）。
                return GO_MARSHAL.writeValueAsBytes(
                        cacheRewritten ? goSorted(body) : structSorted(body));
            } catch (IOException e) {
                throw BizException.internal("marshal request: " + e.getMessage());
            }
        }
    }

    /**
     * 按 Go 的 map 序列化顺序重排请求体：Go 的整个 chat 请求体是经 map 出去的，
     * {@code encoding/json} 对 map 一律按 key 的字节序输出，所以<b>每一层对象</b>
     * 都是字母序。2026-09-23 双端实录对拍坐实三处同源差异：顶层
     * {@code max_completion_tokens/messages/model/parallel_tool_calls/prompt_cache_key/
     * stream/stream_options/tools}、messages 元素 {@code content/role}、
     * 工具 schema {@code properties/required/type}——而 Java 侧的 ObjectNode 保持插入序。
     *
     * <p>键序比较用 UTF-8 字节（与 Go 一致，而非 Java 的 UTF-16 码元序）。</p>
     */
    static JsonNode goSorted(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort((a, b) -> java.util.Arrays.compare(
                    a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, goSorted(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                sorted.add(goSorted(item));
            }
            return sorted;
        }
        return node;
    }

    // ── openai-go 结构体字段序（v1.41.2 实录；SDK 直出路径的键序）─────────────

    /** 对照 ChatCompletionRequest 声明序（chat.go L263-328，含尾部 Extensions）。 */
    private static final List<String> SDK_TOP_ORDER = List.of(
            "model", "messages", "max_tokens", "max_completion_tokens", "temperature",
            "top_p", "n", "stream", "stop", "presence_penalty", "response_format", "seed",
            "frequency_penalty", "logit_bias", "logprobs", "top_logprobs", "user",
            "functions", "function_call", "tools", "tool_choice", "stream_options",
            "parallel_tool_calls", "store", "reasoning_effort", "metadata", "prediction",
            "chat_template_kwargs", "service_tier", "verbosity", "safety_identifier",
            "guided_choice");

    /** 父键 → 子对象字段序（对照各嵌套结构体声明序）。 */
    private static final Map<String, List<String>> SDK_NESTED_ORDER = Map.of(
            "messages", List.of("role", "content", "refusal", "name", "reasoning_content",
                    "function_call", "tool_calls", "tool_call_id"),
            "tools", List.of("type", "function"),
            "functions", List.of("name", "description", "strict", "parameters"),
            "function", List.of("name", "description", "strict", "parameters"),
            "message_function", List.of("name", "arguments"),
            "tool_calls", List.of("index", "id", "type", "function"),
            "stream_options", List.of("include_usage"),
            "response_format", List.of("type", "json_schema"),
            "json_schema", List.of("name", "description", "schema", "strict"));

    /**
     * 按 openai-go 结构体声明序重排（SDK 直出路径，对照 Go body=&req 的
     * json.Marshal）。规则：
     * <ul>
     *   <li>已知键按表序；未知键（thinking 包装字段如 enable_thinking）按插入序
     *       尾随——Go 的包装结构体把扩展字段声明在嵌入基座之后；</li>
     *   <li>Go map 类型字段（metadata/logit_bias/chat_template_kwargs）本应字母序，
     *       单键场景与插入序一致，从简不改；</li>
     *   <li>工具 parameters 子树不动——Go 里是 json.Marshaler（jsonschema 库结构体
     *       序），Java 的 schema 字面量本就按同序录入。</li>
     * </ul>
     */
    static JsonNode structSorted(JsonNode node) {
        return structSorted(node, SDK_TOP_ORDER);
    }

    private static JsonNode structSorted(JsonNode node, List<String> order) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            List<String> known = new ArrayList<>(names);
            List<String> unknown = new ArrayList<>();
            for (String name : names) {
                if (order.contains(name)) {
                    continue;
                }
                known.remove(name);
                unknown.add(name);
            }
            known.sort(java.util.Comparator.comparingInt(order::indexOf));
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : known) {
                sorted.set(name, structSortedChild(name, node.get(name)));
            }
            for (String name : unknown) {
                sorted.set(name, structSortedChild(name, node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                sorted.add(structSorted(item, order));
            }
            return sorted;
        }
        return node;
    }

    /** 子节点按父键选表；parameters 子树（jsonschema 结构体序）原样保留。 */
    private static JsonNode structSortedChild(String parentKey, JsonNode child) {
        if ("parameters".equals(parentKey)) {
            return child;
        }
        List<String> childOrder = SDK_NESTED_ORDER.get(parentKey);
        if (childOrder == null) {
            return structSorted(child, SDK_TOP_ORDER);
        }
        return structSorted(child, childOrder);
    }

    /** 组请求头（对照 Go chatWithRawHTTP/chatStreamWithRawHTTP 的头部设置顺序）。 */
    private HttpHeaders buildHeaders(Outbound out, byte[] bodyBytes, boolean isStream) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        adapter.auth(headers, authCreds(), bodyBytes);
        if (isStream) {
            headers.set("Accept", "text/event-stream");
        }
        applyCustomHeaders(headers, customHeaders);
        PromptCache.attachPromptCacheHeaders(headers, out.policy(), out.sessionId());
        return headers;
    }

    /** 对照 Go authCreds()。 */
    private ProviderAdapter.AuthCreds authCreds() {
        return new ProviderAdapter.AuthCreds(apiKey, appId, appSecret);
    }

    /** 对照 Go secutils.ApplyCustomHeaders：保留头跳过，其余覆盖。 */
    private static void applyCustomHeaders(HttpHeaders headers, Map<String, String> custom) {
        if (custom == null || custom.isEmpty()) {
            return;
        }
        custom.forEach((key, value) -> {
            String name = key == null ? "" : key.trim();
            if (name.isEmpty() || RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            headers.set(name, value);
        });
    }

    /** 发一次裸 HTTP 请求（对照 Go chatWithRawHTTP 的 SSRF 校验 + rawHTTPClient.Do）。 */
    private HttpResponse<InputStream> sendRequest(Outbound out, Duration timeout, boolean isStream) {
        byte[] bodyBytes = out.bodyBytes();
        try {
            LlmTransport.validateUrlForSsrf(out.endpoint());
        } catch (RuntimeException e) {
            throw BizException.internal("endpoint SSRF check failed: " + e.getMessage());
        }
        log.info("[LLM Request] Remote HTTP, endpoint={}, model={}, stream={}\n{}",
                out.endpoint(), modelName, isStream,
                compactForLog(new String(bodyBytes, StandardCharsets.UTF_8)));

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(out.endpoint()))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        } catch (IllegalArgumentException e) {
            throw BizException.internal("create request: " + e.getMessage());
        }
        buildHeaders(out, bodyBytes, isStream).forEach((name, values) ->
                values.forEach(value -> builder.header(name, value)));

        try {
            return LlmTransport.send(builder.build());
        } catch (IOException e) {
            // getMessage() 可能为 null（如 EOFException），对照 Go fmt.Errorf("send request: %w")
            // 会打出错误名，兜底用异常类名。
            throw BizException.internal("send request: " + ioDetail(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.internal("send request interrupted");
        }
    }

    private static String ioDetail(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** 非 200 时读出 body 并抛错（对照 Go 的 "API request failed with status %d: %s"）。 */
    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BizException.internal("read response: " + e.getMessage());
        }
    }

    private static String statusError(HttpResponse<?> resp, String body) {
        return "API request failed with status " + resp.statusCode() + ": " + body;
    }

    // ------------------------------------------------------------------
    // 非流式（对照 remote_api.go Chat / chatWithRawHTTP）
    // ------------------------------------------------------------------

    @Override
    public ChatResponse chat(List<ChatMessage> messages, ChatOptions opts) {
        return chat(messages, opts, null, null);
    }

    /**
     * 非流式聊天（带调用方 deadline 与 session ID）。
     *
     * @param callerDeadline 调用方下发的截止时刻；null = 未设置，套用
     *                       {@link LlmTransport#DEFAULT_CHAT_TIMEOUT}
     * @param sessionId      上下文里的 session ID（对照 Go types.SessionIDFromContext(ctx)）；
     *                       仅在 opts.promptCacheKey 为空时用于 prompt_cache_key
     */
    public ChatResponse chat(List<ChatMessage> messages, ChatOptions opts,
                             Instant callerDeadline, String sessionId) {
        Duration timeout = LlmTransport.withLlmTimeout(callerDeadline, LlmTransport.DEFAULT_CHAT_TIMEOUT);

        Outbound out = buildOutbound(messages, opts, false, sessionId);
        HttpResponse<InputStream> resp = sendRequest(out, timeout, false);
        int status = resp.statusCode();
        String raw = readAll(resp.body());
        if (status != 200) {
            String message = statusError(resp, raw);
            // 对照 Go 的 isMultimodalNotSupportedError 重试：剥掉图片再发一次
            if (ImageResolver.isMultimodalNotSupportedMessage(message)) {
                log.warn("[LLM Request] Model {} does not support multimodal, retrying without images",
                        modelName);
                out = buildOutbound(ImageResolver.stripImagesFromMessages(messages), opts, false, sessionId);
                resp = sendRequest(out, timeout, false);
                status = resp.statusCode();
                raw = readAll(resp.body());
                if (status != 200) {
                    throw BizException.internal(statusError(resp, raw));
                }
            } else {
                throw BizException.internal(message);
            }
        }

        JsonNode body;
        try {
            body = MAPPER.readTree(raw);
        } catch (IOException e) {
            throw BizException.internal("decode response: " + e.getMessage());
        }
        ChatResponse result = parseCompletionResponse(body);
        applyCompletionToolCallMetadata(body, result);
        PromptCache.applyRawPromptCacheUsage(raw, result.getUsage());
        logUsage(result.getUsage());
        return result;
    }

    // ------------------------------------------------------------------
    // 流式（对照 remote_api.go ChatStream / chatStreamWithRawHTTP）
    // ------------------------------------------------------------------

    @Override
    public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions opts) {
        return chatStream(messages, opts, null, null);
    }

    /**
     * 流式聊天：**先同步建立连接，再返回队列**——建立阶段的错误直接抛出；
     * 进入流之后的错误通过流内的 ERROR 型 {@link StreamResponse} 传递。
     *
     * <p>消费者以 {@code done=true} 的元素作为流结束标记（队列不关闭）。</p>
     */
    public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions opts,
                                                    Instant callerDeadline, String sessionId) {
        Duration timeout = LlmTransport.withLlmTimeout(callerDeadline, LlmTransport.DEFAULT_STREAM_TIMEOUT);

        Outbound out = buildOutbound(messages, opts, true, sessionId);
        HttpResponse<InputStream> resp = sendRequest(out, timeout, true);
        int status = resp.statusCode();
        if (status != 200) {
            String message = statusError(resp, readAll(resp.body()));
            if (ImageResolver.isMultimodalNotSupportedMessage(message)) {
                log.warn("[LLM Stream] Model {} does not support multimodal, retrying without images",
                        modelName);
                out = buildOutbound(ImageResolver.stripImagesFromMessages(messages), opts, true, sessionId);
                resp = sendRequest(out, timeout, true);
                status = resp.statusCode();
                if (status != 200) {
                    throw BizException.internal(statusError(resp, readAll(resp.body())));
                }
            } else {
                throw BizException.internal(message);
            }
        }

        BlockingQueue<StreamResponse> streamChan = new LinkedBlockingQueue<>();
        InputStream body = resp.body();
        boolean rawPath = out.rawPath();
        Thread.ofVirtual().name("llm-stream-" + modelName)
                .start(() -> processRawHttpStream(body, streamChan, rawPath));
        return streamChan;
    }

    /**
     * 对照 Go processRawHTTPStream：SSE 逐事件读取 + 解析 + 交给
     * {@link #processStreamDelta}。
     *
     * <p>终态：EOF 与 {@code data: [DONE]} 都发一条 answer{Done:true, ToolCalls, Usage}；
     * FinishReason 只在 SDK 等价路径（{@code rawPath=false}）携带——Go 裸 HTTP 路径的
     * 终态事件不带 finish_reason（remote_api.go processRawHTTPStream 的收尾 send），
     * SDK 路径带 state.lastFinishReason。读错误发 error{Done:true, FinishReason:"incomplete"}。</p>
     */
    void processRawHttpStream(InputStream input, BlockingQueue<StreamResponse> streamChan,
                              boolean rawPath) {
        OpenAiStreamState state = new OpenAiStreamState();
        SseReader reader = new SseReader(input);
        try (input) {
            while (true) {
                SseReader.SseEvent event;
                try {
                    Optional<SseReader.SseEvent> next = reader.readEvent();
                    if (next.isEmpty()) {
                        streamChan.put(terminalResponse(state, rawPath));
                        return;
                    }
                    event = next.get();
                } catch (IOException e) {
                    log.error("Stream read error: {} (tool_calls_assembled={})",
                            e.getMessage(), state.toolCallMap.size());
                    StreamResponse error = StreamResponse.of(ResponseType.ERROR, e.getMessage(), true);
                    error.setToolCalls(state.buildOrderedToolCalls());
                    error.setUsage(state.usage);
                    error.setFinishReason(StreamResponse.FINISH_REASON_INCOMPLETE);
                    streamChan.put(error);
                    return;
                }

                if (event.done()) {
                    // 对照 Go：data: [DONE] 与 EOF 走同一条终态路径
                    streamChan.put(terminalResponse(state, rawPath));
                    return;
                }
                if (event.data() == null) {
                    continue;
                }

                String data = event.dataText();
                JsonNode chunk;
                try {
                    chunk = MAPPER.readTree(data);
                } catch (IOException e) {
                    log.error("Failed to parse stream response: {}", e.getMessage());
                    continue;
                }

                JsonNode usageNode = chunk.get("usage");
                if (usageNode != null && !usageNode.isNull()) {
                    TokenUsage usage = PromptCache.tokenUsageFromOpenAI(usageNode, provider);
                    PromptCache.applyRawPromptCacheUsage(data, usage);
                    state.usage = usage;
                }

                JsonNode choices = chunk.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty()) {
                    JsonNode choice = choices.get(0);
                    JsonNode delta = choice.path("delta");
                    // 统一获取逻辑（兼容标准 reasoning_content 与 vLLM 的 reasoning）
                    String reasoning = textOrEmpty(delta.get("reasoning"));
                    if (reasoning.isEmpty()) {
                        reasoning = textOrEmpty(delta.get("reasoning_content"));
                    }
                    applyStreamToolCallMetadata(chunk, state);
                    processStreamDelta(choice, state, streamChan, reasoning);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[LLM Stream] interrupted, closing stream for model={}", modelName);
        } catch (IOException e) {
            log.debug("[LLM Stream] failed to close stream body for model={}: {}", modelName, e.getMessage());
        }
    }

    /**
     * 流终态响应（EOF / [DONE]）。
     *
     * <p>对照 Go：裸 HTTP 路径的收尾不带 FinishReason，SDK 路径带
     * {@code state.lastFinishReason}；{@code rawPath} 复刻这一分野
     * （字段 omitempty，未观察到时仍是省略）。</p>
     */
    private StreamResponse terminalResponse(OpenAiStreamState state, boolean rawPath) {
        logUsage(state.usage);
        StreamResponse done = StreamResponse.of(ResponseType.ANSWER, "", true);
        done.setToolCalls(state.buildOrderedToolCalls());
        done.setUsage(state.usage);
        if (!rawPath) {
            done.setFinishReason(state.lastFinishReason);
        }
        return done;
    }

    /**
     * 对照 Go processStreamDelta（openai_stream.go:389-488）：单个 delta 的**逐块产出顺序**
     * 是这份翻译的保真重点，顺序如下，不得调整：
     *
     * <ol>
     *   <li>tool_calls delta → {@link #processToolCallsDelta}（可能产出 tool_call / thinking）；</li>
     *   <li>reasoning 分片 → thinking{content, done=false}；</li>
     *   <li>answer 分片 → 先 {@code thinkingEmitter.finish()} 补 thinking-done，再发 answer；</li>
     *   <li>isDone 且有 toolCalls → 再发一条空 answer{Done:true, ToolCalls}；</li>
     *   <li>isDone → 兜底 {@code thinkingEmitter.finish()}；</li>
     *   <li>isDone 且空内容、无工具 → 兜底 answer{Done:true}（保证 finish_reason 不丢）。</li>
     * </ol>
     */
    void processStreamDelta(JsonNode choice, OpenAiStreamState state,
                            BlockingQueue<StreamResponse> streamChan, String reasoningContent)
            throws InterruptedException {
        JsonNode delta = choice.path("delta");
        String finishReason = textOrEmpty(choice.get("finish_reason"));
        boolean isDone = !finishReason.isEmpty();
        if (isDone) {
            state.lastFinishReason = finishReason;
        }

        JsonNode toolCalls = delta.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            processToolCallsDelta(toolCalls, state, streamChan);
        }

        // OpenAI 协议层最早、最可靠的"没有 tool_calls"信号（fire-once 诊断日志）
        if (isDone && "stop".equals(finishReason) && !state.firstToolCallSeen && !state.noToolCallStopLogged) {
            log.info("[LLM Stream] Natural-stop at OpenAI layer (finish=stop, tool_calls field never "
                            + "observed, thinking_seen={}, first_content_seen={}, elapsed_ms={})",
                    state.thinking.isActive(), state.firstContentSeen, state.elapsedMs());
            state.noToolCallStopLogged = true;
        }

        if (!reasoningContent.isEmpty()) {
            if (!state.firstReasoningSeen) {
                state.firstReasoningSeen = true;
                log.info("[LLM Stream] First reasoning_content at OpenAI layer (len={}, elapsed_ms={})",
                        reasoningContent.length(), state.elapsedMs());
            }
            state.thinking.emit(streamChan, reasoningContent);
        }

        String content = textOrEmpty(delta.get("content"));
        if (!content.isEmpty()) {
            if (!state.firstContentSeen) {
                state.firstContentSeen = true;
                log.info("[LLM Stream] First delta.Content at OpenAI layer (len={}, tool_call_seen={}, "
                                + "thinking_seen={}, elapsed_ms={})",
                        content.length(), state.firstToolCallSeen, state.firstReasoningSeen, state.elapsedMs());
            }
            // 先补 thinking-done，再发首个答案分片
            state.thinking.finish(streamChan);
            StreamResponse answer = StreamResponse.of(ResponseType.ANSWER, content, isDone);
            answer.setToolCalls(state.buildOrderedToolCalls());
            answer.setFinishReason(finishReason);
            streamChan.put(answer);
        }

        if (isDone && !state.toolCallMap.isEmpty()) {
            StreamResponse withTools = StreamResponse.of(ResponseType.ANSWER, "", true);
            withTools.setToolCalls(state.buildOrderedToolCalls());
            withTools.setFinishReason(finishReason);
            streamChan.put(withTools);
        }

        // 流在没有答案内容的情况下结束（只产出了 reasoning）时，也要补 thinking-done
        if (isDone) {
            state.thinking.finish(streamChan);
        }

        if (isDone && content.isEmpty() && state.toolCallMap.isEmpty()) {
            StreamResponse fallback = StreamResponse.of(ResponseType.ANSWER, "", true);
            fallback.setFinishReason(finishReason);
            streamChan.put(fallback);
        }
    }

    /**
     * 对照 Go processToolCallsDelta（openai_stream.go:491-630）：tool_calls 增量累积与**发出时机**。
     *
     * <p>保真要点：</p>
     * <ol>
     *   <li>名字是<b>拼接</b>语义，但相同名字视为冗余重复不叠加（vLLM Ascend 等每个 chunk
     *       重复发全名）；</li>
     *   <li>tool_call 标记<b>不是一到就发</b>：必须"本 delta 累计名 == 上次名"（名字已稳定）
     *       + 本次有 arguments 增量 + 该 index 未通知过 + 已有 ID，才发一次；</li>
     *   <li>thinking 工具特例：arguments 里的 thought 字段用 {@link JsonFieldExtractor}
     *       增量抽出，按 thinking 分片下发（Data.source = "thinking_tool"）；</li>
     * </ol>
     *
     */
    private void processToolCallsDelta(JsonNode toolCalls, OpenAiStreamState state,
                                       BlockingQueue<StreamResponse> streamChan) throws InterruptedException {
        if (!state.firstToolCallSeen && !toolCalls.isEmpty()) {
            state.firstToolCallSeen = true;
            String firstId = "";
            String firstName = "";
            for (JsonNode tc : toolCalls) {
                if (firstId.isEmpty()) {
                    firstId = textOrEmpty(tc.get("id"));
                }
                if (firstName.isEmpty()) {
                    firstName = textOrEmpty(tc.path("function").get("name"));
                }
                if (!firstId.isEmpty() || !firstName.isEmpty()) {
                    break;
                }
            }
            log.info("[LLM Stream] First tool_calls delta at OpenAI layer (count={}, first_id={}, "
                            + "first_name={}, first_content_seen={}, thinking_seen={}, elapsed_ms={})",
                    toolCalls.size(), firstId, firstName,
                    state.firstContentSeen, state.firstReasoningSeen, state.elapsedMs());
        }

        for (JsonNode tc : toolCalls) {
            int toolCallIndex = tc.hasNonNull("index") ? tc.get("index").asInt() : 0;
            ToolCall entry = state.toolCallMap.get(toolCallIndex);
            if (entry == null) {
                entry = new ToolCall();
                entry.setType(textOrEmpty(tc.get("type")));
                entry.getFunction().setName("");
                entry.getFunction().setArguments("");
                state.toolCallMap.put(toolCallIndex, entry);
            }

            String id = textOrEmpty(tc.get("id"));
            if (!id.isEmpty()) {
                entry.setId(id);
            }
            String type = textOrEmpty(tc.get("type"));
            if (!type.isEmpty()) {
                entry.setType(type);
            }

            String incomingName = textOrEmpty(tc.path("function").get("name"));
            if (!incomingName.isEmpty()) {
                // 防御性校验：部分供应商（如 vLLM Ascend）每个流 chunk 重复发送完整工具名，
                // 名字与已存一致时视为冗余重复，不叠加。
                String currentName = entry.getFunction().getName();
                if (!currentName.equals(incomingName)) {
                    entry.getFunction().setName(currentName + incomingName);
                }
            }

            String argsDelta = textOrEmpty(tc.path("function").get("arguments"));
            boolean argsUpdated = false;
            if (!argsDelta.isEmpty()) {
                entry.getFunction().setArguments(entry.getFunction().getArguments() + argsDelta);
                argsUpdated = true;
            }

            String currName = entry.getFunction().getName();
            boolean nameStable = !currName.isEmpty()
                    && currName.equals(state.lastFunctionName.get(toolCallIndex));
            if (nameStable && argsUpdated
                    && !Boolean.TRUE.equals(state.nameNotified.get(toolCallIndex))
                    && !entry.getId().isEmpty()) {
                streamChan.put(toolCallResponse(currName, entry.getId(), null));
                state.nameNotified.put(toolCallIndex, true);
            }

            state.lastFunctionName.put(toolCallIndex, currName);

            // thinking 工具的 thought 参数按 thinking 分片增量下发
            if (THINKING_TOOL_NAME.equals(entry.getFunction().getName()) && argsUpdated) {
                JsonFieldExtractor extractor = state.fieldExtractors.get(toolCallIndex);
                if (extractor == null) {
                    extractor = new JsonFieldExtractor("thought");
                    state.fieldExtractors.put(toolCallIndex, extractor);
                }
                String thoughtChunk = extractor.feed(argsDelta);
                if (!thoughtChunk.isEmpty()) {
                    StreamResponse thinking = StreamResponse.of(ResponseType.THINKING, thoughtChunk, false);
                    // Data 键序按字母序（对照 Go map 序列化）
                    LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
                    data.put("source", "thinking_tool");
                    data.put("tool_call_id", entry.getId());
                    thinking.setData(data);
                    streamChan.put(thinking);
                }
            }
        }
    }

    /** tool_call 事件（对照 Go 的 types.ResponseTypeToolCall + Data 三个键）。 */
    private static StreamResponse toolCallResponse(String toolName, String toolCallId,
                                                   Map<String, Object> progressArgs) {
        StreamResponse response = StreamResponse.of(ResponseType.TOOL_CALL, "", false);
        // 键序按字母序（arguments < tool_call_id < tool_name），对照 Go map 序列化
        LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        if (progressArgs != null) {
            data.put("arguments", progressArgs);
        }
        data.put("tool_call_id", toolCallId);
        data.put("tool_name", toolName);
        response.setData(data);
        return response;
    }

    /**
     * 对照 Go applyStreamToolCallMetadata：从原始分片里抓取厂商特有工具调用状态
     * （Gemini 的 extra_content.google 思考签名），挂到对应 index 上。
     * 必须在 {@link #processStreamDelta} 之前调用，后续增量才会填进同一条目。
     */
    void applyStreamToolCallMetadata(JsonNode chunk, OpenAiStreamState state) {
        if (state == null) {
            return;
        }
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode toolCalls = choices.get(0).path("delta").get("tool_calls");
        if (toolCalls == null || !toolCalls.isArray()) {
            return;
        }
        for (JsonNode rawToolCall : toolCalls) {
            Map<String, JsonNode> metadata = adapter.extractToolCallMetadata(rawToolCall);
            if (metadata == null || metadata.isEmpty()) {
                continue;
            }
            int index = rawToolCall.hasNonNull("index") ? rawToolCall.get("index").asInt() : 0;
            state.setToolCallProviderMetadata(index, metadata);
        }
    }

    // ------------------------------------------------------------------
    // 非流式响应解析（对照 openai_stream.go 前半）
    // ------------------------------------------------------------------

    /** 对照 Go parseCompletionResponse：取 choices[0]，剥 thinking 标签，带出 tool_calls 与 usage。 */
    ChatResponse parseCompletionResponse(JsonNode resp) {
        JsonNode choices = resp == null ? null : resp.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw BizException.internal("no response from API");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");

        ChatResponse response = new ChatResponse();
        response.setContent(removeThinkingContent(textOrEmpty(message.get("content"))));
        response.setFinishReason(textOrEmpty(choice.get("finish_reason")));
        response.setUsage(PromptCache.tokenUsageFromOpenAI(resp.get("usage"), provider));

        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<ToolCall> out = new ArrayList<>(toolCalls.size());
            for (JsonNode tc : toolCalls) {
                ToolCall call = new ToolCall();
                call.setId(textOrEmpty(tc.get("id")));
                call.setType(textOrEmpty(tc.get("type")));
                JsonNode fn = tc.path("function");
                call.getFunction().setName(textOrEmpty(fn.get("name")));
                call.getFunction().setArguments(textOrEmpty(fn.get("arguments")));
                out.add(call);
            }
            response.setToolCalls(out);
        }
        return response;
    }

    /**
     * 对照 Go applyCompletionToolCallMetadata：用**原始响应体**里的 tool_call 对象抽取
     * 厂商特有状态（Gemini 的 extra_content），按 index 回填。
     */
    void applyCompletionToolCallMetadata(JsonNode body, ChatResponse result) {
        if (result == null || result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
            return;
        }
        JsonNode choices = body == null ? null : body.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode toolCalls = choices.get(0).path("message").get("tool_calls");
        if (toolCalls == null || !toolCalls.isArray()) {
            return;
        }
        int fallbackIndex = 0;
        for (JsonNode rawToolCall : toolCalls) {
            int idx = rawToolCall.hasNonNull("index") ? rawToolCall.get("index").asInt() : fallbackIndex;
            if (idx >= 0 && idx < result.getToolCalls().size()) {
                result.getToolCalls().get(idx)
                        .setProviderMetadata(adapter.extractToolCallMetadata(rawToolCall));
            }
            fallbackIndex++;
        }
    }

    /**
     * 对照 Go removeThinkingContent：移除思考模型输出里的 {@code <think>...</think>}。
     * 仅当内容以 {@code <think>} 开头才处理；取**最后一个** {@code </think>}（容忍嵌套）；
     * 找不到闭标签（思考被截断）返回空串。
     */
    static String removeThinkingContent(String content) {
        final String thinkStartTag = "<think>";
        final String thinkEndTag = "</think>";
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith(thinkStartTag)) {
            return content;
        }
        int lastEndIdx = trimmed.lastIndexOf(thinkEndTag);
        if (lastEndIdx != -1) {
            String result = trimmed.substring(lastEndIdx + thinkEndTag.length()).trim();
            return result.isEmpty() ? "" : result;
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 日志与访问器
    // ------------------------------------------------------------------

    /** 对照 Go logUsage：nil 安全的标准用量日志行（无 ctx，purpose/前缀指纹取不到）。 */
    private void logUsage(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        log.info("[LLM Usage] model={}, prompt_tokens={}, completion_tokens={}, total_tokens={}, "
                        + "cached_tokens={}, cache_read_tokens={}, cache_write_tokens={}, "
                        + "cache_miss_tokens={}, cache_hit_rate={}%, cache_reported={}, cache_status={}",
                modelName, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                usage.getCachedTokens(), usage.getCacheReadTokens(), usage.getCacheWriteTokens(),
                usage.getCacheMissTokens(), String.format(Locale.ROOT, "%.1f", usage.promptCacheHitRate()),
                usage.isCacheReported(), usage.getCacheStatus() == null ? "" : usage.getCacheStatus().value());
    }

    /** 对照 Go secutils.CompactImageDataURLForLog：截断 data URL 与整行长度后再落日志。 */
    static String compactForLog(String raw) {
        if (raw == null) {
            return "";
        }
        String masked = DATA_URL_PATTERN.matcher(raw).replaceAll(match -> {
            String value = match.group();
            if (value.length() <= MAX_DATA_URL_PREVIEW) {
                return value;
            }
            return value.substring(0, MAX_DATA_URL_PREVIEW)
                    + "...<omitted " + (value.length() - MAX_DATA_URL_PREVIEW) + " chars>";
        });
        if (masked.length() <= MAX_LOG_CHARS) {
            return masked;
        }
        return masked.substring(0, MAX_LOG_CHARS)
                + "... (truncated, total " + masked.length() + " chars)";
    }

    /** 对照 Go GetModelName。 */
    @Override
    public String getModelName() {
        return modelName;
    }

    /** 对照 Go GetModelID。 */
    @Override
    public String getModelId() {
        return modelId;
    }

    /** 对照 Go GetProvider（未知厂商为 null，= Go 的 default 分支）。 */
    public ProviderName getProvider() {
        return provider;
    }

    /** 对照 Go GetBaseURL。 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** 对照 Go GetAPIKey。 */
    public String getApiKey() {
        return apiKey;
    }

    /** 当前适配器（测试用，对照 Go 测试直接访问 c.adapter）。 */
    ProviderAdapter adapter() {
        return adapter;
    }

    /** 测试用：替换适配器（对照 Go 测试的 {@code c.adapter = geminiProvider{}}）。 */
    void setAdapter(ProviderAdapter adapter) {
        this.adapter = adapter;
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return "";
        }
        return node.textValue();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
