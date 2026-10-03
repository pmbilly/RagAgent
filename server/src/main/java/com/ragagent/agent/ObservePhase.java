package com.ragagent.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.compaction.CompactionOverflow;
import com.ragagent.agent.compaction.CompactionReason;
import com.ragagent.agent.compaction.CompactionResult;
import com.ragagent.agent.compaction.CompactionSettings;
import com.ragagent.agent.compaction.NothingToCompactException;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.domain.ToolCall;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.ThinkBlocks;
import com.ragagent.event.payload.AgentFinalAnswerData;
import com.ragagent.event.payload.ContextCompactedData;
import com.ragagent.event.Event;
import com.ragagent.event.EventIds;
import com.ragagent.event.EventType;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.TokenUsage;

/**
 * ReAct「Observe」段的协作者：上下文窗口管理与响应分析——上下文预算估算与
 * 压缩（主动/强制/裁剪工具结果）、停止条件判定（ResponseVerdict）、工具结果
 * 回填消息历史。
 *
 * <p>持有 {@link AgentEngine} 回引以访问引擎字段；本类不得独立实例化。</p>
 */
final class ObservePhase {

    private static final Logger log = LoggerFactory.getLogger(ObservePhase.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentEngine engine;

    ObservePhase(AgentEngine engine) {
        this.engine = engine;
    }

    private static final int MIN_TOOL_RESULT_TOKENS = 8 * 1024;
    private static final int MAX_TOOL_RESULT_TOKENS = 32 * 1024;
    private static final int TOOL_RESULT_TOKEN_FRACTION = 5; // 20%
    private static final int MIN_FREED_FRACTION = 20; // 5%

    /**
     * 上下文过阈值时把老对话摘要掉。
     * changed 报告消息是否变了，调用方据此作废自己的 token 估计。
     */
    AgentEngine.WindowOutcome manageContextWindow(List<ChatMessage> messages, int round, int currentTokens) {
        CompactionSettings settings = activeCompactionSettings();
        if (!settings.shouldCompact(currentTokens)) {
            return new AgentEngine.WindowOutcome(messages, false);
        }
        log.info("[Agent][Round-{}] Context at {} tokens, over the {} threshold (window={}, reserved={}, keep_recent={}); compacting",
                round, currentTokens, settings.threshold(), settings.maxContextTokens(),
                settings.reserveTokens(), settings.keepRecentTokens());

        boolean changed = false;
        CompactionOutcome compacted = runCompaction(messages, round, CompactionReason.THRESHOLD);
        if (compacted.ok()) {
            messages = compacted.messages();
            changed = true;
            currentTokens = engine.tokenEstimator.estimateMessages(messages);
            if (!settings.shouldCompact(currentTokens)) {
                return new AgentEngine.WindowOutcome(messages, true);
            }
        }

        // 压缩后仍超预算说明重量在 keep-recent 窗口内（切点够不着）——裁工具结果是
        // lossy 的兜底而非常规步骤。
        AgentEngine.WindowOutcome trimmed = trimToolResults(messages, round, settings);
        return new AgentEngine.WindowOutcome(trimmed.messages(), changed || trimmed.changed());
    }

    /** runCompaction 的 (messages, ok)。 */
    record CompactionOutcome(List<ChatMessage> messages, boolean ok) {
    }

    /**
     * 执行一次压缩并报告上下文是否真的变小。
     * false = 本轮再试也无用，调用方不得继续重试：腾不出空间的压缩照样付一整个
     * 摘要往返。
     */
    private CompactionOutcome runCompaction(List<ChatMessage> messages, int round, String reason) {
        if (engine.compactor == null) {
            return new CompactionOutcome(messages, false);
        }
        // 耗尽标记挂在消息数上：一旦循环又追加了新轮次，就有新历史可摘要。
        if (engine.compactionExhaustedAt > 0 && messages.size() <= engine.compactionExhaustedAt) {
            return new CompactionOutcome(messages, false);
        }

        CompactionResult result;
        try {
            result = engine.compactor.compact(messages, reason);
        } catch (NothingToCompactException e) {
            log.info("[Agent][Round-{}] Nothing outside the keep-recent budget; skipping compaction", round);
            engine.compactionExhaustedAt = messages.size();
            return new CompactionOutcome(messages, false);
        } catch (RuntimeException e) {
            log.warn("[Agent][Round-{}] Compaction failed: {}", round, e.getMessage());
            engine.compactionExhaustedAt = messages.size();
            return new CompactionOutcome(messages, false);
        }
        // "腾出了"太弱：240/26700 也算进展，循环会每轮白付一次摘要而上下文原地不动。
        if (result.freed() < result.getTokensBefore() / MIN_FREED_FRACTION) {
            log.warn("[Agent][Round-{}] Compaction freed too little ({} → {} tokens); not attempting again at this size",
                    round, result.getTokensBefore(), result.getTokensAfter());
            engine.compactionExhaustedAt = messages.size();
            return new CompactionOutcome(messages, false);
        }

        log.info("[Agent][Round-{}] Compacted ({}): {} → {} tokens, {} → {} messages (split_turn={}, degraded={})",
                round, result.getReason(), result.getTokensBefore(), result.getTokensAfter(),
                result.getMessagesBefore(), result.getMessagesAfter(), result.isSplitTurn(),
                result.isDegraded());
        log.debug("[Agent][Round-{}][ctx] post-compaction: summary={} tail={} (keep_recent={}) | {}",
                round, engine.tokenEstimator.estimateString(result.getSummary()),
                result.getTokensAfter() - engine.tokenEstimator.estimateString(result.getSummary()),
                engine.compactor.settings().keepRecentTokens(),
                ContextDiagnostics.breakdownContext(result.getMessages(), List.of(), engine.tokenEstimator));
        log.info("[PIPELINE] stage=Agent action=context_compacted round={} reason={} tokens_before={} tokens_after={} degraded={}",
                round, result.getReason(), result.getTokensBefore(), result.getTokensAfter(),
                result.isDegraded());
        emitContextCompacted(result, round);

        // usage 基线描述的是压缩前的上下文；留着会让下一轮对着不存在的历史估、立刻再压。
        engine.lastUsage = new TokenUsage();
        engine.lastSentMsgCount = 0;

        return new CompactionOutcome(result.getMessages(), true);
    }

    private void emitContextCompacted(CompactionResult result, int round) {
        engine.eventBus.emit(new Event(EventIds.generateEventID("compaction"),
                EventType.EVENT_CONTEXT_COMPACTED, engine.sessionId,
                new ContextCompactedData(result.getReason(), round, result.getTokensBefore(),
                        result.getTokensAfter(), result.getMessagesBefore(), result.getMessagesAfter(),
                        result.getSummary(), result.isDegraded(), result.isSplitTurn()),
                null, ""));
    }

    /** 响应是否由满窗口塑形而非我们要求的补全预算。 */
    boolean responseHitContextLimit(ChatResponse response) {
        int window = engine.config == null ? 0 : engine.config.getMaxContextTokens();
        return CompactionOverflow.responseHitContextLimit(response, window, engine.getCompletionTokenBudget());
    }

    /** 不看阈值直接压缩：provider 已说窗口满了，让估计见鬼去吧。 */
    List<ChatMessage> forceCompaction(List<ChatMessage> messages, int round) {
        CompactionOutcome compacted = runCompaction(messages, round, CompactionReason.OVERFLOW);
        if (!compacted.ok()) {
            AgentEngine.WindowOutcome trimmed = trimToolResults(messages, round, activeCompactionSettings());
            return trimmed.messages();
        }
        return compacted.messages();
    }

    /** 无压缩器/窗口时压缩整体停用（返回零值 settings）。 */
    CompactionSettings activeCompactionSettings() {
        return engine.compactor == null ? CompactionSettings.ofDefaults() : engine.compactor.settings();
    }

    /** 用预览替换工具输出直到装进窗口的一部分（最后的兜底）。 */
    private AgentEngine.WindowOutcome trimToolResults(List<ChatMessage> messages, int round,
            CompactionSettings settings) {
        AgentEngine.TrimOutcome trimmed = AgentEngine.trimToolResultsToBudget(messages, engine.tokenEstimator,
                toolResultBudget(settings.maxContextTokens()));
        if (!trimmed.ok()) {
            return new AgentEngine.WindowOutcome(messages, false);
        }
        log.info("[Agent][Round-{}] Trimmed tool results to the token budget", round);
        return new AgentEngine.WindowOutcome(trimmed.messages(), true);
    }

    static int toolResultBudget(int maxContextTokens) {
        if (maxContextTokens <= 0) {
            return MAX_TOOL_RESULT_TOKENS;
        }
        int budget = maxContextTokens / TOOL_RESULT_TOKEN_FRACTION;
        if (budget < MIN_TOOL_RESULT_TOKENS) {
            return MIN_TOOL_RESULT_TOKENS;
        }
        if (budget > MAX_TOOL_RESULT_TOKENS) {
            return MAX_TOOL_RESULT_TOKENS;
        }
        return budget;
    }

    /** trimToolResultsToBudget 的 (messages, ok)。 */

    static ChatMessage shallowCopy(ChatMessage m) {
        ChatMessage c = new ChatMessage(m.getRole(), m.getContent());
        c.setMultiContent(m.getMultiContent());
        c.setName(m.getName());
        c.setToolCallId(m.getToolCallId());
        c.setToolCalls(m.getToolCalls());
        c.setImages(m.getImages());
        c.setReasoningContent(m.getReasoningContent());
        c.setKind(m.getKind());
        return c;
    }

    static String compactedToolResultMarker(String content) {
        return "[Tool result compacted: original_bytes=" + content.length()
                + ". Re-run the tool with narrower filters or a smaller range if more detail is needed.]";
    }

    /** 单条工具消息压到 maxTokens 内（keep 值二分）。 */
    static ChatMessage compactToolMessage(ChatMessage msg, int maxTokens,
            com.ragagent.agent.TokenEstimator estimator) {
        String content = msg.getContent();
        int runeCount = content.codePointCount(0, content.length());
        ChatMessage base = shallowCopy(msg);
        base.setContent(compactedToolResultMarker(content));
        if (ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS.equals(msg.getName())) {
            // 目录游标与参数 schema 是结构化协议数据；头尾预览会静默删掉必填字段或约束。
            base.setContent("[MCP directory result omitted to fit the context budget. Use smaller list pages. If "
                    + "a single describe result cannot fit, report that limitation; do not invoke a tool "
                    + "using a partial schema.]");
            return base;
        }
        if (runeCount == 0 || estimator.estimateMessage(base) >= maxTokens) {
            return base;
        }

        ChatMessage best = base;
        int low = 1;
        int high = runeCount;
        while (low <= high) {
            int keep = low + (high - low) / 2;
            int head = keep / 4;
            int tail = keep - head;
            int headEnd = content.offsetByCodePoints(0, head);
            int tailStart = content.offsetByCodePoints(content.length(), -tail);
            ChatMessage candidate = shallowCopy(base);
            candidate.setContent(base.getContent() + "\n\n" + content.substring(0, headEnd)
                    + "\n...[tool result preview omitted]...\n" + content.substring(tailStart));
            if (estimator.estimateMessage(candidate) <= maxTokens) {
                best = candidate;
                low = keep + 1;
            } else {
                high = keep - 1;
            }
        }
        return best;
    }

    /** 响应分析的裁决。 */

    /** 一步响应的分析结论（终态判定 + 步骤与答案事件的落点）。 */
    static final class ResponseVerdict {
        boolean isDone;
        String finalAnswer = "";
        boolean emptyContent;
        AgentStep step;
        String answerID = "";
    }

    /**
     * 检查 LLM 响应的停止条件：自然停无工具调用 → 完成；content_filter 无工具调用 →
     * 完成（终态，避免同一被拦响应无限累积）。
     */
    ResponseVerdict analyzeResponse(ChatResponse response, AgentStep step,
            int iteration, Instant roundStart, String sessionID) {
        // Case 0: 内容被模型内容安全策略拦截。
        if ("content_filter".equals(response.getFinishReason())
                && (response.getToolCalls() == null || response.getToolCalls().isEmpty())) {
            log.warn("[Agent][Round-{}] Content filter triggered, stopping agent loop (content={} chars)",
                    iteration + 1, response.getContent().length());
            log.warn("[PIPELINE] stage=Agent action=content_filter_stop iteration={} round={} content_len={}",
                    iteration, iteration + 1, response.getContent().length());

            String answer = response.getContent();
            if (answer.isEmpty()) {
                answer = "Sorry, this request was blocked by the content safety policy. Please try rephrasing your question.";
            }

            String answerID = EventIds.generateEventID("answer");
            engine.eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionID,
                    new AgentFinalAnswerData(answer, false, false), null, ""));
            engine.eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionID,
                    new AgentFinalAnswerData("", true, false), null, ""));

            ResponseVerdict v = new ResponseVerdict();
            v.isDone = true;
            v.finalAnswer = answer;
            v.step = step;
            return v;
        }

        // Case 1: LLM 自然停且没有请求任何工具调用。先剥掉内联 <think> 块。
        if (AgentEngine.isNaturalStopFinishReason(response.getFinishReason())
                && (response.getToolCalls() == null || response.getToolCalls().isEmpty())) {
            response.setContent(ThinkBlocks.stripThinkBlocks(response.getContent()));
            log.info("[Agent][Round-{}] Agent finished naturally: answer={} chars, duration={}ms",
                    iteration + 1, response.getContent().length(),
                    Duration.between(roundStart, Instant.now()).toMillis());
            log.info("[PIPELINE] stage=Agent action=round_final_answer iteration={} round={} answer_len={}",
                    iteration, iteration + 1, response.getContent().length());

            // 答案文本到 UI 的两条路：
            //  (a) think 阶段已直播（AnswerStreamed）→ 只在同一 event ID 上补 Done——
            //      重发全文会渲染两遍（"思考跳答案"的 jump 缺陷）；
            //  (b) 未直播 → 先发全文再 Done。
            String answerID;
            if (response.isAnswerStreamed() && !response.getAnswerEventId().isEmpty()) {
                answerID = response.getAnswerEventId();
            } else {
                answerID = EventIds.generateEventID("answer");
                if (!response.getContent().isEmpty()) {
                    engine.eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionID,
                            new AgentFinalAnswerData(response.getContent(), false, false), null, ""));
                }
            }
            // 这里不发 Done:true——调用方先 drain 循环结束注入；过早关闭会让客户端
            // 在引擎即将继续时误以为轮次空闲。

            ResponseVerdict v = new ResponseVerdict();
            v.isDone = true;
            v.finalAnswer = response.getContent();
            v.emptyContent = response.getContent().isEmpty();
            v.step = step;
            v.answerID = answerID;
            return v;
        }

        // 仍有工具调用的轮次非终态：agent 只以自然停 + 纯文本答案结束。
        ResponseVerdict v = new ResponseVerdict();
        v.step = step;
        return v;
    }


    /**
     * 工具结果进轮内消息历史（OpenAI tool-calling 格式）。
     * 跨轮持久化另行处理：最终 AgentSteps 由 SSE handler 写上 assistant 消息、
     * 下轮由 service.LoadAgentHistory 从 DB 重建。
     */
    List<ChatMessage> appendToolResults(List<ChatMessage> messages, AgentStep step) {
        if ((step.getThought() != null && !step.getThought().isEmpty())
                || (step.getToolCalls() != null && !step.getToolCalls().isEmpty())
                || (step.getReasoningContent() != null && !step.getReasoningContent().isEmpty())) {
            ChatMessage assistantMsg = new ChatMessage("assistant", step.getThought());
            assistantMsg.setReasoningContent(step.getReasoningContent());

            if (step.getToolCalls() != null && !step.getToolCalls().isEmpty()) {
                List<com.ragagent.llm.domain.ToolCall> llmCalls = new ArrayList<>(step.getToolCalls().size());
                for (ToolCall tc : step.getToolCalls()) {
                    com.ragagent.llm.domain.ToolCall c = new com.ragagent.llm.domain.ToolCall();
                    c.setId(tc.getId());
                    c.setType("function");
                    c.setProviderMetadata(tc.getProviderMetadata());
                    c.setFunction(new com.ragagent.llm.domain.FunctionCall(tc.getName(),
                            goMarshal(tc.getArgs())));
                    llmCalls.add(c);
                }
                assistantMsg.setToolCalls(llmCalls);
            }
            messages.add(assistantMsg);
        }

        if (step.getToolCalls() != null) {
            for (ToolCall toolCall : step.getToolCalls()) {
                String resultContent = engine.modelContext.modelToolResultForTool(toolCall.getName(),
                        toolCall.getResult());
                messages.add(ChatMessage.tool(toolCall.getId(), toolCall.getName(), resultContent));
            }
        }
        return messages;
    }

    /** map 序列化的字节形态：键序 + HTML 转义 + float 语义（经 ToolJson，与既有事件 payload 逐字节一致）。 */
    private static String goMarshal(Map<String, Object> args) {
        if (args == null) {
            return "null";
        }
        return com.ragagent.common.web.ToolJson.write(JSON.valueToTree(ActPhase.deepSortedGoMap(args)));
    }

    /** 工具图片随结果消息走（工具结果图片的 VLM 描述内联在图片富化回调里）。 */
    List<ChatMessage> appendToolImages(List<ChatMessage> messages, AgentStep step) {
        return ToolImages.appendToolImages(messages, step,
                engine.config != null && engine.config.isChatModelSupportsVision(),
                images -> {
                    if (engine.imageDescriber == null) {
                        return null;
                    }
                    List<String> raw = new ArrayList<>();
                    for (String uri : images) {
                        byte[] bytes;
                        try {
                            bytes = ActPhase.decodeDataURIBytes(uri);
                        } catch (Exception e) {
                            log.warn("[Agent] Failed to decode tool result image: {}", e.getMessage());
                            continue;
                        }
                        try {
                            raw.add(engine.imageDescriber.describe(bytes, ActPhase.TOOL_IMAGE_ANALYSIS_PROMPT));
                        } catch (Exception e) {
                            log.warn("[Agent] VLM analysis failed for tool result image: {}", e.getMessage());
                        }
                    }
                    return raw;
                });
    }
}
