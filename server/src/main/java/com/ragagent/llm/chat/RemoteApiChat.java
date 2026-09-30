package com.ragagent.llm.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.error.BizException;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.CacheRetention;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.provider.ProviderBaseURLs;
import com.ragagent.llm.provider.ProviderName;
import com.ragagent.llm.provider.ProviderRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** 对照 Go remote_api.go 的 remote_model_name / api_version 两个 extra_config 键。 */
    private static final String EXTRA_REMOTE_MODEL_NAME = "remote_model_name";
    private static final String EXTRA_API_VERSION = "api_version";

    /** go-openai DefaultAzureConfig 的默认 api-version（Go 走 SDK 时由它兜底）。 */
    static final String DEFAULT_AZURE_API_VERSION = "2023-05-15";

    /** 日志里 data URL 的预览长度与整行上限（对照 log_sanitize.go）。 */
    private static final int MAX_DATA_URL_PREVIEW = 128;
    private static final int MAX_LOG_CHARS = 2000;
    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("data:([^;\"\\s]*);base64,[A-Za-z0-9+/=]+");

    final String modelName;
    private final String modelId;
    private final String baseUrl;
    final String apiKey;
    /** provider 名；未知厂商（Go 允许任意字符串，Java 枚举表达不了）为 null = Go 的 default 分支。 */
    final ProviderName provider;
    final String appId;
    final String appSecret;
    /** 用户在模型配置里指定的自定义 HTTP 头（类 OpenAI Python SDK 的 extra_headers）。 */
    final Map<String, String> customHeaders;
    /** 仅 Azure 使用：URL 上的 api-version（对照 go-openai config.APIVersion）。 */
    private final String azureApiVersion;

    /** 承载全部厂商特有行为；非 final 以便测试注入（对照 Go 测试直接改 c.adapter）。 */
    private ProviderAdapter adapter;
    /** 来自 extra_config.thinking_control，非 null 时覆盖 adapter.thinking()。 */
    private final ThinkingStrategy thinkingOverride;

    /** 响应解析协作者（对照 openai_stream.go 前半）。 */
    final RemoteApiResponseOps responseOps;

    /** 流式解析协作者（对照 openai_stream.go 流式段）。 */
    final RemoteApiStreamOps streamOps;

    /** HTTP 传输协作者（对照 Go 裸 HTTP 段）。 */
    final RemoteHttpOps httpOps;

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
        this.httpOps = new RemoteHttpOps(this);
        this.streamOps = new RemoteApiStreamOps(this);
        this.responseOps = new RemoteApiResponseOps(this);
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
                return RemoteApiBodyCodec.GO_MARSHAL.writeValueAsBytes(
                        cacheRewritten ? RemoteApiBodyCodec.goSorted(body) : RemoteApiBodyCodec.structSorted(body));
            } catch (IOException e) {
                throw BizException.internal("marshal request: " + e.getMessage());
            }
        }
    }

    /** 薄委托：键序归一路线见 {@link RemoteApiBodyCodec}。 */
    static JsonNode goSorted(JsonNode node) {
        return RemoteApiBodyCodec.goSorted(node);
    }

    /** 薄委托：传输细节见 {@link RemoteHttpOps}。 */
    private HttpResponse<InputStream> sendRequest(Outbound out, Duration timeout, boolean isStream) {
        return httpOps.sendRequest(out, timeout, isStream);
    }

    /** 薄委托：见 {@link RemoteHttpOps#readAll}。 */
    private static String readAll(InputStream in) {
        return RemoteHttpOps.readAll(in);
    }

    /** 薄委托：见 {@link RemoteHttpOps#statusError}。 */
    private static String statusError(HttpResponse<?> resp, String body) {
        return RemoteHttpOps.statusError(resp, body);
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

    /** 薄委托：SSE 消费循环见 {@link RemoteApiStreamOps#processRawHttpStream}。 */
    void processRawHttpStream(InputStream input, BlockingQueue<StreamResponse> streamChan,
                              boolean rawPath) {
        streamOps.processRawHttpStream(input, streamChan, rawPath);
    }

    /** 薄委托：delta 逐块产出顺序见 {@link RemoteApiStreamOps#processStreamDelta}。 */
    void processStreamDelta(JsonNode choice, OpenAiStreamState state,
                            BlockingQueue<StreamResponse> streamChan, String reasoningContent)
            throws InterruptedException {
        streamOps.processStreamDelta(choice, state, streamChan, reasoningContent);
    }

    /** 薄委托：见 {@link RemoteApiStreamOps#applyStreamToolCallMetadata}。 */
    void applyStreamToolCallMetadata(JsonNode chunk, OpenAiStreamState state) {
        streamOps.applyStreamToolCallMetadata(chunk, state);
    }
    /** 薄委托：见 {@link RemoteApiResponseOps#parseCompletionResponse}。 */
    ChatResponse parseCompletionResponse(JsonNode resp) {
        return responseOps.parseCompletionResponse(resp);
    }

    /** 薄委托：见 {@link RemoteApiResponseOps#applyCompletionToolCallMetadata}。 */
    void applyCompletionToolCallMetadata(JsonNode body, ChatResponse result) {
        responseOps.applyCompletionToolCallMetadata(body, result);
    }

    /** 薄委托：见 {@link RemoteApiResponseOps#removeThinkingContent}。 */
    static String removeThinkingContent(String content) {
        return RemoteApiResponseOps.removeThinkingContent(content);
    }
    // ------------------------------------------------------------------
    // 日志与访问器
    // ------------------------------------------------------------------

    /** 对照 Go logUsage：nil 安全的标准用量日志行（无 ctx，purpose/前缀指纹取不到）。 */
    void logUsage(TokenUsage usage) {
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

    static String textOrEmpty(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return "";
        }
        return node.textValue();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
