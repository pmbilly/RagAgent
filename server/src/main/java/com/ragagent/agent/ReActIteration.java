package com.ragagent.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ragagent.agent.domain.AgentState;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.tracing.langfuse.LangfuseManager;
import com.ragagent.tracing.langfuse.Span;

/**
 * 单个 ReAct 迭代（think → analyze → act → observe）的编排协作者：轮 span 生命周期、
 * 压缩与 steer 注入时序、停止条件到循环走向（next/continue/break）的翻译。
 *
 * <p>持有 {@link AgentEngine} 回引以访问引擎字段与各段协作者；本类不得独立实例化。</p>
 */
final class ReActIteration {

    private static final Logger log = LoggerFactory.getLogger(ReActIteration.class);

    private final AgentEngine engine;

    ReActIteration(AgentEngine engine) {
        this.engine = engine;
    }

    /**
     * 一个 ReAct 步：think → analyze → act → observe（对照 runReActIteration）。
     * 整个迭代体在一个 span 作用域里，所有出口都触发 Finish。
     */
    AgentEngine.IterOutcome runReActIteration(
            AgentState state, AgentEngine.MsgRef messagesRef, List<ChatTool> tools,
            String sessionId, String assistantMessageId, String query,
            AtomicInteger emptyRetries, AtomicInteger consecutiveSameContent,
            AtomicReference<String> lastResponseContent) {
        Instant roundStart = Instant.now();
        int round = state.getCurrentRound() + 1;

        Map<String, Object> spanInput = new LinkedHashMap<>();
        spanInput.put("round", round);
        spanInput.put("message_count", messagesRef.items.size());
        spanInput.put("max_iterations", engine.config.getMaxIterations());
        Map<String, Object> spanMeta = new LinkedHashMap<>();
        spanMeta.put("iteration", state.getCurrentRound());
        spanMeta.put("round", round);
        spanMeta.put("session_id", sessionId);
        Span roundSpan = LangfuseManager.get().startSpan(
                new LangfuseManager.SpanOptions("agent.round." + round, spanInput, spanMeta));

        ChatResponse[] responseHolder = new ChatResponse[1];
        int[] toolCallCount = {0};
        AgentEngine.IterOutcome[] outcomeHolder = {null};
        RuntimeException[] errorHolder = {null};
        try {
            outcomeHolder[0] = runReActIterationBody(state, messagesRef, tools, sessionId,
                    assistantMessageId, query, emptyRetries, consecutiveSameContent,
                    lastResponseContent, responseHolder, toolCallCount, roundStart, round);
            return outcomeHolder[0];
        } catch (RuntimeException e) {
            errorHolder[0] = e;
            throw e;
        } finally {
            finishRoundSpan(roundSpan, roundStart, round, outcomeHolder[0], toolCallCount[0],
                    responseHolder[0], errorHolder[0] == null ? null : errorHolder[0].getMessage());
        }
    }

    private void finishRoundSpan(Span roundSpan, Instant roundStart, int round, AgentEngine.IterOutcome outcome,
            int toolCallCount, ChatResponse response, String err) {
        if (roundSpan == null) {
            return;
        }
        long durationMs = Duration.between(roundStart, Instant.now()).toMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("round", round);
        out.put("outcome", outcome == null ? "" : outcome.label());
        out.put("tool_calls", toolCallCount);
        if (response != null) {
            out.put("has_tool_calls", response.getToolCalls() != null && !response.getToolCalls().isEmpty());
            out.put("finish_reason", response.getFinishReason());
            out.put("content_len", response.getContent().length());
            if (response.getUsage().getTotalTokens() > 0) {
                out.put("prompt_tokens", response.getUsage().getPromptTokens());
                out.put("completion_tokens", response.getUsage().getCompletionTokens());
                out.put("total_tokens", response.getUsage().getTotalTokens());
            }
        }
        out.put("duration_ms", durationMs);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("round", round);
        meta.put("tool_calls", toolCallCount);
        meta.put("outcome", outcome == null ? "" : outcome.label());
        meta.put("duration_ms", durationMs);
        roundSpan.finish(out, meta, err);
    }

    AgentEngine.IterOutcome runReActIterationBody(
            AgentState state, AgentEngine.MsgRef messagesRef, List<ChatTool> tools,
            String sessionId, String assistantMessageId, String query,
            AtomicInteger emptyRetries, AtomicInteger consecutiveSameContent,
            AtomicReference<String> lastResponseContent,
            ChatResponse[] responseHolder, int[] toolCallCount, Instant roundStart, int round) {

        // 下一响应前压缩老历史（触发估计不计工具 schema）。
        int currentTokens = engine.estimateCurrentTokens(messagesRef.items);
        AgentEngine.WindowOutcome managed = engine.observe.manageContextWindow(messagesRef.items, round, currentTokens);
        if (managed.changed()) {
            messagesRef.items = managed.messages();
            currentTokens = engine.tokenEstimator.estimateMessages(managed.messages());
        }

        // 轮首 drain steer：压缩之后（注入文本落在保护尾内）、engine.lastSentMsgCount 更新之前。
        engine.steer.drainSteerMessages(state, messagesRef, sessionId, assistantMessageId);

        log.info("[Agent][Round-{}/{}] Starting: {} messages, {} tools, est_tokens={}, tenantId={}",
                round, engine.maxIterationsDisplay(), messagesRef.items.size(), tools.size(), currentTokens,
                com.ragagent.common.context.TenantContext.currentTenantId());
        engine.contextDebug.logContextPrediction(round, messagesRef.items, tools, currentTokens);
        log.info("[PIPELINE] stage=Agent action=round_start iteration={} round={} message_count={} pending_tools={} max_iterations={}",
                state.getCurrentRound(), round, messagesRef.items.size(), tools.size(),
                engine.config.getMaxIterations());

        // 1. Think：带重试与优雅降级的 LLM 调用
        engine.lastSentMsgCount = messagesRef.items.size();
        ChatResponse response = engine.think.callLLMWithRetry(messagesRef, tools, state, query,
                state.getCurrentRound(), sessionId);
        if (response == null) {
            return AgentEngine.IterOutcome.BREAK;
        }

        // 该轮自己的估计可能偏小——历史是估的。压缩一次再重试把溢出变成回收的轮次；
        // 每轮限一次：重试也溢出说明问题不在历史大小。
        if (!engine.overflowRecovered && engine.observe.responseHitContextLimit(response)) {
            engine.overflowRecovered = true;
            log.warn("[Agent][Round-{}] Response hit the context window (finish={}, completion={} of {} requested); compacting and retrying once",
                    round, response.getFinishReason(), response.getUsage().getCompletionTokens(),
                    engine.getCompletionTokenBudget());
            messagesRef.items = engine.observe.forceCompaction(messagesRef.items, round);
            engine.lastSentMsgCount = messagesRef.items.size();
            response = engine.think.callLLMWithRetry(messagesRef, tools, state, query, state.getCurrentRound(),
                    sessionId);
            if (response == null) {
                return AgentEngine.IterOutcome.BREAK;
            }
        }
        responseHolder[0] = response;
        engine.contextDebug.logContextDrift(round, currentTokens, response.getUsage());
        if (response.getUsage().getTotalTokens() > 0) {
            engine.lastUsage = response.getUsage();
            state.getTurnUsage().accumulate(response.getUsage());
            log.info("[Agent][Round-{}] Usage: prompt={}, completion={}, total={}, cache_read={}, cache_write={}, cache_hit_rate={}, cache_status={}",
                    round, response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens(), response.getUsage().getTotalTokens(),
                    response.getUsage().getCacheReadTokens(), response.getUsage().getCacheWriteTokens(),
                    String.format(java.util.Locale.ROOT, "%.1f", response.getUsage().promptCacheHitRate()),
                    response.getUsage().getCacheStatus() == null ? ""
                            : response.getUsage().getCacheStatus().value());
        }

        // 卡死循环检测：LLM 一直回相同内容且没有工具调用 → 提前收束。
        if ((response.getToolCalls() == null || response.getToolCalls().isEmpty())
                && !response.getContent().isEmpty()) {
            if (response.getContent().equals(lastResponseContent.get())) {
                consecutiveSameContent.incrementAndGet();
            } else {
                consecutiveSameContent.set(0);
            }
            lastResponseContent.set(response.getContent());
            if (consecutiveSameContent.get() >= AgentConsts.MAX_REPEATED_RESPONSE_ROUNDS) {
                log.warn("[Agent][Round-{}] Detected stuck loop: same content repeated {} times (finish={}), stopping",
                        round, consecutiveSameContent.get() + 1, response.getFinishReason());
                state.setFinalAnswer(response.getContent());
                state.setComplete(true);
                return AgentEngine.IterOutcome.BREAK;
            }
        } else {
            consecutiveSameContent.set(0);
            lastResponseContent.set("");
        }

        // 建 AgentStep
        AgentStep step = new AgentStep();
        step.setUserMessagesBefore(state.getPendingSteerMessages() == null
                ? null : new ArrayList<>(state.getPendingSteerMessages()));
        step.setIteration(state.getCurrentRound());
        step.setThought(response.getContent());
        step.setReasoningContent(response.getReasoningContent());
        step.setToolCalls(new ArrayList<>());
        step.setTimestamp(OffsetDateTime.now());
        state.setPendingSteerMessages(null);

        // 流式中被取消（用户停止）：流驱动仍返回可用响应（部分内容 / finish=stop / 无工具调用）。
        // 别让 analyzeResponse 把半截思考当最终答案——保留为 AgentStep 并退出循环。
        if (engine.pollCancellation() != null) {
            log.warn("[Agent][Round-{}] Context cancelled during LLM call; preserving partial step", round);
            boolean hasContent = step.getThought() != null && !step.getThought().isEmpty();
            boolean hasCalls = step.getToolCalls() != null && !step.getToolCalls().isEmpty();
            boolean hasSteer = step.getUserMessagesBefore() != null && !step.getUserMessagesBefore().isEmpty();
            if (hasContent || hasCalls || hasSteer) {
                state.getRoundSteps().add(step);
            }
            return AgentEngine.IterOutcome.BREAK;
        }

        // 2. Analyze：检查停止条件
        ObservePhase.ResponseVerdict verdict = engine.observe.analyzeResponse(response, step, state.getCurrentRound(), roundStart, sessionId);
        if (verdict.isDone) {
            if (verdict.emptyContent) {
                // 空内容守卫：自然停且无内容无工具调用 → 带 nudge 重试而非接受空答案。
                emptyRetries.incrementAndGet();
                if (emptyRetries.get() <= AgentConsts.MAX_EMPTY_RESPONSE_RETRIES) {
                    state.setPendingSteerMessages(step.getUserMessagesBefore());
                    log.warn("[Agent][Round-{}] Empty content with stop - retrying ({}/{})",
                            round, emptyRetries.get(), AgentConsts.MAX_EMPTY_RESPONSE_RETRIES);
                    messagesRef.items.add(new ChatMessage("user",
                            "Please provide your complete answer now as plain text."));
                    return AgentEngine.IterOutcome.CONTINUE;
                }
                log.warn("[Agent][Round-{}] Empty content after {} retries - using fallback",
                        round, AgentConsts.MAX_EMPTY_RESPONSE_RETRIES);
                state.setFinalAnswer("I'm sorry, I was unable to generate a response. Please try again.");
                state.setComplete(true);
                state.getRoundSteps().add(verdict.step);
                engine.finalize.closeAnswerStream(sessionId, verdict.answerID);
                return AgentEngine.IterOutcome.BREAK;
            }
            // 循环结束注入：本轮收束期间排进来的用户消息让 agent 继续而不是收束答案。
            // content_filter 停止是终态，不走这条路。
            if (!"content_filter".equals(response.getFinishReason())) {
                int nextRound = state.getCurrentRound() + 1;
                boolean canContinue = engine.withinIterationBudget(nextRound) || engine.steerOverruns < AgentEngine.MAX_STEER_OVERRUNS;
                if (canContinue) {
                    ChatMessage assistant = new ChatMessage("assistant", verdict.finalAnswer);
                    assistant.setReasoningContent(response.getReasoningContent());
                    messagesRef.items.add(assistant);
                    int injected = engine.steer.drainSteerMessages(state, messagesRef, sessionId, assistantMessageId);
                    if (injected > 0) {
                        verdict.step.setIntermediateAnswer(true);
                        state.getRoundSteps().add(verdict.step);
                        if (!engine.withinIterationBudget(nextRound)) {
                            engine.steerOverruns++;
                            engine.allowSteerOverrun = true;
                        }
                        return AgentEngine.IterOutcome.NEXT;
                    }
                }
            }
            state.setFinalAnswer(verdict.finalAnswer);
            state.setComplete(true);
            state.getRoundSteps().add(verdict.step);
            engine.finalize.closeAnswerStream(sessionId, verdict.answerID);
            return AgentEngine.IterOutcome.BREAK;
        }

        // 本轮非终态（要执行工具再进下一轮）。本轮直播到答案区的纯文本是 preamble 而非答案；
        // 无需显式撤回信号——后续的 tool-call 事件就是权威的"那不是最终答案"标记。

        // 3. Act：执行工具调用
        engine.act.executeToolCalls(response, step, state.getCurrentRound(), sessionId, assistantMessageId);
        toolCallCount[0] = step.getToolCalls().size();

        // 4. Observe：工具结果进消息
        state.getRoundSteps().add(step);
        messagesRef.items = engine.observe.appendToolResults(messagesRef.items, step);
        messagesRef.items = engine.observe.appendToolImages(messagesRef.items, step);
        log.info("[PIPELINE] stage=Agent action=round_end iteration={} round={} tool_calls={} thought_len={}",
                state.getCurrentRound(), round, toolCallCount[0], step.getThought().length());

        return AgentEngine.IterOutcome.NEXT;
    }
}
