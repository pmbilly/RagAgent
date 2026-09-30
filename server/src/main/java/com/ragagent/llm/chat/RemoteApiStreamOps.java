package com.ragagent.llm.chat;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.domain.ToolCall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流式解析协作者（对照 Go openai_stream.go 流式段，自 {@link RemoteApiChat} 机械搬出）：
 * SSE 逐事件读取、delta 逐块产出、tool_calls 增量累积与厂商元数据回填。
 * 持门面回引用 adapter（可变，测试可替换）/provider/modelName，共享 static
 * {@code textOrEmpty} 与 {@code MAPPER} 经门面类名访问。
 */
final class RemoteApiStreamOps {

    private static final Logger log = LoggerFactory.getLogger(RemoteApiStreamOps.class);

    private final RemoteApiChat service;

    RemoteApiStreamOps(RemoteApiChat service) {
        this.service = service;
    }

    /** 对照 Go 的 thinking 工具名特例（thought 参数增量转成 thinking 分片）。 */
    private static final String THINKING_TOOL_NAME = "thinking";

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
                    chunk = RemoteApiChat.MAPPER.readTree(data);
                } catch (IOException e) {
                    log.error("Failed to parse stream response: {}", e.getMessage());
                    continue;
                }

                JsonNode usageNode = chunk.get("usage");
                if (usageNode != null && !usageNode.isNull()) {
                    TokenUsage usage = PromptCache.tokenUsageFromOpenAI(usageNode, service.provider);
                    PromptCache.applyRawPromptCacheUsage(data, usage);
                    state.usage = usage;
                }

                JsonNode choices = chunk.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty()) {
                    JsonNode choice = choices.get(0);
                    JsonNode delta = choice.path("delta");
                    // 统一获取逻辑（兼容标准 reasoning_content 与 vLLM 的 reasoning）
                    String reasoning = RemoteApiChat.textOrEmpty(delta.get("reasoning"));
                    if (reasoning.isEmpty()) {
                        reasoning = RemoteApiChat.textOrEmpty(delta.get("reasoning_content"));
                    }
                    applyStreamToolCallMetadata(chunk, state);
                    processStreamDelta(choice, state, streamChan, reasoning);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[LLM Stream] interrupted, closing stream for model={}", service.modelName);
        } catch (IOException e) {
            log.debug("[LLM Stream] failed to close stream body for model={}: {}", service.modelName, e.getMessage());
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
        service.logUsage(state.usage);
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
        String finishReason = RemoteApiChat.textOrEmpty(choice.get("finish_reason"));
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

        String content = RemoteApiChat.textOrEmpty(delta.get("content"));
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
                    firstId = RemoteApiChat.textOrEmpty(tc.get("id"));
                }
                if (firstName.isEmpty()) {
                    firstName = RemoteApiChat.textOrEmpty(tc.path("function").get("name"));
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
                entry.setType(RemoteApiChat.textOrEmpty(tc.get("type")));
                entry.getFunction().setName("");
                entry.getFunction().setArguments("");
                state.toolCallMap.put(toolCallIndex, entry);
            }

            String id = RemoteApiChat.textOrEmpty(tc.get("id"));
            if (!id.isEmpty()) {
                entry.setId(id);
            }
            String type = RemoteApiChat.textOrEmpty(tc.get("type"));
            if (!type.isEmpty()) {
                entry.setType(type);
            }

            String incomingName = RemoteApiChat.textOrEmpty(tc.path("function").get("name"));
            if (!incomingName.isEmpty()) {
                // 防御性校验：部分供应商（如 vLLM Ascend）每个流 chunk 重复发送完整工具名，
                // 名字与已存一致时视为冗余重复，不叠加。
                String currentName = entry.getFunction().getName();
                if (!currentName.equals(incomingName)) {
                    entry.getFunction().setName(currentName + incomingName);
                }
            }

            String argsDelta = RemoteApiChat.textOrEmpty(tc.path("function").get("arguments"));
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
            Map<String, JsonNode> metadata = service.adapter().extractToolCallMetadata(rawToolCall);
            if (metadata == null || metadata.isEmpty()) {
                continue;
            }
            int index = rawToolCall.hasNonNull("index") ? rawToolCall.get("index").asInt() : 0;
            state.setToolCallProviderMetadata(index, metadata);
        }
    }

}
