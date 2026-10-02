package com.ragagent.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.event.payload.AgentFinalAnswerData;
import com.ragagent.event.payload.AgentThoughtData;
import com.ragagent.event.payload.AgentToolCallData;
import com.ragagent.event.Event;
import com.ragagent.event.EventIds;
import com.ragagent.event.EventType;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.agent.compaction.CompactionOverflow;
import com.ragagent.agent.domain.AgentState;
import com.ragagent.agent.tools.MessageSanitizer;
import com.ragagent.agent.tools.ThinkBlocks;
import com.ragagent.agent.tools.ThinkStreamSplitter;
import com.ragagent.agent.modelcontext.StreamDecoder;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;

/**
 * ReAct「Think」段的协作者：LLM 流式调用与事件直播——流消费与停顿看门狗、
 * 瞬态重试与压缩重试、思考/答案通道拆分直播。
 *
 * <p>持有 {@link AgentEngine} 回引以访问引擎字段与跨段协作
 * （压缩走 observe、兜底合成走 finalize）；本类不得独立实例化。</p>
 */
final class ThinkPhase {

    private static final Logger log = LoggerFactory.getLogger(ThinkPhase.class);

    private final AgentEngine engine;

    ThinkPhase(AgentEngine engine) {
        this.engine = engine;
    }

    /** 流式 LLM 调用的累计输出。 */
    static final class StreamLLMResult {
        String content = "";
        String reasoningContent = "";
        List<com.ragagent.llm.domain.ToolCall> toolCalls;
        TokenUsage usage;
        String finishReason = "";
        String streamError = "";
    }

    /** think 阶段的分片发射回调。 */
    interface ThinkChunkEmitter {
        void accept(StreamResponse chunk, String fullContent);
    }

    /**
     * LLM 流经 EventBus 直发。emit 为 null 只累计不发射。
     * 流错误（含停顿）→ 抛 {@link AgentEngineException}（message = "LLM stream error: ..."）。
     */
    StreamLLMResult streamLLMToEventBus(List<ChatMessage> messages, ChatOptions opts,
            ThinkChunkEmitter emit) {
        log.debug("[Agent][Stream] Starting LLM stream with {} messages", messages.size());

        // Model-context 编码独占 codec 顺序与临时句柄生命周期。
        List<ChatMessage> encoded = engine.modelContext.encodeMessages(messages);
        java.util.concurrent.BlockingQueue<StreamResponse> stream;
        try {
            stream = engine.chatModel.chatStream(encoded, opts);
        } catch (RuntimeException e) {
            log.error("[Agent][Stream] Failed to start LLM stream: {}",
                    e.getMessage() == null ? e.toString() : e.getMessage());
            throw new AgentEngineException(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        StreamLLMResult result = new StreamLLMResult();
        int[] chunkCount = {0};
        Map<String, Integer> responseTypeCounts = new HashMap<>();
        StreamDecoder answerDecoder = engine.modelContext.streamDecoder();
        StreamDecoder thinkingDecoder = engine.modelContext.streamDecoder();

        // 看门狗在请求发出前置位——time-to-first-token 也在约束内。
        Duration stallTimeout = engine.getLLMStallTimeout();
        AtomicLong lastChunkAt = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean(false);

        while (true) {
            StreamResponse chunk = pollChunk(stream, stallTimeout, lastChunkAt, stalled);
            if (chunk == null) {
                break; // 停顿看门狗触发：流已被"取消"。
            }
            lastChunkAt.set(System.nanoTime());
            chunkCount[0]++;
            responseTypeCounts.merge(
                    chunk.getResponseType() == null ? "" : chunk.getResponseType().value(), 1, Integer::sum);

            // 流内错误：内容不进 result.Content（会漏给用户当成答案），但错误块上搭载的
            // 工具调用与 finish_reason 是 provider 断流前已拼好的部分调用，保留供日志与推理。
            if (chunk.getResponseType() == ResponseType.ERROR) {
                result.streamError = chunk.getContent();
                if (chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty()) {
                    result.toolCalls = chunk.getToolCalls();
                }
                if (chunk.getFinishReason() != null && !chunk.getFinishReason().isEmpty()) {
                    result.finishReason = chunk.getFinishReason();
                }
                if (chunk.isDone()) {
                    break;
                }
                continue;
            }
            if (chunk.getResponseType() == ResponseType.THINKING) {
                chunk.setContent(thinkingDecoder.feed(chunk.getContent()));
                if (chunk.isDone()) {
                    chunk.setContent(chunk.getContent() + thinkingDecoder.flush());
                }
            } else {
                chunk.setContent(answerDecoder.feed(chunk.getContent()));
                if (chunk.isDone()) {
                    chunk.setContent(chunk.getContent() + answerDecoder.flush());
                }
            }
            if (chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty()) {
                engine.modelContext.decodeToolCalls(chunk.getToolCalls());
            }

            if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                boolean isExtracted = chunk.getData() != null && chunk.getData().get("source") != null;
                if (!isExtracted) {
                    if (chunk.getResponseType() == ResponseType.THINKING) {
                        result.reasoningContent += chunk.getContent();
                    } else {
                        result.content += chunk.getContent();
                    }
                }
            }

            if (chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty()) {
                result.toolCalls = chunk.getToolCalls();
            }
            if (chunk.getUsage() != null) {
                result.usage = chunk.getUsage();
            }
            if (chunk.getFinishReason() != null && !chunk.getFinishReason().isEmpty()) {
                result.finishReason = chunk.getFinishReason();
            }

            if (emit != null) {
                emit.accept(chunk, result.content);
            }
            // 流结束标记：done=true 的 ANSWER/ERROR 块（4.0 生产者的终态元素）。
            // THINKING + done=true 是生产者中途补的 thinking-done 标记——该分片
            // 之后流仍开着，后续分片照常消费；这里同样继续。
            if (chunk.isDone() && chunk.getResponseType() != ResponseType.THINKING) {
                break;
            }
        }
        String answerTail = answerDecoder.flush();
        String thinkingTail = thinkingDecoder.flush();
        result.content += answerTail;
        result.reasoningContent += thinkingTail;
        if (emit != null) {
            if (thinkingTail != null && !thinkingTail.isEmpty()) {
                emit.accept(StreamResponse.of(ResponseType.THINKING, thinkingTail, false), result.content);
            }
            if (answerTail != null && !answerTail.isEmpty()) {
                emit.accept(StreamResponse.of(ResponseType.ANSWER, answerTail, false), result.content);
            }
        }
        // 有些 provider 分片流工具参数、末块给整装调用——组装后再解码一次，
        // 跨 provider 分片的句柄就不会漏进工具执行。
        if (result.toolCalls != null) {
            engine.modelContext.decodeToolCalls(result.toolCalls);
            for (com.ragagent.llm.domain.ToolCall toolCall : result.toolCalls) {
                if (toolCall.getUnresolvedHandles() == null || toolCall.getUnresolvedHandles().isEmpty()) {
                    continue;
                }
                log.warn("[Agent][Stream] Tool {} ({}) contains unresolvable model handle(s): {}",
                        toolCall.getFunction().getName(), toolCall.getId(), toolCall.getUnresolvedHandles());
            }
        }
        List<String> orphans = engine.modelContext.orphanResourceHandles(result.content);
        if (orphans != null && !orphans.isEmpty()) {
            log.warn("[Agent][Stream] Model emitted {} unresolvable resource handle(s): {}",
                    orphans.size(), orphans);
        }

        // 看门狗取消的是 provider 上下文，流只会冒出泛化取消——改述成停顿。
        if (stalled.get()) {
            result.streamError = "LLM stream stalled: no output for "
                    + com.ragagent.common.time.GoDuration.of(stallTimeout);
        }

        log.info("[Agent][Stream] Completed: chunks={}, content_len={}, tool_calls={}, type_distribution={}",
                chunkCount[0], result.content.length(),
                result.toolCalls == null ? 0 : result.toolCalls.size(), responseTypeCounts);

        if (result.streamError != null && !result.streamError.isEmpty()) {
            throw new AgentEngineException("LLM stream error: " + result.streamError);
        }
        return result;
    }

    /**
     * 停顿看门狗语义：超过 stallTimeout 无输出 → 置位 stalled
     * 并结束消费。刻意不限制总时长：流大参数的轮次会连续产出走很久。
     */
    private StreamResponse pollChunk(java.util.concurrent.BlockingQueue<StreamResponse> stream,
            Duration stallTimeout, AtomicLong lastChunkAt, AtomicBoolean stalled) {
        while (true) {
            long idle = System.nanoTime() - lastChunkAt.get();
            long remaining = stallTimeout.toNanos() - idle;
            StreamResponse chunk;
            try {
                if (remaining <= 0) {
                    chunk = stream.poll();
                } else {
                    // 在窗口内密探（stallTimeout/4），检出的间隙贴近配置值。
                    chunk = stream.poll(Math.min(remaining, stallTimeout.toNanos() / 4),
                            TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentEngineException("context canceled");
            }
            if (chunk == null) {
                if (System.nanoTime() - lastChunkAt.get() >= stallTimeout.toNanos()) {
                    log.error("[Agent][Stream] No output for {} (stall timeout {}); cancelling LLM stream",
                            com.ragagent.common.time.GoDuration.of(
                                    Duration.ofNanos(System.nanoTime() - lastChunkAt.get())),
                            com.ragagent.common.time.GoDuration.of(stallTimeout));
                    stalled.set(true);
                    return null;
                }
                continue;
            }
            return chunk;
        }
    }

    /**
     * 思考过程流经 EventBus：pending/progress 工具事件、
     * thinking 通道与内联 think 拆分、答案直播。
     */
    ChatResponse streamThinkingToEventBus(List<ChatMessage> messages, List<ChatTool> tools,
            int iteration, String sessionId) {
        int budget = engine.clampCompletionBudgetToContext(engine.tokenEstimator.estimateMessages(messages));
        log.debug("[Agent][Thinking] Iteration-{}: temp={}, tools={}, thinking={}, max_tokens={}",
                iteration + 1, String.format(java.util.Locale.ROOT, "%.2f", engine.config.getTemperature()),
                tools.size(), engine.config.getThinking(), budget);

        ChatOptions opts = new ChatOptions();
        opts.setTemperature(engine.config.getTemperature());
        opts.setMaxCompletionTokens(budget);
        opts.setTools(tools);
        opts.setThinking(engine.config.getThinking());
        opts.setParallelToolCalls(Boolean.TRUE);
        opts.setPromptCacheKey(sessionId);

        Map<String, Boolean> pendingToolCalls = new HashMap<>();
        Map<String, String> thinkingToolIDs = new HashMap<>();
        Map<String, Integer> emittedEventTypes = new HashMap<>();
        String thinkingID = EventIds.generateEventID("thinking");
        String answerID = EventIds.generateEventID("answer");

        ThinkStreamSplitter splitter = new ThinkStreamSplitter();
        AtomicBoolean thinkingOpen = new AtomicBoolean(false);
        AtomicBoolean answerStreamed = new AtomicBoolean(false);

        ThinkChunkEmitter emitFunc = (chunk, fullContent) -> {
            if (chunk.getResponseType() == ResponseType.TOOL_CALL && chunk.getData() != null) {
                String toolCallID = SteerIntake.mapString(chunk.getData(), "tool_call_id");
                String toolName = SteerIntake.mapString(chunk.getData(), "tool_name");
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) chunk.getData().get("arguments");

                if (!toolCallID.isEmpty() && !toolName.isEmpty()
                        && !pendingToolCalls.containsKey(toolCallID)) {
                    pendingToolCalls.put(toolCallID, Boolean.TRUE);
                    emittedEventTypes.merge("tool_call_pending", 1, Integer::sum);
                    engine.eventBus.emit(new Event(toolCallID + "-tool-call-pending",
                            EventType.EVENT_AGENT_TOOL_CALL, sessionId,
                            new AgentToolCallData(toolCallID, toolName, ActPhase.deepSortedGoMap(args),
                                    iteration, ""), null, ""));
                } else if (!toolCallID.isEmpty() && pendingToolCalls.containsKey(toolCallID)
                        && args != null) {
                    emittedEventTypes.merge("tool_call_progress", 1, Integer::sum);
                    engine.eventBus.emit(new Event(toolCallID + "-tool-call-progress",
                            EventType.EVENT_AGENT_TOOL_CALL, sessionId,
                            new AgentToolCallData(toolCallID, toolName, ActPhase.deepSortedGoMap(args),
                                    iteration, ""), null, ""));
                }
            }

            // thinking 工具的流式思考内容
            if (chunk.getResponseType() == ResponseType.THINKING && chunk.getData() != null) {
                if ("thinking_tool".equals(SteerIntake.mapString(chunk.getData(), "source"))) {
                    String toolCallID = SteerIntake.mapString(chunk.getData(), "tool_call_id");
                    String eventID = thinkingToolIDs.computeIfAbsent(toolCallID,
                            k -> EventIds.generateEventID("thinking-tool"));
                    emittedEventTypes.merge("thinking_tool_chunk", 1, Integer::sum);
                    engine.eventBus.emit(new Event(eventID, EventType.EVENT_AGENT_THOUGHT, sessionId,
                            new AgentThoughtData(chunk.getContent(), iteration, false), null, ""));
                    return;
                }
            }

            // reasoning_content（独立思考通道，如 DeepSeek V4）→ 思考区；
            // 透传 provider 从推理切到答案时发的 Done 标记。
            if (chunk.getResponseType() == ResponseType.THINKING) {
                if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                    thinkingOpen.set(true);
                    emitThought(sessionId, emittedEventTypes, thinkingID, iteration,
                            chunk.getContent(), false);
                } else if (chunk.isDone() && thinkingOpen.get()) {
                    emitThought(sessionId, emittedEventTypes, thinkingID, iteration, "", true);
                    thinkingOpen.set(false);
                }
                return;
            }

            // 纯 content 通道：直播到答案区（乐观渲染为最终答案）。本轮若调了工具，
            // 那是 preamble——随后的 tool-call 事件让 UI 收回。内联 <think> 拆到思考区。
            if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                ThinkStreamSplitter.FeedResult parts = splitter.feed(chunk.getContent());
                if (parts.think() != null && !parts.think().isEmpty()) {
                    thinkingOpen.set(true);
                    emitThought(sessionId, emittedEventTypes, thinkingID, iteration, parts.think(), false);
                }
                emitAnswer(sessionId, emittedEventTypes, answerID, parts.answer(), answerStreamed,
                        thinkingOpen, thinkingID, iteration);
            }
            if (chunk.isDone()) {
                ThinkStreamSplitter.FeedResult parts = splitter.flush();
                if (parts.think() != null && !parts.think().isEmpty()) {
                    thinkingOpen.set(true);
                    emitThought(sessionId, emittedEventTypes, thinkingID, iteration, parts.think(), false);
                }
                emitAnswer(sessionId, emittedEventTypes, answerID, parts.answer(), answerStreamed,
                        thinkingOpen, thinkingID, iteration);
                if (thinkingOpen.get()) {
                    emitThought(sessionId, emittedEventTypes, thinkingID, iteration, "", true);
                    thinkingOpen.set(false);
                }
            }
        };

        StreamLLMResult llmResult = streamLLMToEventBus(messages, opts, emitFunc);

        log.info("[Agent][Thinking] Iteration-{} completed: content={} chars, tool_calls={}, emitted_events={}",
                iteration + 1, llmResult.content.length(),
                llmResult.toolCalls == null ? 0 : llmResult.toolCalls.size(), emittedEventTypes);

        String fullContent = ThinkBlocks.stripThinkBlocks(llmResult.content);

        // 用 LLM 流的实际 finish_reason 而非硬编码 "stop"；流未报时回退 "stop"。
        String finishReason = llmResult.finishReason;
        if (finishReason == null || finishReason.isEmpty()) {
            finishReason = "stop";
        }

        ChatResponse resp = new ChatResponse();
        resp.setContent(fullContent);
        resp.setReasoningContent(llmResult.reasoningContent);
        resp.setToolCalls(llmResult.toolCalls);
        resp.setFinishReason(finishReason);
        resp.setAnswerStreamed(answerStreamed.get());
        if (answerStreamed.get()) {
            resp.setAnswerEventId(answerID);
        }
        if (llmResult.usage != null) {
            resp.setUsage(llmResult.usage);
        }
        return resp;
    }

    private void emitThought(String sessionId, Map<String, Integer> emittedEventTypes,
            String thinkingID, int iteration, String content, boolean done) {
        if ((content == null || content.isEmpty()) && !done) {
            return;
        }
        emittedEventTypes.merge("thought_chunk", 1, Integer::sum);
        engine.eventBus.emit(new Event(thinkingID, EventType.EVENT_AGENT_THOUGHT, sessionId,
                new AgentThoughtData(content, iteration, done), null, ""));
    }

    private void emitAnswer(String sessionId, Map<String, Integer> emittedEventTypes,
            String answerID, String content, AtomicBoolean answerStreamed,
            AtomicBoolean thinkingOpen, String thinkingID, int iteration) {
        if (content == null || content.isEmpty()) {
            return;
        }
        // 真答案开始前压制纯空白（OpenAI 兼容模型常在 tool_call 块同块夹空换行）；
        // 真答案开播后原样保留全部空白。
        if (!answerStreamed.get() && content.trim().isEmpty()) {
            return;
        }
        // closeThinking：第一个答案分片前发思考 Done，UI 的思考卡翻成"已完成"。
        if (thinkingOpen.get()) {
            emitThought(sessionId, emittedEventTypes, thinkingID, iteration, "", true);
            thinkingOpen.set(false);
        }
        answerStreamed.set(true);
        emittedEventTypes.merge("final_answer_chunk", 1, Integer::sum);
        engine.eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionId,
                new AgentFinalAnswerData(content, false, false), null, ""));
    }

    /**
     * 一轮 ReAct 的 LLM 调用（瞬态重试 + 优雅降级）。
     * 返回 null = 优雅降级成功（state.IsComplete 已置位）。
     */
    ChatResponse callLLMWithRetry(AgentEngine.MsgRef messagesRef, List<ChatTool> tools,
            AgentState state, String query, int iteration, String sessionId) {
        int round = iteration + 1;
        List<ChatMessage> messages = messagesRef.items;

        final int maxDetailMsgs = 4;
        log.info("[Agent][Round-{}] Calling LLM: {} messages, {} tools, tenantId={}", round, messages.size(),
                tools.size(), com.ragagent.common.context.TenantContext.currentTenantId());
        int startIdx = 0;
        if (messages.size() > maxDetailMsgs) {
            startIdx = messages.size() - maxDetailMsgs;
            log.debug("[Agent][Round-{}] (skipping msg[0..{}], already logged in prior rounds)",
                    round, startIdx - 1);
        }
        for (int i = startIdx; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("tool".equals(msg.getRole())) {
                log.debug("[Agent][Round-{}] msg[{}]: role=tool, name={}, len={}",
                        round, i, msg.getName(), msg.getContent().length());
            } else if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                List<String> tcNames = new ArrayList<>();
                for (com.ragagent.llm.domain.ToolCall tc : msg.getToolCalls()) {
                    tcNames.add(tc.getFunction().getName());
                }
                log.debug("[Agent][Round-{}] msg[{}]: role={}, len={}, tool_calls={}",
                        round, i, msg.getRole(), msg.getContent().length(), tcNames);
            } else {
                String preview = msg.getContent();
                if (preview.length() > 100) {
                    preview = preview.substring(0, 100) + "...";
                }
                log.debug("[Agent][Round-{}] msg[{}]: role={}, len={}, content={}",
                        round, i, msg.getRole(), msg.getContent().length(), preview);
            }
        }
        log.info("[PIPELINE] stage=Agent action=think_start iteration={} round={} tool_cnt={}",
                iteration, round, tools.size());

        // 发送前清洗（修连续角色、孤儿工具结果）
        messages = MessageSanitizer.sanitizeMessages(messages);

        ChatResponse response = null;
        AgentEngineException error = null;
        try {
            response = streamThinkingToEventBus(messages, tools, iteration, sessionId);
        } catch (AgentEngineException e) {
            error = e;
        }

        // 因体积被拒的请求既非瞬态也非致命：压缩一次再重试。
        if (error != null && !engine.overflowRecovered
                && CompactionOverflow.isOverflowError(error.getMessage() == null ? "" : error.getMessage())) {
            engine.overflowRecovered = true;
            log.warn("[Agent][Round-{}] Provider rejected the request as too large; compacting and retrying once: {}",
                    round, error.getMessage());
            // 引擎的副本保持未清洗。清洗会并掉连续同角色消息，而摘要是条 user 消息、
            // 可能紧贴真用户消息——并掉会把活对话折进摘要信封，下次压缩读不出来。
            List<ChatMessage> compacted = engine.observe.forceCompaction(messages, round);
            messagesRef.items = compacted;
            messages = MessageSanitizer.sanitizeMessages(compacted);
            engine.lastSentMsgCount = compacted.size();
            try {
                response = streamThinkingToEventBus(messages, tools, iteration, sessionId);
                error = null;
            } catch (AgentEngineException e) {
                error = e;
            }
        }

        if (error != null && AgentConsts.isTransientError(
                error.getMessage() == null ? "" : error.getMessage())) {
            // 瞬态错误（超时/限流/服务器错误）最多重试 MAX_LLM_RETRIES 次。
            for (int retry = 1; retry <= AgentConsts.MAX_LLM_RETRIES; retry++) {
                long retryDelayMs = retry * 1000L;
                log.warn("[Agent][Round-{}] LLM transient error (attempt {}/{}), retrying in {}ms: {}",
                        round, retry, AgentConsts.MAX_LLM_RETRIES, retryDelayMs, error.getMessage());
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AgentEngineException("context canceled");
                }
                try {
                    response = streamThinkingToEventBus(messages, tools, iteration, sessionId);
                    error = null;
                } catch (AgentEngineException e) {
                    error = e;
                }
                if (error == null || !AgentConsts.isTransientError(
                        error.getMessage() == null ? "" : error.getMessage())) {
                    break;
                }
            }
        }
        if (error != null) {
            log.error("[Agent][Round-{}] LLM call failed: {}", round, error.getMessage());
            log.info("[PIPELINE] stage=Agent action=think_failed iteration={} error=\"{}\"",
                    iteration, error.getMessage());

            // 优雅降级：有历史工具结果时从它们合成最终答案，而不是全丢。
            int totalTC = AgentEngine.countTotalToolCalls(state.getRoundSteps());
            if (totalTC > 0) {
                log.warn("[Agent] LLM failed but have {} steps with {} tool calls — attempting final answer synthesis from existing results",
                        state.getRoundSteps().size(), totalTC);
                log.warn("[PIPELINE] stage=Agent action=llm_failed_synthesizing steps={} tool_calls={}",
                        state.getRoundSteps().size(), totalTC);
                try {
                    engine.finalize.streamFinalAnswerToEventBus(query, state, sessionId, messages);
                } catch (RuntimeException synthErr) {
                    log.error("[Agent] Final answer synthesis also failed: {}", synthErr.getMessage());
                    throw new AgentEngineException("LLM call failed: " + error.getMessage()
                            + " (synthesis also failed: " + synthErr.getMessage() + ")");
                }
                state.setComplete(true);
                return null; // 优雅降级成功
            }

            throw new AgentEngineException("LLM call failed: " + error.getMessage());
        }

        log.info("[PIPELINE] stage=Agent action=think_result iteration={} finish_reason={} tool_calls={} content_len={}",
                iteration, response.getFinishReason(),
                response.getToolCalls() == null ? 0 : response.getToolCalls().size(),
                response.getContent().length());

        if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
            List<String> tcNames = new ArrayList<>();
            for (com.ragagent.llm.domain.ToolCall tc : response.getToolCalls()) {
                tcNames.add(tc.getFunction().getName());
            }
            log.info("[Agent][Round-{}] LLM responded: finish={}, content={} chars, tools={}",
                    round, response.getFinishReason(), response.getContent().length(), tcNames);
        } else {
            log.info("[Agent][Round-{}] LLM responded: finish={}, content={} chars, tool_calls=0",
                    round, response.getFinishReason(), response.getContent().length());
            if (AgentEngine.isNaturalStopFinishReason(response.getFinishReason())) {
                log.info("[Agent][Round-{}] Natural-stop candidate detected (finish={}, tool_calls=0, content={} chars)",
                        round, response.getFinishReason(), response.getContent().length());
            }
        }
        if (!response.getContent().isEmpty()) {
            String preview = response.getContent();
            if (preview.length() > 300) {
                preview = preview.substring(0, 300) + "...";
            }
            log.debug("[Agent][Round-{}] LLM content preview:\n{}", round, preview);
        }

        return response;
    }

}
