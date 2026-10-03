package com.ragagent.llm.chat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.CacheRetention;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.FunctionCall;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.llm.provider.ProviderBaseURLs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic Messages API 客户端。
 *
 * <p><b>与 OpenAI 路径的四处关键差异</b>：</p>
 * <ol>
 *   <li>{@code max_tokens} <b>硬编码默认 1024</b>，
 *       只有调用方给了补全预算（&gt;0）才覆盖——OpenAI 路径不带默认值；</li>
 *   <li>system 抽成<b>顶层字段</b>（string 或 {@code [{text, cache_control}]}），
 *       不留在 messages 里；</li>
 *   <li>鉴权走 {@code x-api-key} + {@code anthropic-version}，不是 Bearer；</li>
 *   <li>prompt 缓存用 content 上的 {@code cache_control} 断点（走
 *       {@link PromptCache#cacheControlFor}，与 Aliyun 同策略）。</li>
 * </ol>
 *
 * <p>usage 归一化：{@code input_tokens + cache_read_input_tokens + cache_creation_input_tokens}
 * = promptTokens；流式下多次上报按 {@code max()} 合并（不是累加）。</p>
 *
 * <p>错误处理：统一抛 {@link IllegalStateException}，消息前缀形如
 * "send request: "、"API request failed with status %d: "。</p>
 */
public class AnthropicChat implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicChat.class);

    /** anthropic-version 请求头的取值。 */
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * max_tokens 默认值。<b>这是 Anthropic 路径独有的默认值</b>，别跟 OpenAI 路径对齐成"不发"。
     */
    public static final int DEFAULT_MAX_TOKENS = 1024;

    /** 不允许被自定义头覆盖的凭据/协议头名单。 */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "api-key", "x-api-key", "x-goog-api-key", "content-type",
            "content-length", "accept-encoding", "host", "connection", "transfer-encoding");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String modelName;
    private final String modelId;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> customHeaders;

    /**
     * 构造：baseURL 先过 SSRF 校验，API key 必填，
     * 空 baseURL 回退到厂商默认（{@code https://api.anthropic.com/v1}）。
     */
    public AnthropicChat(ChatConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Anthropic provider: config is required");
        }
        String rawBaseUrl = config.getBaseUrl() == null ? "" : config.getBaseUrl();
        if (!rawBaseUrl.isEmpty()) {
            try {
                LlmTransport.validateUrlForSsrf(rawBaseUrl);
            } catch (RuntimeException e) {
                throw new IllegalStateException("baseURL SSRF check failed: " + e.getMessage(), e);
            }
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("Anthropic provider: API key is required");
        }
        String normalized = trimTrailingSlashes(rawBaseUrl);
        this.baseUrl = normalized.isEmpty() ? ProviderBaseURLs.ANTHROPIC_BASE_URL : normalized;
        this.modelName = config.getModelName();
        this.modelId = config.getModelId();
        this.apiKey = config.getApiKey();
        this.customHeaders = config.getCustomHeaders();
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /** 非流式（但服务端可能仍回 SSE，本方法两者都吃）。 */
    @Override
    public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
        AnthropicRequest request = buildRequest(messages, options);
        byte[] jsonData = serialize(request);

        Duration timeout = LlmTransport.withLlmTimeout(null, LlmTransport.DEFAULT_CHAT_TIMEOUT);
        String endpoint = endpoint();
        try {
            LlmTransport.validateUrlForSsrf(endpoint);
        } catch (RuntimeException e) {
            throw new IllegalStateException("endpoint SSRF check failed: " + e.getMessage(), e);
        }

        HttpRequest.Builder builder = newRequestBuilder(endpoint, timeout);
        builder.header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION);
        // 自定义头在标准头之后，非保留头同名覆盖
        applyCustomHeaders(builder, customHeaders);
        HttpResponse<InputStream> resp = send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(jsonData)).build());
        byte[] body = readBody(resp);
        String contentType = headerValue(resp, "Content-Type").toLowerCase(Locale.ROOT);
        int status = resp.statusCode();

        if (contentType.contains("text/event-stream")) {
            ChatResponse chatResp = parseAnthropicSse(new ByteArrayInputStream(body));
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(
                        "API request failed with status " + status + ": " + chatResp.getContent());
            }
            logUsage(chatResp.getUsage());
            return chatResp;
        }

        AnthropicResponse chatResp;
        try {
            chatResp = MAPPER.readValue(body, AnthropicResponse.class);
        } catch (IOException e) {
            throw new IllegalStateException("decode response: " + e.getMessage(), e);
        }
        if (status < 200 || status >= 300) {
            if (chatResp.getError() != null && chatResp.getError().getMessage() != null
                    && !chatResp.getError().getMessage().isEmpty()) {
                throw new IllegalStateException(
                        "API request failed with status " + status + ": " + chatResp.getError().getMessage());
            }
            throw new IllegalStateException(
                    "API request failed with status " + status + ": " + new String(body, StandardCharsets.UTF_8));
        }

        ChatResponse result = parseResponse(chatResp);
        logUsage(result.getUsage());
        return result;
    }

    /**
     * <b>先同步建立连接</b>（建立阶段失败直接抛），
     * 再返回由虚拟线程填充的响应队列。队列以 done=true 的元素收尾。
     */
    @Override
    public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
        AnthropicRequest request = buildRequest(messages, options);
        request.setStream(true);
        byte[] jsonData = serialize(request);

        String endpoint = endpoint();
        try {
            LlmTransport.validateUrlForSsrf(endpoint);
        } catch (RuntimeException e) {
            throw new IllegalStateException("endpoint SSRF check failed: " + e.getMessage(), e);
        }

        // 流式不套兜底超时（超时由调用方的 deadline/取消控制）
        HttpRequest.Builder builder = newRequestBuilder(endpoint, null);
        builder.header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION);
        applyCustomHeaders(builder, customHeaders);
        HttpResponse<InputStream> resp = send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(jsonData)).build());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            byte[] body = readBody(resp);
            throw new IllegalStateException("API request failed with status " + resp.statusCode()
                    + ": " + new String(body, StandardCharsets.UTF_8));
        }

        BlockingQueue<StreamResponse> streamQueue = new LinkedBlockingQueue<>();
        InputStream streamBody = resp.body();
        String model = modelName;
        Thread.ofVirtual().name("anthropic-chat-stream").start(() -> {
            try (streamBody) {
                processAnthropicStream(streamBody, model, streamQueue);
            } catch (IOException e) {
                log.warn("[anthropic-stream] close failed: {}", e.toString());
            }
        });
        return streamQueue;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    // ------------------------------------------------------------------
    // endpoint 拼接
    // ------------------------------------------------------------------

    /**
     * <b>三种形态</b>——
     * 已是 {@code /messages} 结尾 → 直接用；{@code /v1} 或 {@code /v1beta} 结尾 → 补
     * {@code /messages}；其余 → 补 {@code /v1/messages}。
     */
    public String endpoint() {
        String base = trimTrailingSlashes(baseUrl);
        if (isAnthropicMessagesEndpoint(base)) {
            return base;
        }
        if (isAnthropicVersionedBaseUrl(base)) {
            return base + "/messages";
        }
        return base + "/v1/messages";
    }

    /** URL 解析失败一律 false。 */
    static boolean isAnthropicMessagesEndpoint(String baseUrl) {
        String path = urlPath(baseUrl);
        return path != null && path.endsWith("/messages");
    }

    /** {@code /v1} 或 {@code /v1beta} 结尾。 */
    static boolean isAnthropicVersionedBaseUrl(String baseUrl) {
        String path = urlPath(baseUrl);
        return path != null && (path.endsWith("/v1") || path.endsWith("/v1beta"));
    }

    /**
     * 取 URL 的 path 并去掉尾部斜杠；解析不出来返回 null。
     */
    private static String urlPath(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        try {
            String path = new URI(baseUrl).getPath();
            return path == null ? "" : trimTrailingSlashes(path);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 请求体构造
    // ------------------------------------------------------------------

    /**
     * 构造 Anthropic 请求体。
     *
     * <p>system / messages 的三种形态（CacheRetention 决定）：</p>
     * <ul>
     *   <li>NONE：{@code system: "文本"} + 最后一条消息保持纯字符串——<b>不打断点</b>；</li>
     *   <li>否则：system 升格为 {@code [{type,text,cache_control}]}，且<b>最后一条消息若内容是
     *       非空字符串，也升格成内容块</b>（尾断点），TTL 固定 "1h"。</li>
     * </ul>
     */
    public AnthropicRequest buildRequest(List<ChatMessage> messages, ChatOptions opts) {
        AnthropicRequest req = new AnthropicRequest();
        req.setModel(modelName);
        req.setMaxTokens(DEFAULT_MAX_TOKENS);
        req.setMessages(new ArrayList<>());

        if (opts != null) {
            int budget = opts.completionBudget();
            if (budget > 0) {
                req.setMaxTokens(budget);
            }
            if (opts.getTemperature() > 0) {
                req.setTemperature(opts.getTemperature());
            }
            if (opts.getTopP() > 0) {
                req.setTopP(opts.getTopP());
            }
        }

        AnthropicMessages.toolOptions(req, opts);
        AnthropicMessages.Converted converted = AnthropicMessages.messages(messages);
        req.setMessages(converted.messages());

        String systemText = String.join("\n\n", converted.system());
        CacheRetention retention = PromptCache.resolveCacheRetention(opts);
        AnthropicCacheControl marker = AnthropicCacheControl.from(PromptCache.cacheControlFor(retention, "1h"));
        if (marker == null) {
            // system 只要被赋过值（哪怕是空串）就照发 "system":""
            req.setSystem(systemText);
            return req;
        }
        if (!systemText.isEmpty()) {
            AnthropicContentBlock block = AnthropicContentBlock.text(systemText);
            block.setCacheControl(marker);
            req.setSystem(List.of(block));
        }
        if (!req.getMessages().isEmpty()) {
            AnthropicMessage last = req.getMessages().get(req.getMessages().size() - 1);
            if (last.getContent() instanceof String text && !text.isEmpty()) {
                AnthropicContentBlock block = AnthropicContentBlock.text(text);
                block.setCacheControl(marker);
                last.setContent(List.of(block));
            }
        }
        return req;
    }

    // ------------------------------------------------------------------
    // 非流式解析
    // ------------------------------------------------------------------

    /** 解析非流式响应。 */
    public ChatResponse parseResponse(AnthropicResponse resp) {
        List<String> parts = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        if (resp.getContent() != null) {
            for (AnthropicResponse.Block part : resp.getContent()) {
                if (part == null) {
                    continue;
                }
                if (AnthropicContentBlock.TYPE_TOOL_USE.equals(part.getType())) {
                    ToolCall call = new ToolCall();
                    call.setId(part.getId());
                    call.setType("function");
                    call.setFunction(new FunctionCall(part.getName(),
                            part.getInput() == null ? "" : part.getInput().toString()));
                    calls.add(call);
                }
                if (AnthropicContentBlock.TYPE_TEXT.equals(part.getType())
                        && part.getText() != null && !part.getText().isEmpty()) {
                    parts.add(part.getText());
                }
            }
        }

        AnthropicResponse.Usage rawUsage = resp.getUsage();
        int inputTokens = rawUsage == null ? 0 : rawUsage.getInputTokens();
        int outputTokens = rawUsage == null ? 0 : rawUsage.getOutputTokens();
        Integer cacheReadRaw = rawUsage == null ? null : rawUsage.getCacheReadInputTokens();
        Integer cacheWriteRaw = rawUsage == null ? null : rawUsage.getCacheCreationInputTokens();
        int cacheRead = cacheReadRaw == null ? 0 : cacheReadRaw;
        int cacheWrite = cacheWriteRaw == null ? 0 : cacheWriteRaw;
        int promptTokens = inputTokens + cacheRead + cacheWrite;

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(outputTokens);
        usage.setTotalTokens(promptTokens + outputTokens);
        usage.setPromptCacheUsage(cacheRead, cacheWrite, Math.max(0, promptTokens - cacheRead),
                cacheReadRaw != null || cacheWriteRaw != null);

        ChatResponse result = new ChatResponse();
        result.setContent(String.join("", parts));
        result.setFinishReason(new AnthropicToolStream().finishReason(resp.getStopReason()));
        result.setToolCalls(calls);
        result.setUsage(usage);
        return result;
    }

    /**
     * 非流式入口拿到 {@code text/event-stream} 时的整段解析。
     * 与流式路径共用 {@link AnthropicToolStream}，但总量按 {@code max()} 逐次合并。
     */
    static ChatResponse parseAnthropicSse(InputStream reader) {
        SseReader sseReader = new SseReader(reader);
        List<String> contentParts = new ArrayList<>();
        AnthropicToolStream toolStream = new AnthropicToolStream();
        String finishReason = "";
        int inputTokens = 0;
        int outputTokens = 0;
        int cacheReadTokens = 0;
        int cacheWriteTokens = 0;
        boolean cacheReported = false;

        while (true) {
            Optional<SseReader.SseEvent> next;
            try {
                next = sseReader.readEvent();
            } catch (IOException e) {
                throw new IllegalStateException("read SSE response: " + e.getMessage(), e);
            }
            if (next.isEmpty()) {
                break;
            }
            SseReader.SseEvent event = next.get();
            if (event.done()) {
                break;
            }
            if (event.data() == null || event.data().length == 0) {
                continue;
            }

            AnthropicStreamEvent streamEvent = decodeEvent(event);
            if (streamEvent.getError() != null && streamEvent.getError().getMessage() != null
                    && !streamEvent.getError().getMessage().isEmpty()) {
                throw new IllegalStateException("API stream error: " + streamEvent.getError().getMessage());
            }
            toolStream.consume(streamEvent);

            if (streamEvent.getMessage() != null && streamEvent.getMessage().getUsage() != null) {
                AnthropicResponse.Usage u = streamEvent.getMessage().getUsage();
                inputTokens = Math.max(inputTokens, u.getInputTokens());
                outputTokens = Math.max(outputTokens, u.getOutputTokens());
                CacheCounters merged = mergeAnthropicCacheCounters(cacheReadTokens, cacheWriteTokens,
                        cacheReported, u.getCacheReadInputTokens(), u.getCacheCreationInputTokens());
                cacheReadTokens = merged.read();
                cacheWriteTokens = merged.write();
                cacheReported = merged.reported();
            }
            if (streamEvent.getDelta() != null) {
                if (AnthropicStreamEvent.DELTA_TEXT.equals(streamEvent.getDelta().getType())
                        && streamEvent.getDelta().getText() != null && !streamEvent.getDelta().getText().isEmpty()) {
                    contentParts.add(streamEvent.getDelta().getText());
                }
                if (streamEvent.getDelta().getStopReason() != null
                        && !streamEvent.getDelta().getStopReason().isEmpty()) {
                    finishReason = streamEvent.getDelta().getStopReason();
                }
            }
            if (streamEvent.getUsage() != null) {
                AnthropicResponse.Usage u = streamEvent.getUsage();
                inputTokens = Math.max(inputTokens, u.getInputTokens());
                outputTokens = Math.max(outputTokens, u.getOutputTokens());
                CacheCounters merged = mergeAnthropicCacheCounters(cacheReadTokens, cacheWriteTokens,
                        cacheReported, u.getCacheReadInputTokens(), u.getCacheCreationInputTokens());
                cacheReadTokens = merged.read();
                cacheWriteTokens = merged.write();
                cacheReported = merged.reported();
            }
        }

        int promptTokens = inputTokens + cacheReadTokens + cacheWriteTokens;
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(outputTokens);
        usage.setTotalTokens(promptTokens + outputTokens);
        usage.setPromptCacheUsage(cacheReadTokens, cacheWriteTokens,
                Math.max(0, promptTokens - cacheReadTokens), cacheReported);

        ChatResponse result = new ChatResponse();
        result.setContent(String.join("", contentParts));
        result.setFinishReason(toolStream.finishReason(finishReason));
        result.setToolCalls(toolStream.calls());
        result.setUsage(usage);
        return result;
    }

    /**
     * 逐事件转发到队列，末尾一定补一个 done=true 的终态块
     * （出错时改写成 ERROR + finish_reason="incomplete"，用量与工具调用照带）。
     */
    static void processAnthropicStream(InputStream body, String model, BlockingQueue<StreamResponse> streamQueue) {
        SseReader sseReader = new SseReader(body);
        TokenUsage usage = null;
        String finishReason = "";
        AnthropicToolStream toolStream = new AnthropicToolStream();

        while (true) {
            Optional<SseReader.SseEvent> next;
            try {
                next = sseReader.readEvent();
            } catch (IOException e) {
                finish(streamQueue, model, usage, toolStream, finishReason,
                        new IllegalStateException("read SSE response: " + e.getMessage(), e));
                return;
            }
            if (next.isEmpty()) {
                finish(streamQueue, model, usage, toolStream, finishReason, null);
                return;
            }
            SseReader.SseEvent event = next.get();
            if (event.done()) {
                finish(streamQueue, model, usage, toolStream, finishReason, null);
                return;
            }
            if (event.data() == null || event.data().length == 0) {
                continue;
            }

            AnthropicStreamEvent streamEvent;
            try {
                streamEvent = decodeEvent(event);
            } catch (RuntimeException e) {
                finish(streamQueue, model, usage, toolStream, finishReason, e);
                return;
            }
            if (streamEvent.getError() != null && streamEvent.getError().getMessage() != null
                    && !streamEvent.getError().getMessage().isEmpty()) {
                finish(streamQueue, model, usage, toolStream, finishReason,
                        new IllegalStateException("API stream error: " + streamEvent.getError().getMessage()));
                return;
            }
            toolStream.consume(streamEvent);

            if (AnthropicStreamEvent.TYPE_CONTENT_BLOCK_START.equals(streamEvent.getType())
                    && streamEvent.getContentBlock() != null
                    && AnthropicContentBlock.TYPE_TOOL_USE.equals(streamEvent.getContentBlock().getType())) {
                AnthropicContentBlock block = streamEvent.getContentBlock();
                StreamResponse chunk = StreamResponse.of(ResponseType.TOOL_CALL, "", false);
                // data 是 map 时按键字母序序列化，故这里用有序 map 且按序插入
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("tool_call_id", nullSafe(block.getId()));
                data.put("tool_name", nullSafe(block.getName()));
                chunk.setData(data);
                if (!put(streamQueue, chunk)) {
                    return;
                }
            }
            if (streamEvent.getMessage() != null && streamEvent.getMessage().getUsage() != null) {
                AnthropicResponse.Usage u = streamEvent.getMessage().getUsage();
                usage = mergeAnthropicUsage(usage, u.getInputTokens(), u.getOutputTokens(),
                        u.getCacheReadInputTokens(), u.getCacheCreationInputTokens());
            }
            if (streamEvent.getDelta() != null) {
                if (streamEvent.getDelta().getStopReason() != null && !streamEvent.getDelta().getStopReason().isEmpty()) {
                    finishReason = streamEvent.getDelta().getStopReason();
                }
                if (AnthropicStreamEvent.DELTA_TEXT.equals(streamEvent.getDelta().getType())
                        && streamEvent.getDelta().getText() != null && !streamEvent.getDelta().getText().isEmpty()) {
                    if (!put(streamQueue, StreamResponse.of(
                            ResponseType.ANSWER, streamEvent.getDelta().getText(), false))) {
                        return;
                    }
                }
            }
            if (streamEvent.getUsage() != null) {
                AnthropicResponse.Usage u = streamEvent.getUsage();
                usage = mergeAnthropicUsage(usage, u.getInputTokens(), u.getOutputTokens(),
                        u.getCacheReadInputTokens(), u.getCacheCreationInputTokens());
            }
            if (AnthropicStreamEvent.TYPE_MESSAGE_STOP.equals(streamEvent.getType())) {
                finish(streamQueue, model, usage, toolStream, finishReason, null);
                return;
            }
        }
    }

    /** 补发流末尾的终态块。 */
    private static void finish(BlockingQueue<StreamResponse> streamQueue, String model, TokenUsage usage,
                               AnthropicToolStream toolStream, String finishReason, RuntimeException error) {
        StreamResponse chunk = new StreamResponse();
        chunk.setResponseType(ResponseType.ANSWER);
        chunk.setDone(true);
        chunk.setUsage(usage);
        chunk.setToolCalls(toolStream.calls());
        chunk.setFinishReason(toolStream.finishReason(finishReason));
        if (error != null) {
            chunk.setResponseType(ResponseType.ERROR);
            chunk.setContent(error.getMessage());
            chunk.setFinishReason(StreamResponse.FINISH_REASON_INCOMPLETE);
        }
        logUsageStatic(model, usage);
        put(streamQueue, chunk);
    }

    // ------------------------------------------------------------------
    // usage 合并
    // ------------------------------------------------------------------

    /** 缓存读写计数与"是否上报过"标记的合并结果。 */
    record CacheCounters(int read, int write, boolean reported) {
    }

    /**
     * 缓存计数按 max() 合并，reported 取 OR
     * （<b>任一</b>上报过就算上报过——nil 与 0 的区别在这里兑现）。
     */
    static CacheCounters mergeAnthropicCacheCounters(int currentRead, int currentWrite, boolean currentReported,
                                                     Integer cacheRead, Integer cacheWrite) {
        int read = currentRead;
        int write = currentWrite;
        boolean reported = currentReported || cacheRead != null || cacheWrite != null;
        if (cacheRead != null) {
            read = Math.max(read, cacheRead);
        }
        if (cacheWrite != null) {
            write = Math.max(write, cacheWrite);
        }
        return new CacheCounters(read, write, reported);
    }

    /**
     * 流式下的增量合并。
     *
     * <p><b>不是简单累加</b>：promptTokens 由"已见的最大未缓存输入 + 合并后的缓存读写"重算，
     * completionTokens 取 max——因为同一份用量会在 message_start 与 message_delta 里各报一次。</p>
     */
    static TokenUsage mergeAnthropicUsage(TokenUsage current, int inputTokens, int outputTokens,
                                          Integer cacheRead, Integer cacheWrite) {
        if (current == null) {
            current = new TokenUsage();
        }
        CacheCounters merged = mergeAnthropicCacheCounters(
                current.getCacheReadTokens(), current.getCacheWriteTokens(), current.isCacheReported(),
                cacheRead, cacheWrite);
        int read = merged.read();
        int write = merged.write();

        int uncachedInput = Math.max(0,
                current.getPromptTokens() - current.getCacheReadTokens() - current.getCacheWriteTokens());
        uncachedInput = Math.max(uncachedInput, inputTokens);

        int promptTokens = uncachedInput + read + write;
        int completionTokens = Math.max(current.getCompletionTokens(), outputTokens);
        current.setPromptTokens(promptTokens);
        current.setCompletionTokens(completionTokens);
        current.setTotalTokens(promptTokens + completionTokens);
        current.setPromptCacheUsage(read, write, Math.max(0, promptTokens - read), merged.reported());
        return current;
    }

    // ------------------------------------------------------------------
    // HTTP 细节
    // ------------------------------------------------------------------

    /** 建 builder（只带超时，头部顺序由调用方拼：标准头 → 自定义头）。 */
    private static HttpRequest.Builder newRequestBuilder(String endpoint, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint));
        if (timeout != null) {
            builder.timeout(timeout);
        }
        return builder;
    }

    /**
     * 跳过保留头（否则会破坏鉴权/签名），其余同名覆盖。JDK 的 HttpClient 对
     * Connection/Content-Length/Expect/Host/Upgrade 等头部直接抛
     * IllegalArgumentException，漏网的（Expect/Upgrade）按"发不出去"处理，记 debug 日志。
     */
    static void applyCustomHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().strip();
            if (name.isEmpty() || RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                builder.setHeader(name, entry.getValue());
            } catch (IllegalArgumentException e) {
                log.debug("[anthropic] custom header {} rejected by JDK http client: {}", name, e.toString());
            }
        }
    }

    private static HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return LlmTransport.send(request);
        } catch (IOException e) {
            // getMessage() 可能为 null（如 EOFException），兜底用异常类名。
            throw new IllegalStateException("send request: " + ioDetail(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("send request: " + ioDetail(e), e);
        }
    }

    private static String ioDetail(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static byte[] readBody(HttpResponse<InputStream> resp) {
        try (InputStream body = resp.body()) {
            return body.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("read response: " + e.getMessage(), e);
        }
    }

    private static String headerValue(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).orElse("");
    }

    static byte[] serialize(AnthropicRequest request) {
        try {
            return MAPPER.writeValueAsBytes(request);
        } catch (IOException e) {
            throw new IllegalStateException("marshal request: " + e.getMessage(), e);
        }
    }

    private static AnthropicStreamEvent decodeEvent(SseReader.SseEvent event) {
        try {
            AnthropicStreamEvent parsed = MAPPER.readValue(event.data(), AnthropicStreamEvent.class);
            return parsed == null ? new AnthropicStreamEvent() : parsed;
        } catch (IOException e) {
            throw new IllegalStateException("decode SSE response: " + e.getMessage(), e);
        }
    }

    /** 可中断的入队。 */
    private static boolean put(BlockingQueue<StreamResponse> queue, StreamResponse chunk) {
        try {
            queue.put(chunk);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 去掉全部尾部斜杠。 */
    static String trimTrailingSlashes(String value) {
        if (value == null) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private void logUsage(TokenUsage usage) {
        logUsageStatic(modelName, usage);
    }

    /** 标准用量日志行。 */
    private static void logUsageStatic(String model, TokenUsage usage) {
        if (usage == null || !log.isInfoEnabled()) {
            return;
        }
        log.info("[LLM Usage] model={}, prompt_tokens={}, completion_tokens={}, total_tokens={}, "
                        + "cache_read_tokens={}, cache_write_tokens={}, cache_miss_tokens={}, "
                        + "cache_hit_rate={}%, cache_reported={}, cache_status={}",
                model, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                usage.getCacheReadTokens(), usage.getCacheWriteTokens(), usage.getCacheMissTokens(),
                String.format(Locale.ROOT, "%.1f", usage.promptCacheHitRate()),
                usage.isCacheReported(), usage.getCacheStatus());
    }
}
