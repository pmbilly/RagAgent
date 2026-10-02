package com.ragagent.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.NullNode;
import com.ragagent.agent.compaction.CompactionSettings;
import com.ragagent.agent.compaction.Compactor;
import com.ragagent.agent.domain.AgentState;
import com.ragagent.agent.domain.ToolCall;
import com.ragagent.event.TenantContextSnapshot;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.skills.Manager;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.ToolRegistry;
import com.ragagent.event.payload.ErrorData;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventIds;
import com.ragagent.event.EventType;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.FunctionDef;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.agent.modelcontext.Registry;
import com.ragagent.tracing.langfuse.LangfuseManager;
import com.ragagent.tracing.langfuse.Span;

/**
 * ReAct agent 引擎。
 *
 * <h2>无状态跨轮</h2>
 * <p>引擎跨轮无状态：会话历史每轮由调用方从 DB
 * 重建后经 {@code llmContext} 传入。引擎不维护自己的缓存、系统提示词存储或跨轮缓冲。</p>
 *
 * <h2>事件即 SSE 上游</h2>
 * <p>所有事件经 {@link EventBus} 发出；
 * emit 顺序就是将来的 SSE 帧序。</p>
 *
 * <h2>语义决策</h2>
 * <ul>
 *   <li><b>失败即异常</b>：成功返回值 / 抛 {@link AgentEngineException}
 *       （message 逐字稳定——它是 error 事件字段原文）。</li>
 *   <li><b>取消探测</b>用
 *       {@link #setCancellationSource(Supplier)}（null=存活，非 null=错误原文）；
 *       租户/主体在引擎线程解析成<b>显式值</b>传入虚拟线程，
 *       不读 ThreadLocal。</li>
 *   <li><b>消息列表/计数器</b>用 {@link MsgRef} / AtomicInteger /
 *       AtomicReference 装箱共享。</li>
 *   <li><b>complete 事件的 usage 键恒输出</b>：用量缺失也输出
 *       {@code "usage":null}（实录钉住）；Java 用 {@link NullNode} 复刻，消费侧按
 *       {@code usage instanceof TokenUsage} 判别。</li>
 *   <li><b>LLM 瞬态重试</b>的 sleep（1s/2s）保留。</li>
 * </ul>
 */
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);


    /** agent.execute span 输入里 query 的预览上限。 */
    private static final int LANGFUSE_QUERY_PREVIEW = 2000;

    /** 循环结束注入最多多跑一轮。 */
    static final int MAX_STEER_OVERRUNS = 1;

    // ==================================================================
    // 引擎字段
    final AgentConfig config;
    final ToolRegistry toolRegistry;
    final LlmChatClient chatModel;
    final EventBus eventBus;
    /** 绑定知识库详情（提示词用）；测试需要直改 → 包内可见。 */
    List<AgentPrompts.KnowledgeBaseInfo> knowledgeBasesInfo;
    /** 用户 @ 选中的文档。 */
    List<AgentPrompts.SelectedDocumentInfo> selectedDocs;
    /** 本轮 @ 指定的 MCP 服务。 */
    List<AgentPrompts.PinnedMCPServiceInfo> pinnedMCPServices = List.of();
    /** 本轮 @ 指定的技能。 */
    List<AgentPrompts.PinnedSkillInfo> pinnedSkills = List.of();
    /** 引擎自己的 session id（emitContextCompacted 用它，不是执行入口的入参）。 */
    final String sessionId;
    String systemPromptTemplate;
    String memoryPrompt = "";
    Manager skillsManager;
    /** 提示词模板解析配置；null = 默认 base。 */
    AgentPromptTemplates.TemplatesConfig appConfig;
    /** 工具结果图片的 VLM 描述函数（可选）。 */
    ImageDescriberFunc imageDescriber;
    final com.ragagent.agent.TokenEstimator tokenEstimator = new com.ragagent.agent.TokenEstimator();
    Compactor compactor;
    /** 最近一次 LLM 调用的用量（usage 基线）。 */
    TokenUsage lastUsage = new TokenUsage();
    /** 最近一次 LLM 调用发送的消息数。 */
    int lastSentMsgCount;
    /** 本轮已用过的一次溢出压缩重试。 */
    boolean overflowRecovered;
    /** 压缩上次"腾不出空间"时的消息数。 */
    int compactionExhaustedAt;
    /** 请求内 model-context 边界。 */
    final Registry modelContext;
    /** 运行中注入通道；null = 禁用。 */
    SteerSink steerSink;

    boolean allowSteerOverrun;
    int steerOverruns;
    /** 取消探测；null = 永不取消。 */
    Supplier<String> cancellationSource;

    /** ReAct 各段协作者（构造期装配；只存引擎引用，调用期才解引）。 */
    final ThinkPhase think;
    final ActPhase act;
    final ObservePhase observe;
    final PromptAssembly prompts;
    final FinalizePhase finalize;
    final SteerIntake steer;
    final ReActIteration iteration;
    final ContextDebugEmitter contextDebug;

    /** 图片描述函数。 */
    @FunctionalInterface
    public interface ImageDescriberFunc {
        /** 描述一张图片；失败抛异常。 */
        String describe(byte[] imgBytes, String prompt);
    }

    // ==================================================================
    // 构造
    // ==================================================================

    public AgentEngine(AgentConfig config, LlmChatClient chatModel, ToolRegistry toolRegistry,
            EventBus eventBus, List<AgentPrompts.KnowledgeBaseInfo> knowledgeBasesInfo,
            List<AgentPrompts.SelectedDocumentInfo> selectedDocs, String sessionId,
            String systemPromptTemplate) {
        if (eventBus == null) {
            eventBus = new EventBus();
        }
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.chatModel = chatModel;
        this.eventBus = eventBus;
        this.knowledgeBasesInfo = knowledgeBasesInfo;
        this.selectedDocs = selectedDocs;
        this.sessionId = sessionId;
        this.systemPromptTemplate = systemPromptTemplate == null ? "" : systemPromptTemplate;
        this.modelContext = new Registry(config == null || config.citationsEnabled());

        this.compactor = Compactor.create(chatModel, tokenEstimator,
                new CompactionSettings(true,
                        config == null ? 0 : config.getMaxContextTokens(),
                        contextReserveTokens(),
                        config == null ? 0 : config.getCompactionKeepRecentTokens(),
                        getCompletionTokenBudget()));
        this.think = new ThinkPhase(this);
        this.act = new ActPhase(this);
        this.observe = new ObservePhase(this);
        this.prompts = new PromptAssembly(this);
        this.finalize = new FinalizePhase(this);
        this.steer = new SteerIntake(this);
        this.iteration = new ReActIteration(this);
        this.contextDebug = new ContextDebugEmitter(this);
    }

    /** 带技能管理器的构造变体。 */
    public static AgentEngine withSkills(AgentConfig config, LlmChatClient chatModel,
            ToolRegistry toolRegistry, EventBus eventBus,
            List<AgentPrompts.KnowledgeBaseInfo> knowledgeBasesInfo,
            List<AgentPrompts.SelectedDocumentInfo> selectedDocs, String sessionId,
            String systemPromptTemplate, Manager skillsManager) {
        AgentEngine engine = new AgentEngine(config, chatModel, toolRegistry, eventBus,
                knowledgeBasesInfo, selectedDocs, sessionId, systemPromptTemplate);
        engine.skillsManager = skillsManager;
        return engine;
    }

    /** 本轮 @mention 范围。 */
    public void setPinnedMentions(List<AgentPrompts.PinnedMCPServiceInfo> mcpServices,
            List<AgentPrompts.PinnedSkillInfo> skills) {
        this.pinnedMCPServices = mcpServices == null ? List.of() : mcpServices;
        this.pinnedSkills = skills == null ? List.of() : skills;
    }

    /** 空输入不改动系统提示词。 */
    public void setMemoryPrompt(String prompt) {
        this.memoryPrompt = prompt == null ? "" : prompt;
    }

    /** 设置提示词模板解析配置。 */
    public void setAppConfig(AgentPromptTemplates.TemplatesConfig cfg) {
        this.appConfig = cfg;
    }

    public void setImageDescriber(ImageDescriberFunc fn) {
        this.imageDescriber = fn;
    }

    public void setSkillsManager(Manager manager) {
        this.skillsManager = manager;
    }

    public Manager getSkillsManager() {
        return skillsManager;
    }

    /** null（默认）= 完全禁用运行中注入。 */
    public void setSteerSink(SteerSink sink) {
        this.steerSink = sink;
    }

    /**
     * 取消探测 seam：存活返回 null、取消返回错误原文；
     * 调用方在 stop 链路接线它。
     */
    public void setCancellationSource(Supplier<String> source) {
        this.cancellationSource = source;
    }

    String pollCancellation() {
        return cancellationSource == null ? null : cancellationSource.get();
    }

    // ---- 包内测试 seam（生产装配走构造器/setter）----

    ToolRegistry getRegistryForTest() {
        return toolRegistry;
    }

    EventBus getBusForTest() {
        return eventBus;
    }

    void setKnowledgeBasesInfoForTest(List<AgentPrompts.KnowledgeBaseInfo> v) {
        this.knowledgeBasesInfo = v;
    }

    void setSelectedDocsForTest(List<AgentPrompts.SelectedDocumentInfo> v) {
        this.selectedDocs = v;
    }

    com.ragagent.agent.TokenEstimator tokenEstimatorForTest() {
        return tokenEstimator;
    }

    void setUsageBaselineForTest(TokenUsage usage, int sentCount) {
        this.lastUsage = usage;
        this.lastSentMsgCount = sentCount;
    }

    CompactionSettings compactorSettingsForTest() {
        return compactor == null ? null : compactor.settings();
    }

    // ---- 包内测试/跨段 seam 委托（实现随 ReAct 各段协作者）----

    ToolCall runToolCall(com.ragagent.llm.domain.ToolCall tc, int i, int iteration, int round,
            String sessionId, String assistantMessageID, TenantContextSnapshot tenant) {
        return act.runToolCall(tc, i, iteration, round, sessionId, assistantMessageID, tenant);
    }

    int drainSteerMessages(AgentState state, MsgRef messagesRef, String sessionId, String messageID) {
        return steer.drainSteerMessages(state, messagesRef, sessionId, messageID);
    }

    WindowOutcome manageContextWindow(List<ChatMessage> messages, int round, int currentTokens) {
        return observe.manageContextWindow(messages, round, currentTokens);
    }

    ChatResponse streamThinkingToEventBus(List<ChatMessage> messages, List<ChatTool> tools,
            int iteration, String sessionId) {
        return think.streamThinkingToEventBus(messages, tools, iteration, sessionId);
    }

    // ==================================================================
    // token 预算与迭代上限

    int estimateCurrentTokens(List<ChatMessage> messages) {
        int baseline = contextTokensFromUsage(lastUsage);
        if (baseline > 0 && lastSentMsgCount > 0 && lastSentMsgCount <= messages.size()) {
            return baseline + tokenEstimator
                    .estimateMessages(messages.subList(deltaStart(messages), messages.size()));
        }
        return tokenEstimator.estimateMessages(messages);
    }

    /** 第一个尚未被 lastUsage 覆盖的消息下标。 */
    private int deltaStart(List<ChatMessage> messages) {
        int start = lastSentMsgCount;
        if (start < messages.size() && "assistant".equals(messages.get(start).getRole())) {
            start++;
        }
        return start;
    }

    /** usage 报告还原为它描述的上下文大小（缓存计数不加回）。 */
    static int contextTokensFromUsage(TokenUsage usage) {
        if (usage == null) {
            return 0;
        }
        if (usage.getTotalTokens() > 0) {
            return usage.getTotalTokens();
        }
        return usage.getPromptTokens() + usage.getCompletionTokens();
    }

    /** 是否还允许一个 ReAct 轮次；负 MaxIterations = 无上限。 */
    boolean withinIterationBudget(int round) {
        if (config == null) {
            return false;
        }
        if (config.unlimitedIterations()) {
            return true;
        }
        return round < config.getMaxIterations();
    }
    String maxIterationsDisplay() {
        if (config != null && config.unlimitedIterations()) {
            return "unlimited";
        }
        return String.valueOf(config == null ? 0 : config.getMaxIterations());
    }

    /** 单轮 ReAct 的补全预算。 */
    int getCompletionTokenBudget() {
        int configured = 0;
        if (config != null) {
            configured = config.getMaxCompletionTokens();
        }
        return AgentBudgets.agentRoundMaxCompletionTokens(configured);
    }

    int contextReserveTokens() {
        return AgentConsts.contextReserveTokens(getCompletionTokenBudget());
    }

    int clampCompletionBudgetToContext(int currentTokens) {
        int budget = getCompletionTokenBudget();
        if (config == null || config.getMaxContextTokens() <= 0) {
            return budget;
        }
        return AgentConsts.clampCompletionBudgetToContext(config.getMaxContextTokens(), currentTokens, budget);
    }

    Duration getLLMStallTimeout() {
        return AgentConsts.llmStallTimeout(config == null ? null : config.getLlmCallTimeout());
    }

    // ==================================================================
    // 执行主入口
    /** 便捷重载（无图片）。 */
    public AgentState execute(String sessionId, String messageId, String query,
            List<ChatMessage> llmContext) {
        return execute(sessionId, messageId, query, llmContext, null);
    }

    /**
     * 执行 agent：带会话历史与流式输出。
     *
     * @param imageURLs 多模态输入图片
     * @throws AgentEngineException 失败时（error 事件已在抛出前发出）
     */
    public AgentState execute(String sessionId, String messageId, String query,
            List<ChatMessage> llmContext, List<String> imageURLs) {
        // null 列表按空集处理——调用方（如 skill 安装器的 installer run）可以合法传
        // null，入口日志会直接取 size 而 NPE（2026-09-25 install E2E 抓回：
        // installer agent failed: Cannot invoke "java.util.List.size()" because
        // "llmContext" is null），这里归一为空列表。
        List<ChatMessage> context = llmContext == null ? List.of() : llmContext;
        log.info("[Agent] Starting execution: session={}, message={}, query_len={}, context_msgs={}, tenantId={}, principal={}, userId={}",
                sessionId, messageId, query.length(), context.size(),
                com.ragagent.common.context.TenantContext.currentTenantId(),
                com.ragagent.common.context.TenantContext.currentPrincipal() == null ? "<null>"
                        : com.ragagent.common.context.TenantContext.currentPrincipal().type(),
                com.ragagent.common.context.TenantContext.currentUserId());
        try {
            return executeInner(sessionId, messageId, query, context, imageURLs);
        } finally {
            // Ensure tools are cleaned up after execution
            if (toolRegistry != null) {
                toolRegistry.cleanup();
            }
        }
    }

    private AgentState executeInner(String sessionId, String messageId, String query,
            List<ChatMessage> llmContext, List<String> imageURLs) {
        // 顶层 Langfuse span：整轮归到一个节点（no-op 实现零成本）。
        int imgCount = imageURLs == null ? 0 : imageURLs.size();
        List<String> kbIds = new ArrayList<>();
        if (knowledgeBasesInfo != null) {
            for (AgentPrompts.KnowledgeBaseInfo kb : knowledgeBasesInfo) {
                if (kb != null) {
                    kbIds.add(kb.id());
                }
            }
        }
        Map<String, Object> spanInput = new LinkedHashMap<>();
        spanInput.put("query", truncateRunes(query, LANGFUSE_QUERY_PREVIEW));
        spanInput.put("query_len", query.length());
        spanInput.put("context_msgs", llmContext.size());
        spanInput.put("image_count", imgCount);
        Map<String, Object> spanMeta = new LinkedHashMap<>();
        spanMeta.put("session_id", sessionId);
        spanMeta.put("message_id", messageId);
        spanMeta.put("max_iterations", config.getMaxIterations());
        spanMeta.put("parallel_tool_calls", config.isParallelToolCalls());
        spanMeta.put("web_search", config.isWebSearchEnabled());
        spanMeta.put("multi_turn", config.isMultiTurnEnabled());
        spanMeta.put("knowledge_base_ids", kbIds);
        spanMeta.put("allowed_tools", config.getAllowedTools());
        Span agentSpan = LangfuseManager.get().startSpan(
                new LangfuseManager.SpanOptions("agent.execute", spanInput, spanMeta));

        // Initialize state
        AgentState state = new AgentState();
        state.setRoundSteps(new ArrayList<>());
        state.setKnowledgeRefs(new ArrayList<>());
        state.setComplete(false);
        state.setCurrentRound(0);

        String systemPrompt = prompts.buildSystemPrompt();
        log.debug("[Agent] SystemPrompt: {} chars", systemPrompt.length());

        List<String> imgs = imageURLs;
        List<ChatMessage> messages = prompts.buildMessagesWithLLMContext(systemPrompt, query, sessionId,
                llmContext, imgs);
        if (toolRegistry != null) {
            toolRegistry.rememberMcpHistory(messages);
            toolRegistry.refreshMcpTools();
        }

        List<ChatTool> tools = buildToolsForLLM();
        String toolListStr = String.join(", ", listToolNames(tools));
        log.info("[Agent] Ready: {} messages, {} tools [{}], mcp_catalog={} chars, {} images",
                messages.size(), tools.size(), toolListStr, mcpCatalogDescriptionLen(tools),
                imgs == null ? 0 : imgs.size());

        try {
            executeLoop(state, query, new MsgRef(messages), tools, sessionId, messageId);
        } catch (AgentEngineException e) {
            log.error("[Agent] Execution failed: {}", e.getMessage());
            eventBus.emit(new Event(EventIds.generateEventID("error"), EventType.EVENT_ERROR,
                    sessionId, new ErrorData(e.getMessage(), "", "agent_execution", sessionId,
                            "", null), null, ""));
            finishAgentSpan(agentSpan, state, e.getMessage());
            throw e;
        }

        log.info("[Agent] Completed: {} rounds, {} steps, complete={}",
                state.getCurrentRound(), state.getRoundSteps().size(), state.isComplete());
        finishAgentSpan(agentSpan, state, null);
        return state;
    }

    /** 成败共用同一份 span 载荷。 */
    private static void finishAgentSpan(Span span, AgentState state, String err) {
        if (span == null) {
            return;
        }
        int totalToolCalls = countTotalToolCalls(state.getRoundSteps());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("rounds", state.getCurrentRound());
        output.put("steps", state.getRoundSteps().size());
        output.put("tool_calls", totalToolCalls);
        output.put("complete", state.isComplete());
        output.put("final_answer_len", state.getFinalAnswer().length());
        output.put("final_answer", truncateRunes(state.getFinalAnswer(), LANGFUSE_QUERY_PREVIEW));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rounds", state.getCurrentRound());
        meta.put("steps", state.getRoundSteps().size());
        meta.put("tool_calls", totalToolCalls);
        meta.put("complete", state.isComplete());
        span.finish(output, meta, err);
    }

    /** rune 截断 + 尾加 "…"。 */
    static String truncateRunes(String s, int n) {
        if (n <= 0 || s.isEmpty()) {
            return s;
        }
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n)) + "…";
    }

    // ==================================================================
    // ReAct 主循环
    /** 消息列表的可变引用代理（模拟引用传参）。 */
    static final class MsgRef {
        List<ChatMessage> items;

        MsgRef(List<ChatMessage> items) {
            this.items = items;
        }
    }

    /** 一个 ReAct 迭代后的循环走向（label 供 langfuse 输出）。 */
    enum IterOutcome {
        NEXT("next"), CONTINUE("continue"), BREAK("break");

        private final String label;

        IterOutcome(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private void executeLoop(AgentState state, String query, MsgRef messagesRef,
            List<ChatTool> tools, String sessionId, String messageId) {
        Instant startTime = Instant.now();
        log.info("[PIPELINE] stage=Agent action=loop_start max_iterations={}", config.getMaxIterations());

        AtomicBoolean completionEmitted = new AtomicBoolean(false);
        try {
            AtomicInteger emptyRetries = new AtomicInteger();
            AtomicInteger consecutiveSameContent = new AtomicInteger();
            AtomicReference<String> lastResponseContent = new AtomicReference<>("");

            loop:
            while (withinIterationBudget(state.getCurrentRound()) || allowSteerOverrun) {
                allowSteerOverrun = false;
                // 轮首取消检查（请求超时/用户停止）。
                String cancelErr = pollCancellation();
                if (cancelErr != null) {
                    log.warn("[Agent] Context cancelled at round {}: {}", state.getCurrentRound() + 1,
                            cancelErr);
                    int totalTC = countTotalToolCalls(state.getRoundSteps());
                    if (totalTC > 0) {
                        log.info("[Agent] Synthesizing final answer from {} existing tool results", totalTC);
                        finalize.streamFinalAnswerToEventBus(query, state, sessionId, messagesRef.items);
                        state.setComplete(true);
                    }
                    throw new AgentEngineException(cancelErr, state);
                }

                // 上一轮结束后可能产生了新工具定义；只在轮首发布并重建 wire 列表。
                if (toolRegistry != null) {
                    toolRegistry.refreshMcpTools();
                    tools = buildToolsForLLM();
                }

                IterOutcome outcome = iteration.runReActIteration(state, messagesRef, tools,
                        sessionId, messageId, query, emptyRetries, consecutiveSameContent,
                        lastResponseContent);
                switch (outcome) {
                    case CONTINUE -> {
                        continue loop;
                    }
                    case BREAK -> {
                        break loop;
                    }
                    case NEXT -> state.setCurrentRound(state.getCurrentRound() + 1);
                }
            }

            // 循环走完没有最终答案就补一个——上下文被取消（用户停止）时跳过：
            // 兜底调用会在已取消的 ctx 上失败并把占位文案漏给用户。
            if (!state.isComplete() && pollCancellation() == null) {
                finalize.handleMaxIterations(query, state, sessionId, messagesRef.items);
            }
        } finally {
            if (completionEmitted.compareAndSet(false, true)) {
                finalize.emitCompletionEvent(state, sessionId, messageId, startTime);
            }
        }
    }

    /** WindowOutcome：manageContextWindow 的 (messages, changed)。 */
    record WindowOutcome(List<ChatMessage> messages, boolean changed) {
    }

    // ==================================================================
    // 响应分析保留面（TrimOutcome 族 + finish reason 判定，供测试与 ReAct 迭代共用）
    record TrimOutcome(List<ChatMessage> messages, boolean ok) {
    }

    /**
     * 返回下一次模型调用的消息副本。
     * 绝不改 SSE/诊断/持久化共用的 ToolResult 对象；assistant 工具调用消息不动，
     * 保住 provider 要求的 call/result 配对。每个工具结果都是候选——压缩后保留窗口
     * 全部是"近期"的，需要裁的那个大结果在头在尾都可能。
     */
    static TrimOutcome trimToolResultsToBudget(List<ChatMessage> messages,
            com.ragagent.agent.TokenEstimator estimator, int budget) {
        if (estimator == null || budget <= 0 || messages.isEmpty()) {
            return new TrimOutcome(messages, false);
        }

        List<Integer> toolIndexes = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("tool".equals(messages.get(i).getRole())) {
                toolIndexes.add(i);
                total += estimator.estimateMessage(messages.get(i));
            }
        }
        if (total <= budget || toolIndexes.isEmpty()) {
            return new TrimOutcome(messages, false);
        }

        List<ChatMessage> out = new ArrayList<>(messages);
        Map<Integer, Integer> baseCosts = new HashMap<>();
        int remaining = budget;
        for (int idx : toolIndexes) {
            ChatMessage copy = ObservePhase.shallowCopy(out.get(idx));
            copy.setContent(ObservePhase.compactedToolResultMarker(messages.get(idx).getContent()));
            out.set(idx, copy);
            int cost = estimator.estimateMessage(out.get(idx));
            baseCosts.put(idx, cost);
            remaining -= cost;
        }
        if (remaining < 0) {
            remaining = 0;
        }

        // 剩余预算从最新往最旧花；装不下完整版的拿到装得下的最大头尾预览。
        for (int i = toolIndexes.size() - 1; i >= 0; i--) {
            int idx = toolIndexes.get(i);
            int fullCost = estimator.estimateMessage(messages.get(idx));
            int extra = fullCost - baseCosts.get(idx);
            if (extra <= remaining) {
                out.set(idx, messages.get(idx));
                remaining -= extra;
                continue;
            }
            out.set(idx, ObservePhase.compactToolMessage(messages.get(idx), baseCosts.get(idx) + remaining, estimator));
            remaining = 0;
        }
        return new TrimOutcome(out, true);
    }

    /** 自然停 finish reason。 */
    static boolean isNaturalStopFinishReason(String reason) {
        String r = reason == null ? "" : reason.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (r) {
            case "stop", "end_turn", "stop_sequence" -> true;
            default -> false;
        };
    }

    /** 截断 finish reason。 */
    static boolean isLengthFinishReason(String reason) {
        String r = reason == null ? "" : reason.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (r) {
            case "length", "max_tokens", "max_output_tokens" -> true;
            default -> false;
        };
    }

    /**
     * 渲染本轮用户消息内容（含 runtime_context / must_use 注入块；实现见 {@link PromptAssembly}）。
     */
    public String renderUserTurnContent(String sessionId, String query) {
        return prompts.renderUserTurnContent(sessionId, query);
    }

    static List<String> listToolNames(List<ChatTool> tools) {
        List<String> names = new ArrayList<>(tools.size());
        for (ChatTool t : tools) {
            names.add(t.getFunction().getName());
        }
        return names;
    }

    static int mcpCatalogDescriptionLen(List<ChatTool> tools) {
        for (ChatTool t : tools) {
            if (ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS.equals(t.getFunction().getName())) {
                return t.getFunction().getDescription().length();
            }
        }
        return 0;
    }

    /** LLM 函数调用用的工具列表。 */
    List<ChatTool> buildToolsForLLM() {
        List<FunctionDef> functionDefs = toolRegistry.getModelFunctionDefinitions();
        List<ChatTool> tools = new ArrayList<>(functionDefs.size());
        for (FunctionDef def : functionDefs) {
            tools.add(new ChatTool(def.getName(), def.getDescription(), def.getParameters()));
        }
        return modelContext.encodeTools(tools);
    }

    static int countTotalToolCalls(List<AgentStep> steps) {
        int total = 0;
        if (steps == null) {
            return 0;
        }
        for (AgentStep step : steps) {
            total += step.getToolCalls() == null ? 0 : step.getToolCalls().size();
        }
        return total;
    }
}
