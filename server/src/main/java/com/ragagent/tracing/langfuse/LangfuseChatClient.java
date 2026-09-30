package com.ragagent.tracing.langfuse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.ToolCall;

/**
 * chat 客户端的 langfuse 装饰器（对照 Go internal/models/chat/langfuse_wrapper.go 全文）：
 * 每次 Chat/ChatStream 发一条 generation 观测（消息输入、模型参数、输出、用量）。
 * 由 {@code ModelRuntimeFactory.getChatModel} 在管理器启用时装配（对照 NewChat 后的
 * {@code wrapChatLangfuse}）。未启用时 {@link #wrap} 原样返回，零成本。
 *
 * <p><b>已知差异（备案）</b>：Go 经 ctx 取 {@code LLMCallMetadataFromContext} 的
 * call_purpose / prompt_prefix_fingerprint；Java 调用点未携带该元数据载体，
 * 两键恒为空串（与 Go 缺省同形，真实取值不产出）。</p>
 */
public final class LangfuseChatClient implements LlmChatClient {

    private static final String PURPOSE = "";
    private static final String PREFIX_FINGERPRINT = "";

    private final LlmChatClient inner;

    LangfuseChatClient(LlmChatClient inner) {
        this.inner = inner;
    }

    /** 对照 wrapChatLangfuse：未启用/空客户端原样返回。 */
    public static LlmChatClient wrap(LlmChatClient client) {
        if (client == null || !LangfuseManager.get().enabled()) {
            return client;
        }
        return new LangfuseChatClient(client);
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return inner.chat(messages, options);
        }
        Generation gen = manager.startGeneration(new LangfuseManager.GenerationOptions(
                "chat.completion", inner.getModelName(), buildMessages(messages),
                buildChatMetadata(inner.getModelId(), false, options),
                buildModelParams(options)));

        ChatResponse response = null;
        String err = null;
        try {
            response = inner.chat(messages, options);
            return response;
        } catch (RuntimeException e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        } finally {
            Object output = null;
            TokenUsage usage = null;
            if (response != null) {
                usage = LangfusePayloads.convertUsage(response.getUsage());
                output = buildGenerationOutput(response.getContent(), response.getReasoningContent(),
                        response.getFinishReason(), response.getToolCalls());
            }
            gen.finish(output, usage, err);
        }
    }

    @Override
    public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return inner.chatStream(messages, options);
        }
        Generation gen = manager.startGeneration(new LangfuseManager.GenerationOptions(
                "chat.completion.stream", inner.getModelName(), buildMessages(messages),
                buildChatMetadata(inner.getModelId(), true, options),
                buildModelParams(options)));

        BlockingQueue<StreamResponse> innerQueue;
        try {
            innerQueue = inner.chatStream(messages, options);
        } catch (RuntimeException e) {
            gen.finish(null, null, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
        if (innerQueue == null) {
            gen.finish(null, null, null);
            return null;
        }

        // 转发线程（对照 Go 的 wrapped channel + goroutine）：累积 content/reasoning/usage/
        // tool_calls/finish_reason，首 token 到达时 MarkCompletionStart，流结束收 generation。
        BlockingQueue<StreamResponse> wrapped = new LinkedBlockingQueue<>();
        Thread.ofVirtual().name("langfuse-chat-stream").start(() -> {
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            com.ragagent.llm.domain.TokenUsage usage = null;
            List<ToolCall> toolCalls = null;
            String finishReason = "";
            boolean firstToken = false;
            try {
                while (true) {
                    StreamResponse resp;
                    try {
                        resp = innerQueue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    ResponseType type = resp.getResponseType();
                    String chunk = resp.getContent() == null ? "" : resp.getContent();
                    if ((type == ResponseType.THINKING || type == ResponseType.ANSWER)
                            && !chunk.isEmpty()) {
                        if (!firstToken) {
                            gen.markCompletionStart();
                            firstToken = true;
                        }
                        if (type == ResponseType.THINKING) {
                            reasoning.append(chunk);
                        } else {
                            content.append(chunk);
                        }
                    }
                    if (resp.getUsage() != null) {
                        usage = resp.getUsage();
                    }
                    if (resp.getToolCalls() != null && !resp.getToolCalls().isEmpty()) {
                        // 快照：下游可能就地改写 parameters（对照 Go 的 snapshotLangfuseToolCalls）
                        toolCalls = List.copyOf(resp.getToolCalls());
                    }
                    if (resp.getFinishReason() != null && !resp.getFinishReason().isEmpty()) {
                        finishReason = resp.getFinishReason();
                    }
                    try {
                        wrapped.put(resp);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (resp.isDone()) {
                        break;
                    }
                }
            } finally {
                gen.finish(buildGenerationOutput(content.toString(), reasoning.toString(),
                        finishReason, toolCalls), LangfusePayloads.convertUsage(usage), null);
            }
        });
        return wrapped;
    }

    @Override
    public String getModelName() {
        return inner.getModelName();
    }

    @Override
    public String getModelId() {
        return inner.getModelId();
    }

    // ── 载荷构造（逐条对照 Go 的 buildLangfuse* 辅助） ──

    /** 对照 buildLangfuseMessages。 */
    static List<Map<String, Object>> buildMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", m.getRole());
            if (m.getContent() != null && !m.getContent().isEmpty()) {
                entry.put("content", m.getContent());
            }
            if (m.getMultiContent() != null && !m.getMultiContent().isEmpty()) {
                entry.put("content", m.getMultiContent());
            }
            if (m.getName() != null && !m.getName().isEmpty()) {
                entry.put("name", m.getName());
            }
            if (m.getToolCallId() != null && !m.getToolCallId().isEmpty()) {
                entry.put("tool_call_id", m.getToolCallId());
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                entry.put("tool_calls", m.getToolCalls());
            }
            if (m.getReasoningContent() != null && !m.getReasoningContent().isEmpty()) {
                entry.put("reasoning_content", m.getReasoningContent());
            }
            out.add(entry);
        }
        return out;
    }

    /** 对照 buildLangfuseChatMetadata（含 MCP 目录截断）。 */
    static Map<String, Object> buildChatMetadata(String modelId, boolean streaming,
                                                 ChatOptions options) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model_id", modelId == null ? "" : modelId);
        meta.put("streaming", streaming);
        meta.put("has_tools", options != null && options.getTools() != null
                && !options.getTools().isEmpty());
        meta.put("call_purpose", PURPOSE);
        meta.put("prompt_prefix_fingerprint", PREFIX_FINGERPRINT);
        if (options == null || options.getTools() == null || options.getTools().isEmpty()) {
            return meta;
        }
        List<String> names = new ArrayList<>(options.getTools().size());
        for (com.ragagent.llm.domain.ChatTool tool : options.getTools()) {
            String name = tool.getFunction() == null ? "" : tool.getFunction().getName();
            names.add(name);
            if ("discover_mcp_tools".equals(name) && tool.getFunction() != null
                    && tool.getFunction().getDescription() != null
                    && !tool.getFunction().getDescription().isEmpty()) {
                meta.put("mcp_catalog", LangfusePayloads.truncateChat(
                        tool.getFunction().getDescription(), LangfusePayloads.MCP_CATALOG_RUNES));
            }
        }
        meta.put("tool_names", names);
        return meta;
    }

    /** 对照 buildLangfuseGenerationOutput（tool_calls 恒在，可能 null）。 */
    static Map<String, Object> buildGenerationOutput(String content, String reasoningContent,
                                                     String finishReason, List<ToolCall> toolCalls) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("content", content == null ? "" : content);
        output.put("tool_calls", toolCalls);
        output.put("finish_reason", finishReason == null ? "" : finishReason);
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            output.put("reasoning_content", reasoningContent);
        }
        return output;
    }

    /** 对照 buildLangfuseModelParams：只带非零参数；空 → null（属性省略）。 */
    static Map<String, Object> buildModelParams(ChatOptions options) {
        if (options == null) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (options.getTemperature() != 0) {
            params.put("temperature", options.getTemperature());
        }
        if (options.getTopP() != 0) {
            params.put("top_p", options.getTopP());
        }
        if (options.completionBudget() > 0) {
            params.put("max_completion_tokens", options.completionBudget());
        }
        if (options.getFrequencyPenalty() != 0) {
            params.put("frequency_penalty", options.getFrequencyPenalty());
        }
        if (options.getPresencePenalty() != 0) {
            params.put("presence_penalty", options.getPresencePenalty());
        }
        if (options.getSeed() != 0) {
            params.put("seed", options.getSeed());
        }
        if (options.getToolChoice() != null && !options.getToolChoice().isEmpty()) {
            params.put("tool_choice", options.getToolChoice());
        }
        return params.isEmpty() ? null : params;
    }
}
