package com.ragagent.llm.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.FunctionCall;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.llm.ollama.OllamaChatRequest;
import com.ragagent.llm.ollama.OllamaChatResponse;
import com.ragagent.llm.ollama.OllamaMessage;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.llm.ollama.OllamaTool;
import com.ragagent.llm.ollama.OllamaToolCall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地 Ollama 聊天客户端（对照 Go chat.OllamaChat，
 * internal/models/chat/ollama.go 全文）。
 *
 * <p><b>与 OpenAI 路径的关键差异（最容易翻错）：</b></p>
 * <ol>
 *   <li>补全预算走 {@code options.num_predict}（不是 max_tokens）；思考开关走
 *       {@code think}；响应格式 {@code format} 直接透传；</li>
 *   <li>流式回调<b>一次给一个完整 block（不是增量 token）</b>——Ollama 的工具调用与
 *       thinking 都是整块到达；</li>
 *   <li>工具调用没有语义化 ID：出站把 ID 解析回整数 {@code index}，入站用
 *       {@code index} 转字符串当 ID（{@link #tooli2s}）；</li>
 *   <li>用量取 {@code prompt_eval_count}/{@code eval_count}，且<b>永远
 *       {@link TokenUsage#markPromptCacheUnsupported()}</b>（Ollama 不上报服务端 prompt 缓存）；</li>
 *   <li>{@code finish_reason} <b>不设置</b>（Go 侧全程留空）。</li>
 * </ol>
 *
 * <p><b>⚠️ 非流式与流式路径的 completionTokens 算法不一致，这是 Go 侧既有行为，
 * 两边照抄、不要统一：</b>非流式 {@code eval_count - prompt_eval_count}，
 * 流式直接 {@code eval_count}。</p>
 *
 * <p>图片：user 消息的 Images 走 {@link ImageResolver#resolveImageForOllama}（含 SSRF 校验、
 * 30s 超时、20MB 上限），解析失败的单张直接跳过。</p>
 */
public class OllamaChat implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaChat.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String modelName;
    private final String modelId;
    private final OllamaService ollamaService;

    /**
     * 对照 Go NewOllamaChat。<b>注意：与 Go 一样忽略 config.BaseURL</b>——
     * Ollama 的基址只来自 {@link OllamaService}（OLLAMA_BASE_URL 环境变量）。
     */
    public OllamaChat(ChatConfig config, OllamaService ollamaService) {
        this.modelName = config == null ? null : config.getModelName();
        this.modelId = config == null ? null : config.getModelId();
        this.ollamaService = ollamaService;
    }

    // ------------------------------------------------------------------
    // 对外接口（对照 ollama.go:126-294）
    // ------------------------------------------------------------------

    /**
     * 对照 Go OllamaChat.Chat：非流式。
     *
     * <p>Content 为空但 Thinking 有内容时，用 Thinking 兜底当答案
     * （推理模型没正确配置 thinking 参数时的补救）。</p>
     */
    @Override
    public ChatResponse chat(List<ChatMessage> messages, ChatOptions opts) {
        ensureModelAvailable();

        OllamaChatRequest chatReq = buildChatRequest(messages, opts, false);
        log.info("发送聊天请求到模型 {}", modelName);

        String[] responseContent = {""};
        java.util.concurrent.atomic.AtomicReference<List<ToolCall>> toolCalls =
                new java.util.concurrent.atomic.AtomicReference<>(new ArrayList<>());
        int[] promptTokens = {0};
        int[] completionTokens = {0};

        try {
            ollamaService.chat(chatReq, resp -> {
                OllamaMessage message = resp.getMessage();
                String content = message == null || message.getContent() == null ? "" : message.getContent();
                if (content.isEmpty() && message != null && message.getThinking() != null) {
                    content = message.getThinking();
                }
                responseContent[0] = content;
                toolCalls.set(toolCallTo(message == null ? null : message.getToolCalls()));

                // 注意：非流式用 eval_count - prompt_eval_count 当补全量（Go 侧既有口径）
                if (resp.getEvalCount() > 0) {
                    promptTokens[0] = resp.getPromptEvalCount();
                    completionTokens[0] = resp.getEvalCount() - promptTokens[0];
                }
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("聊天请求失败: " + e.getMessage(), e);
        }

        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(promptTokens[0]);
        usage.setCompletionTokens(completionTokens[0]);
        usage.setTotalTokens(promptTokens[0] + completionTokens[0]);
        usage.markPromptCacheUnsupported();
        logUsage(usage);

        ChatResponse result = new ChatResponse();
        result.setContent(responseContent[0]);
        result.setToolCalls(toolCalls.get());
        result.setUsage(usage);
        // finish_reason 留空（对照 Go：ChatResponse 的该字段从未赋值）
        return result;
    }

    /**
     * 对照 Go OllamaChat.ChatStream：模型可用性检查在<b>建立阶段同步做</b>（失败直接抛），
     * 之后由虚拟线程把块推进队列，出错时补一个 ERROR + done=true。
     */
    @Override
    public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions opts) {
        ensureModelAvailable();

        OllamaChatRequest chatReq = buildChatRequest(messages, opts, true);
        log.info("发送流式聊天请求到模型 {}", modelName);

        BlockingQueue<StreamResponse> streamQueue = new LinkedBlockingQueue<>();
        String model = modelName;

        Thread.ofVirtual().name("ollama-chat-stream").start(() -> {
            // 与 Go 一样，thinking 的记账跨回调实例存活（"还欠一个 thinking-done"）
            ThinkingEmitter thinking = new ThinkingEmitter();
            try {
                ollamaService.chat(chatReq, resp -> {
                    if (Thread.currentThread().isInterrupted()) {
                        return; // 消费者已放弃本流
                    }
                    OllamaMessage message = resp.getMessage();

                    // 思考分片（Qwen3 / DeepSeek 等推理模型）
                    if (message != null && message.getThinking() != null && !message.getThinking().isEmpty()) {
                        thinkingEmit(thinking, streamQueue, message.getThinking());
                    }

                    if (message != null && message.getContent() != null && !message.getContent().isEmpty()) {
                        // 思考阶段结束：在第一个答案 token 之前补上唯一的 thinking-done
                        thinkingFinish(thinking, streamQueue);
                        put(streamQueue, StreamResponse.of(ResponseType.ANSWER, message.getContent(), false));
                    }

                    if (message != null && message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                        StreamResponse toolChunk = StreamResponse.of(ResponseType.TOOL_CALL, "", false);
                        toolChunk.setToolCalls(toolCallTo(message.getToolCalls()));
                        put(streamQueue, toolChunk);

                        // Ollama 的工具调用是完整对象到达（不是增量分片）。
                        // 记一条警告，便于追踪"思考没有逐 token 流到前端"的现象。
                        for (OllamaToolCall tc : message.getToolCalls()) {
                            if ("thinking".equals(tc.getFunction().getName())) {
                                log.warn("[Ollama Stream] Tool \"{}\" arrived non-incrementally ({} bytes args), "
                                                + "thought will not be token-streamed to frontend",
                                        tc.getFunction().getName(), argumentBytes(tc));
                            }
                        }

                        for (OllamaToolCall tc : message.getToolCalls()) {
                            if (!"thinking".equals(tc.getFunction().getName())) {
                                continue;
                            }
                            ObjectNode argsMap = tc.getFunction().getArguments();
                            JsonNode thought = argsMap == null ? null : argsMap.get("thought");
                            if (thought != null && thought.isTextual() && !thought.asText().isEmpty()) {
                                StreamResponse thoughtChunk =
                                        StreamResponse.of(ResponseType.THINKING, thought.asText(), false);
                                // 对照 Go 的 map[string]interface{}：键按字母序序列化（约定 §9）
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("source", "thinking_tool");
                                data.put("tool_call_id", tooli2s(tc.getFunction().getIndex()));
                                thoughtChunk.setData(data);
                                put(streamQueue, thoughtChunk);
                            }
                        }
                    }

                    if (resp.isDone()) {
                        TokenUsage usage = null;
                        if (resp.getPromptEvalCount() > 0 || resp.getEvalCount() > 0) {
                            usage = new TokenUsage();
                            usage.setPromptTokens(resp.getPromptEvalCount());
                            // 流式路径直接用 eval_count（与上面的非流式口径不一致，照抄 Go）
                            usage.setCompletionTokens(resp.getEvalCount());
                            usage.setTotalTokens(resp.getPromptEvalCount() + resp.getEvalCount());
                            usage.markPromptCacheUnsupported();
                        }
                        logUsage(usage);
                        StreamResponse doneChunk = StreamResponse.of(ResponseType.ANSWER, "", true);
                        doneChunk.setUsage(usage);
                        put(streamQueue, doneChunk);
                    }
                });
            } catch (RuntimeException e) {
                log.error("流式聊天请求失败: {}", e.toString());
                put(streamQueue, StreamResponse.of(ResponseType.ERROR, e.getMessage(), true));
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

    /** 对照 Go {@code c.ensureModelAvailable(ctx)}：每次调用都先确保模型就绪（必要时拉取）。 */
    void ensureModelAvailable() {
        log.info("确保模型 {} 可用", modelName);
        ollamaService.ensureModelAvailable(modelName);
    }

    // ------------------------------------------------------------------
    // 请求体构造（对照 ollama.go:36-123）
    // ------------------------------------------------------------------

    /** 对照 Go buildChatRequest：{@code isStream} 决定 stream 标志。 */
    OllamaChatRequest buildChatRequest(List<ChatMessage> messages, ChatOptions opts, boolean isStream) {
        OllamaChatRequest chatReq = new OllamaChatRequest();
        chatReq.setModel(modelName);
        chatReq.setMessages(convertMessages(messages));
        chatReq.setStream(isStream);

        if (opts != null) {
            // 注意：temperature 无条件塞进去（Go 里即 0 也会发送），
            // Go 的 encoding/json 把 0.0 写成 0，Jackson 写成 0.0——数值语义相同
            chatReq.putOption("temperature", opts.getTemperature());
            if (opts.getTopP() > 0) {
                chatReq.putOption("top_p", opts.getTopP());
            }
            int budget = opts.completionBudget();
            if (budget > 0) {
                // 补全预算在 Ollama 里叫 num_predict（不是 max_tokens）
                chatReq.putOption("num_predict", budget);
            }
            if (opts.getThinking() != null) {
                chatReq.setThink(opts.getThinking());
            }
            if (opts.getFormat() != null) {
                chatReq.setFormat(opts.getFormat());
            }
            if (opts.getTools() != null && !opts.getTools().isEmpty()) {
                chatReq.setTools(toolFrom(opts.getTools()));
            }
        }
        return chatReq;
    }

    /** 对照 Go convertMessages：tool 角色带 tool_name；图片只取 user 消息。 */
    List<OllamaMessage> convertMessages(List<ChatMessage> messages) {
        List<OllamaMessage> ollamaMessages = new ArrayList<>();
        if (messages == null) {
            return ollamaMessages;
        }
        for (ChatMessage msg : messages) {
            OllamaMessage converted = new OllamaMessage(msg.getRole(), msg.getContent());
            converted.setToolCalls(toolCallFrom(msg.getToolCalls()));
            if ("tool".equals(msg.getRole())) {
                converted.setToolName(msg.getName());
            }
            if (msg.getImages() != null && !msg.getImages().isEmpty() && "user".equals(msg.getRole())) {
                List<byte[]> images = new ArrayList<>();
                for (String imgUrl : msg.getImages()) {
                    byte[] imgData = ImageResolver.resolveImageForOllama(imgUrl);
                    if (imgData != null) {
                        images.add(imgData);
                    }
                }
                if (!images.isEmpty()) {
                    converted.setImages(images);
                }
            }
            ollamaMessages.add(converted);
        }
        return ollamaMessages;
    }

    /**
     * 对照 Go toolFrom。schema 直接透传（不做 Go SDK 那套强类型结构的丢字段往返，
     * 见 {@link OllamaTool} 的类注释）。
     */
    List<OllamaTool> toolFrom(List<com.ragagent.llm.domain.ChatTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<OllamaTool> ollamaTools = new ArrayList<>(tools.size());
        for (com.ragagent.llm.domain.ChatTool tool : tools) {
            var function = tool.getFunction();
            OllamaTool.Function fn = new OllamaTool.Function(
                    function == null ? "" : function.getName(),
                    function == null ? null : function.getDescription(),
                    function == null ? null : function.getParameters());
            ollamaTools.add(new OllamaTool(tool.getType(), fn));
        }
        return ollamaTools;
    }

    // ------------------------------------------------------------------
    // 工具调用互转（对照 ollama.go:349-398）
    // ------------------------------------------------------------------

    /** 对照 Go toolCallFrom：ID 解析回整数 index（非数字 → 0），arguments 解析成对象。 */
    List<OllamaToolCall> toolCallFrom(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        List<OllamaToolCall> ollamaToolCalls = new ArrayList<>(toolCalls.size());
        for (ToolCall tc : toolCalls) {
            String name = tc.getFunction() == null ? "" : tc.getFunction().getName();
            String arguments = tc.getFunction() == null ? "" : tc.getFunction().getArguments();
            OllamaToolCall call = new OllamaToolCall();
            OllamaToolCall.Function function = new OllamaToolCall.Function();
            function.setIndex(tools2i(tc.getId()));
            function.setName(name);
            function.setArguments(parseArguments(arguments));
            call.setFunction(function);
            ollamaToolCalls.add(call);
        }
        return ollamaToolCalls;
    }

    /** 对照 Go toolCallTo：ID 用 function.index 的十进制字符串（Ollama 没有语义化 ID）。 */
    List<ToolCall> toolCallTo(List<OllamaToolCall> ollamaToolCalls) {
        if (ollamaToolCalls == null || ollamaToolCalls.isEmpty()) {
            return null;
        }
        List<ToolCall> toolCalls = new ArrayList<>(ollamaToolCalls.size());
        for (OllamaToolCall tc : ollamaToolCalls) {
            OllamaToolCall.Function function = tc.getFunction();
            ToolCall call = new ToolCall();
            call.setId(tooli2s(function == null ? 0 : function.getIndex()));
            call.setType("function");
            call.setFunction(new FunctionCall(
                    function == null ? "" : function.getName(),
                    function == null || function.getArguments() == null ? "{}" : function.getArguments().toString()));
            toolCalls.add(call);
        }
        return toolCalls;
    }

    /** 对照 Go {@code strconv.Itoa(i)}。 */
    static String tooli2s(int i) {
        return Integer.toString(i);
    }

    /** 对照 Go {@code strconv.Atoi(s)} 忽略了错误的那一面：非数字一律 0。 */
    static int tools2i(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 对照 Go 的 {@code args.UnmarshalJSON} + 被忽略的 error：
     * 解析不出对象就给空对象（Go 的 map 在失败时也保持"已初始化的空 map"）。
     */
    private static ObjectNode parseArguments(String arguments) {
        ObjectNode empty = MAPPER.createObjectNode();
        if (arguments == null || arguments.isEmpty()) {
            return empty;
        }
        try {
            JsonNode node = MAPPER.readTree(arguments);
            return node instanceof ObjectNode objectNode ? objectNode : empty;
        } catch (Exception e) {
            return empty;
        }
    }

    private static int argumentBytes(OllamaToolCall tc) {
        try {
            return MAPPER.writeValueAsBytes(tc.getFunction().getArguments()).length;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private void logUsage(TokenUsage usage) {
        if (usage == null || !log.isInfoEnabled()) {
            return;
        }
        log.info("[LLM Usage] model={}, prompt_tokens={}, completion_tokens={}, total_tokens={}, "
                        + "cache_read_tokens={}, cache_write_tokens={}, cache_miss_tokens={}, "
                        + "cache_reported={}, cache_status={}",
                modelName, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                usage.getCacheReadTokens(), usage.getCacheWriteTokens(), usage.getCacheMissTokens(),
                usage.isCacheReported(), usage.getCacheStatus());
    }

    /** 对照 Go {@code ch <- chunk}（阻塞写；被中断则标记线程并让后续回调短路）。 */
    private static void put(BlockingQueue<StreamResponse> queue, StreamResponse chunk) {
        try {
            queue.put(chunk);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 对照 Go {@code thinking.emit(streamChan, ...)}。 */
    private static void thinkingEmit(ThinkingEmitter thinking, BlockingQueue<StreamResponse> queue, String content) {
        try {
            thinking.emit(queue, content);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 对照 Go {@code thinking.finish(streamChan)}。 */
    private static void thinkingFinish(ThinkingEmitter thinking, BlockingQueue<StreamResponse> queue) {
        try {
            thinking.finish(queue);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
