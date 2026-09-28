package com.ragagent.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.ragagent.agent.compaction.CompactionOverflow;
import com.ragagent.agent.compaction.CompactionReason;
import com.ragagent.agent.compaction.CompactionResult;
import com.ragagent.agent.compaction.CompactionSettings;
import com.ragagent.agent.compaction.Compactor;
import com.ragagent.agent.compaction.NothingToCompactException;
import com.ragagent.agent.domain.AgentState;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.domain.ToolCall;
import com.ragagent.agent.domain.ToolCallTarget;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.skills.Manager;
import com.ragagent.agent.skills.Skill;
import com.ragagent.agent.tools.SandboxExecuteResult;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.tools.ThinkStreamSplitter;
import com.ragagent.agent.tools.ExecutionPolicy;
import com.ragagent.agent.tools.JsonRepair;
import com.ragagent.agent.tools.MessageSanitizer;
import com.ragagent.agent.tools.NormalizeToolCallId;
import com.ragagent.agent.tools.SandboxDiffs;
import com.ragagent.agent.tools.ThinkBlocks;
import com.ragagent.agent.tools.ToolCancellation;
import com.ragagent.agent.tools.ToolExecContext;
import com.ragagent.agent.tools.ToolRegistry;
import com.ragagent.common.context.TenantContext;
import com.ragagent.event.AgentActionData;
import com.ragagent.event.AgentCompleteData;
import com.ragagent.event.AgentFinalAnswerData;
import com.ragagent.event.AgentThoughtData;
import com.ragagent.event.AgentToolCallData;
import com.ragagent.event.AgentToolResultData;
import com.ragagent.event.ContextCompactedData;
import com.ragagent.event.ErrorData;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventIds;
import com.ragagent.event.EventType;
import com.ragagent.event.TenantContextSnapshot;
import com.ragagent.event.UserMessageInjectedData;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.FunctionDef;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.modelcontext.Registry;
import com.ragagent.modelcontext.StreamDecoder;
import com.ragagent.tracing.langfuse.LangfuseManager;
import com.ragagent.tracing.langfuse.Span;
import com.ragagent.wiki.service.WikiLanguageSupport;

/**
 * ReAct agent 引擎（对照 Go internal/agent 根包的 engine/observe/think/act/finalize/
 * steer/context_debug 七个文件——Go 把方法摊在 {@code *AgentEngine} 上，Java 收进同一个
 * 类，分段注释即 Go 文件名）。
 *
 * <h2>无状态跨轮</h2>
 * <p>引擎跨轮无状态：会话历史每轮由调用方（service.LoadAgentHistory，4.6d）从 DB
 * 重建后经 {@code llmContext} 传入。引擎不维护自己的缓存、系统提示词存储或跨轮缓冲。</p>
 *
 * <h2>事件即 SSE 上游</h2>
 * <p>所有事件经 {@link EventBus} 发出（4.1 package-info 的 24 emit 点表中本类占 22 个）；
 * emit 顺序就是将来的 SSE 帧序，逐字对齐 Go。</p>
 *
 * <h2>Go → Java 语义映射（本波决策，报告备案）</h2>
 * <ul>
 *   <li><b>(result, error) 双通道</b> → 成功返回值 / 抛 {@link AgentEngineException}
 *       （message 逐字对照 Go 的 fmt.Errorf 文案——它是 error 事件字段原文）。</li>
 *   <li><b>context.Context</b> → 无 ctx；取消探测用
 *       {@link #setCancellationSource(Supplier)}（null=存活，非 null=错误原文，对照
 *       ctx.Err().Error()）；租户/主体在引擎线程解析成<b>显式值</b>传入虚拟线程，
 *       不读 ThreadLocal（纪律 #1）。</li>
 *   <li><b>切片/计数器指针</b>（messagesPtr 等）→ {@link MsgRef} / AtomicInteger /
 *       AtomicReference。</li>
 *   <li><b>complete 事件的 usage 键恒输出</b>：Go 的 typed-nil interface
 *       （nil *TokenUsage 装进 interface{}）不被 omitempty 省略 →
 *       {@code "usage":null}（实录钉住）；Java 用 {@link NullNode} 复刻，4.6d 消费侧按
 *       {@code usage instanceof TokenUsage} 判别。</li>
 *   <li><b>LLM 瞬态重试</b>的 sleep（1s/2s）保留。</li>
 * </ul>
 */
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** agent.execute span 输入里 query 的预览上限（对照 langfuseQueryPreview）。 */
    private static final int LANGFUSE_QUERY_PREVIEW = 2000;

    /** 循环结束注入最多多跑一轮（对照 maxSteerOverruns）。 */
    private static final int MAX_STEER_OVERRUNS = 1;

    /** 工具结果图片的 VLM 描述提示词（对照 toolImageAnalysisPrompt）。 */
    private static final String TOOL_IMAGE_ANALYSIS_PROMPT = "Describe the content of this image in detail. "
            + "If it contains text, extract all readable text. "
            + "If it contains charts or diagrams, describe the data and structure.";

    /** langfuse 工具输出预览上限（对照 langfuseToolOutputPreview）。 */
    private static final int LANGFUSE_TOOL_OUTPUT_PREVIEW = 4000;

    /** local_browser 工具名（Go 侧字面量使用处）。 */
    private static final String LOCAL_BROWSER_TOOL = "local_browser";

    /** 拒执行截断参数的模型可见文案（对照 truncatedArgumentsError）。 */
    static final String TRUNCATED_ARGUMENTS_ERROR = "Tool call was not executed: the model output was cut off "
            + "before the arguments finished, so they are incomplete rather than wrong. "
            + "Re-issue the call with a complete JSON object. If the payload is large, "
            + "split it across several smaller calls.";

    // ==================================================================
    // 引擎字段（对照 engine.go L35-69）
    // ==================================================================

    private final AgentConfig config;
    private final ToolRegistry toolRegistry;
    private final LlmChatClient chatModel;
    private final EventBus eventBus;
    /** 绑定知识库详情（提示词用）；测试需要直改 → 包内可见。 */
    List<AgentPrompts.KnowledgeBaseInfo> knowledgeBasesInfo;
    /** 用户 @ 选中的文档。 */
    List<AgentPrompts.SelectedDocumentInfo> selectedDocs;
    /** 本轮 @ 指定的 MCP 服务。 */
    private List<AgentPrompts.PinnedMCPServiceInfo> pinnedMCPServices = List.of();
    /** 本轮 @ 指定的技能。 */
    private List<AgentPrompts.PinnedSkillInfo> pinnedSkills = List.of();
    /** 引擎自己的 session id（emitContextCompacted 用它，不是 Execute 的入参）。 */
    private final String sessionId;
    private String systemPromptTemplate;
    private String memoryPrompt = "";
    private Manager skillsManager;
    /** 提示词模板解析配置（对照 appConfig；null = 默认 base）。 */
    private AgentPromptTemplates.TemplatesConfig appConfig;
    /** 工具结果图片的 VLM 描述函数（可选）。 */
    private ImageDescriberFunc imageDescriber;
    private final com.ragagent.agent.TokenEstimator tokenEstimator = new com.ragagent.agent.TokenEstimator();
    private Compactor compactor;
    /** 最近一次 LLM 调用的用量（usage 基线）。 */
    TokenUsage lastUsage = new TokenUsage();
    /** 最近一次 LLM 调用发送的消息数。 */
    int lastSentMsgCount;
    /** 本轮已用过的一次溢出压缩重试。 */
    private boolean overflowRecovered;
    /** 压缩上次"腾不出空间"时的消息数。 */
    private int compactionExhaustedAt;
    /** 请求内 model-context 边界。 */
    private final Registry modelContext;
    /** 运行中注入通道；null = 禁用。 */
    private SteerSink steerSink;
    private boolean allowSteerOverrun;
    private int steerOverruns;
    /** 取消探测（对照 ctx.Done()；null = 永不取消）。 */
    private Supplier<String> cancellationSource;

    /** 图片描述函数（对照 Go ImageDescriberFunc：func(ctx, imgBytes, prompt) (string, error)）。 */
    @FunctionalInterface
    public interface ImageDescriberFunc {
        /** 描述一张图片；失败抛异常（对照 error 通道）。 */
        String describe(byte[] imgBytes, String prompt);
    }

    // ==================================================================
    // 构造（对照 NewAgentEngine / NewAgentEngineWithSkills）
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
    }

    /** 对照 NewAgentEngineWithSkills。 */
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

    /** 对照 SetPinnedMentions：本轮 @mention 范围。 */
    public void setPinnedMentions(List<AgentPrompts.PinnedMCPServiceInfo> mcpServices,
            List<AgentPrompts.PinnedSkillInfo> skills) {
        this.pinnedMCPServices = mcpServices == null ? List.of() : mcpServices;
        this.pinnedSkills = skills == null ? List.of() : skills;
    }

    /** 对照 SetMemoryPrompt：空输入不改动系统提示词。 */
    public void setMemoryPrompt(String prompt) {
        this.memoryPrompt = prompt == null ? "" : prompt;
    }

    /** 对照 SetAppConfig（读 config/prompt_templates/ 的模板解析配置）。 */
    public void setAppConfig(AgentPromptTemplates.TemplatesConfig cfg) {
        this.appConfig = cfg;
    }

    /** 对照 SetImageDescriber。 */
    public void setImageDescriber(ImageDescriberFunc fn) {
        this.imageDescriber = fn;
    }

    public void setSkillsManager(Manager manager) {
        this.skillsManager = manager;
    }

    public Manager getSkillsManager() {
        return skillsManager;
    }

    /** 对照 SetSteerSink：null（默认）= 完全禁用运行中注入。 */
    public void setSteerSink(SteerSink sink) {
        this.steerSink = sink;
    }

    /**
     * 取消探测 seam（对照把 ctx 传进 Execute）：存活返回 null、取消返回
     * {@code ctx.Err().Error()} 原文的探针；4.6d 的 stop 链路接线它。
     */
    public void setCancellationSource(Supplier<String> source) {
        this.cancellationSource = source;
    }

    private String pollCancellation() {
        return cancellationSource == null ? null : cancellationSource.get();
    }

    // ---- 包内测试 seam（Go 同包测试直改字段的对应物；生产装配走构造器/setter）----

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

    // ==================================================================
    // engine.go：系统提示词与预算
    // ==================================================================

    private AgentPrompts.BuildSystemPromptOptions systemPromptOptions() {
        AgentPrompts.BuildSystemPromptOptions opts = new AgentPrompts.BuildSystemPromptOptions()
                .setLanguage(WikiLanguageSupport.languageNameFromContext())
                .setConfig(appConfig)
                .setSkillInstallMode(config != null && config.isSkillInstallMode())
                .setMemoryPrompt(memoryPrompt)
                .setProtocolPrompt(modelContext.protocolPrompt());
        List<Skill.SkillMetadata> allMetadata = skillsManager != null && skillsManager.isEnabled()
                ? skillsManager.getAllMetadata() : null;
        if (toolRegistry != null) {
            opts.setSelectedTools(toolRegistry.listTools());
            try {
                toolRegistry.getTool(LOCAL_BROWSER_TOOL);
                // local_browser 在场时隐藏内建 browser 技能（能力重叠）。
                List<Skill.SkillMetadata> filtered = new ArrayList<>();
                if (allMetadata != null) {
                    for (Skill.SkillMetadata item : allMetadata) {
                        if (item != null && !"browser".equals(item.name())
                                && !"browser-skill".equals(item.name())) {
                            filtered.add(item);
                        }
                    }
                }
                opts.setSkillsMetadata(toAgentSkillMetadata(filtered));
            } catch (ToolRegistry.ToolNotFoundException e) {
                opts.setSkillsMetadata(toAgentSkillMetadata(allMetadata));
            }
            try {
                toolRegistry.getTool(ToolDefinitions.TOOL_SHELL_EXEC);
                opts.setShellExecEnabled(true);
            } catch (ToolRegistry.ToolNotFoundException e) {
                opts.setShellExecEnabled(false);
            }
        } else {
            opts.setSkillsMetadata(toAgentSkillMetadata(allMetadata));
        }
        return opts;
    }

    private static List<com.ragagent.agent.SkillMetadata> toAgentSkillMetadata(List<Skill.SkillMetadata> in) {
        if (in == null) {
            return null;
        }
        List<com.ragagent.agent.SkillMetadata> out = new ArrayList<>(in.size());
        for (Skill.SkillMetadata m : in) {
            out.add(new com.ragagent.agent.SkillMetadata(m.name(), m.description(), m.basePath()));
        }
        return out;
    }

    String buildSystemPrompt() {
        List<AgentPrompts.SystemPromptSection> sections = AgentPrompts.buildSystemPromptSections(
                knowledgeBasesInfo, config != null && config.isWebSearchEnabled(),
                systemPromptOptions(), LocalDate.now(), systemPromptTemplate);
        for (AgentPrompts.SystemPromptSection section : sections) {
            log.debug("[Agent][Prompt] section={} bytes={}", section.name(),
                    section.content() == null ? 0 : section.content().length());
        }
        return AgentPrompts.renderSystemPromptSections(sections);
    }

    /**
     * 当前上下文 token 的最优估计：有上一轮 API 用量时以它为基线，只 BPE 估其后新增的
     * 消息；否则纯按消息大小估。工具 schema 不加进来。
     */
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

    /** 自然停答案真正收束时发 Done:true；循环结束注入跳过它（客户端不掉 isReplying）。 */
    private void closeAnswerStream(String sessionID, String answerId) {
        if (eventBus == null || answerId == null || answerId.isEmpty()) {
            return;
        }
        eventBus.emit(new Event(answerId, EventType.EVENT_AGENT_FINAL_ANSWER, sessionID,
                new AgentFinalAnswerData("", true, false), null, ""));
    }

    String maxIterationsDisplay() {
        if (config != null && config.unlimitedIterations()) {
            return "unlimited";
        }
        return String.valueOf(config == null ? 0 : config.getMaxIterations());
    }

    /** 单轮 ReAct 的补全预算（对照 getCompletionTokenBudget）。 */
    int getCompletionTokenBudget() {
        int configured = 0;
        String sandboxId = "";
        if (config != null) {
            configured = config.getMaxCompletionTokens();
            sandboxId = config.getSandboxConfigId();
        }
        return AgentBudgets.agentRoundMaxCompletionTokensFor(configured, sandboxId);
    }

    private int contextReserveTokens() {
        return AgentConsts.contextReserveTokens(getCompletionTokenBudget());
    }

    private int clampCompletionBudgetToContext(int currentTokens) {
        int budget = getCompletionTokenBudget();
        if (config == null || config.getMaxContextTokens() <= 0) {
            return budget;
        }
        return AgentConsts.clampCompletionBudgetToContext(config.getMaxContextTokens(), currentTokens, budget);
    }

    private Duration getLLMStallTimeout() {
        return AgentConsts.llmStallTimeout(config == null ? null : config.getLlmCallTimeout());
    }

    // ==================================================================
    // engine.go：Execute 主入口
    // ==================================================================

    /** 便捷重载（无图片）。 */
    public AgentState execute(String sessionId, String messageId, String query,
            List<ChatMessage> llmContext) {
        return execute(sessionId, messageId, query, llmContext, null);
    }

    /**
     * 执行 agent：带会话历史与流式输出（对照 Execute）。
     *
     * @param imageURLs 多模态输入图片（对照变长参只取第一组）
     * @throws AgentEngineException 失败时（error 事件已在抛出前发出）
     */
    public AgentState execute(String sessionId, String messageId, String query,
            List<ChatMessage> llmContext, List<String> imageURLs) {
        // Go 的 nil slice 在 len()/range 下等价空集——调用方（如 skill 安装器的
        // installer run）可以合法地传 nil。Java 的 null List 会在入口日志就 NPE
        // （2026-09-25 install E2E 抓回：installer agent failed: Cannot invoke
        // "java.util.List.size()" because "llmContext" is null），这里按 Go 语义归一。
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
            // Ensure tools are cleaned up after execution（defer toolRegistry.Cleanup）
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

        String systemPrompt = buildSystemPrompt();
        log.debug("[Agent] SystemPrompt: {} chars", systemPrompt.length());

        List<String> imgs = imageURLs;
        List<ChatMessage> messages = buildMessagesWithLLMContext(systemPrompt, query, sessionId,
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

    /** 对照 finishAgentSpan：成败共用同一份 span 载荷。 */
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

    /** rune 截断 + 尾加 "…"（对照 truncateRunes）。 */
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
    // engine.go：executeLoop / runReActIteration
    // ==================================================================

    /** Go 的 {@code *[]chat.Message} 参数代理。 */
    static final class MsgRef {
        List<ChatMessage> items;

        MsgRef(List<ChatMessage> items) {
            this.items = items;
        }
    }

    /** 一个 ReAct 迭代后的循环走向（对照 iterOutcome；label 供 langfuse 输出）。 */
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
                        streamFinalAnswerToEventBus(query, state, sessionId, messagesRef.items);
                        state.setComplete(true);
                    }
                    throw new AgentEngineException(cancelErr, state);
                }

                // 上一轮结束后可能产生了新工具定义；只在轮首发布并重建 wire 列表。
                if (toolRegistry != null) {
                    toolRegistry.refreshMcpTools();
                    tools = buildToolsForLLM();
                }

                IterOutcome outcome = runReActIteration(state, messagesRef, tools,
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
                handleMaxIterations(query, state, sessionId, messagesRef.items);
            }
        } finally {
            if (completionEmitted.compareAndSet(false, true)) {
                emitCompletionEvent(state, sessionId, messageId, startTime);
            }
        }
    }

    /**
     * 一个 ReAct 步：think → analyze → act → observe（对照 runReActIteration）。
     * 整个迭代体在一个 span 作用域里，所有出口都触发 Finish。
     */
    private IterOutcome runReActIteration(
            AgentState state, MsgRef messagesRef, List<ChatTool> tools,
            String sessionId, String assistantMessageId, String query,
            AtomicInteger emptyRetries, AtomicInteger consecutiveSameContent,
            AtomicReference<String> lastResponseContent) {
        Instant roundStart = Instant.now();
        int round = state.getCurrentRound() + 1;

        Map<String, Object> spanInput = new LinkedHashMap<>();
        spanInput.put("round", round);
        spanInput.put("message_count", messagesRef.items.size());
        spanInput.put("max_iterations", config.getMaxIterations());
        Map<String, Object> spanMeta = new LinkedHashMap<>();
        spanMeta.put("iteration", state.getCurrentRound());
        spanMeta.put("round", round);
        spanMeta.put("session_id", sessionId);
        Span roundSpan = LangfuseManager.get().startSpan(
                new LangfuseManager.SpanOptions("agent.round." + round, spanInput, spanMeta));

        ChatResponse[] responseHolder = new ChatResponse[1];
        int[] toolCallCount = {0};
        IterOutcome[] outcomeHolder = {null};
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

    private void finishRoundSpan(Span roundSpan, Instant roundStart, int round, IterOutcome outcome,
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

    private IterOutcome runReActIterationBody(
            AgentState state, MsgRef messagesRef, List<ChatTool> tools,
            String sessionId, String assistantMessageId, String query,
            AtomicInteger emptyRetries, AtomicInteger consecutiveSameContent,
            AtomicReference<String> lastResponseContent,
            ChatResponse[] responseHolder, int[] toolCallCount, Instant roundStart, int round) {

        // 下一响应前压缩老历史（触发估计不计工具 schema）。
        int currentTokens = estimateCurrentTokens(messagesRef.items);
        WindowOutcome managed = manageContextWindow(messagesRef.items, round, currentTokens);
        if (managed.changed()) {
            messagesRef.items = managed.messages();
            currentTokens = tokenEstimator.estimateMessages(managed.messages());
        }

        // 轮首 drain steer：压缩之后（注入文本落在保护尾内）、lastSentMsgCount 更新之前。
        drainSteerMessages(state, messagesRef, sessionId, assistantMessageId);

        log.info("[Agent][Round-{}/{}] Starting: {} messages, {} tools, est_tokens={}, tenantId={}",
                round, maxIterationsDisplay(), messagesRef.items.size(), tools.size(), currentTokens,
                com.ragagent.common.context.TenantContext.currentTenantId());
        logContextPrediction(round, messagesRef.items, tools, currentTokens);
        log.info("[PIPELINE] stage=Agent action=round_start iteration={} round={} message_count={} pending_tools={} max_iterations={}",
                state.getCurrentRound(), round, messagesRef.items.size(), tools.size(),
                config.getMaxIterations());

        // 1. Think：带重试与优雅降级的 LLM 调用
        lastSentMsgCount = messagesRef.items.size();
        ChatResponse response = callLLMWithRetry(messagesRef, tools, state, query,
                state.getCurrentRound(), sessionId);
        if (response == null) {
            return IterOutcome.BREAK;
        }

        // 该轮自己的估计可能偏小——历史是估的。压缩一次再重试把溢出变成回收的轮次；
        // 每轮限一次：重试也溢出说明问题不在历史大小。
        if (!overflowRecovered && responseHitContextLimit(response)) {
            overflowRecovered = true;
            log.warn("[Agent][Round-{}] Response hit the context window (finish={}, completion={} of {} requested); compacting and retrying once",
                    round, response.getFinishReason(), response.getUsage().getCompletionTokens(),
                    getCompletionTokenBudget());
            messagesRef.items = forceCompaction(messagesRef.items, round);
            lastSentMsgCount = messagesRef.items.size();
            response = callLLMWithRetry(messagesRef, tools, state, query, state.getCurrentRound(),
                    sessionId);
            if (response == null) {
                return IterOutcome.BREAK;
            }
        }
        responseHolder[0] = response;
        logContextDrift(round, currentTokens, response.getUsage());
        if (response.getUsage().getTotalTokens() > 0) {
            lastUsage = response.getUsage();
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
                return IterOutcome.BREAK;
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
        if (pollCancellation() != null) {
            log.warn("[Agent][Round-{}] Context cancelled during LLM call; preserving partial step", round);
            boolean hasContent = step.getThought() != null && !step.getThought().isEmpty();
            boolean hasCalls = step.getToolCalls() != null && !step.getToolCalls().isEmpty();
            boolean hasSteer = step.getUserMessagesBefore() != null && !step.getUserMessagesBefore().isEmpty();
            if (hasContent || hasCalls || hasSteer) {
                state.getRoundSteps().add(step);
            }
            return IterOutcome.BREAK;
        }

        // 2. Analyze：检查停止条件
        ResponseVerdict verdict = analyzeResponse(response, step, state.getCurrentRound(), roundStart, sessionId);
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
                    return IterOutcome.CONTINUE;
                }
                log.warn("[Agent][Round-{}] Empty content after {} retries - using fallback",
                        round, AgentConsts.MAX_EMPTY_RESPONSE_RETRIES);
                state.setFinalAnswer("I'm sorry, I was unable to generate a response. Please try again.");
                state.setComplete(true);
                state.getRoundSteps().add(verdict.step);
                closeAnswerStream(sessionId, verdict.answerID);
                return IterOutcome.BREAK;
            }
            // 循环结束注入：本轮收束期间排进来的用户消息让 agent 继续而不是收束答案。
            // content_filter 停止是终态，不走这条路。
            if (!"content_filter".equals(response.getFinishReason())) {
                int nextRound = state.getCurrentRound() + 1;
                boolean canContinue = withinIterationBudget(nextRound) || steerOverruns < MAX_STEER_OVERRUNS;
                if (canContinue) {
                    ChatMessage assistant = new ChatMessage("assistant", verdict.finalAnswer);
                    assistant.setReasoningContent(response.getReasoningContent());
                    messagesRef.items.add(assistant);
                    int injected = drainSteerMessages(state, messagesRef, sessionId, assistantMessageId);
                    if (injected > 0) {
                        verdict.step.setIntermediateAnswer(true);
                        state.getRoundSteps().add(verdict.step);
                        if (!withinIterationBudget(nextRound)) {
                            steerOverruns++;
                            allowSteerOverrun = true;
                        }
                        return IterOutcome.NEXT;
                    }
                }
            }
            state.setFinalAnswer(verdict.finalAnswer);
            state.setComplete(true);
            state.getRoundSteps().add(verdict.step);
            closeAnswerStream(sessionId, verdict.answerID);
            return IterOutcome.BREAK;
        }

        // 本轮非终态（要执行工具再进下一轮）。本轮直播到答案区的纯文本是 preamble 而非答案；
        // 无需显式撤回信号——后续的 tool-call 事件就是权威的"那不是最终答案"标记。

        // 3. Act：执行工具调用
        executeToolCalls(response, step, state.getCurrentRound(), sessionId, assistantMessageId);
        toolCallCount[0] = step.getToolCalls().size();

        // 4. Observe：工具结果进消息
        state.getRoundSteps().add(step);
        messagesRef.items = appendToolResults(messagesRef.items, step);
        messagesRef.items = appendToolImages(messagesRef.items, step);
        log.info("[PIPELINE] stage=Agent action=round_end iteration={} round={} tool_calls={} thought_len={}",
                state.getCurrentRound(), round, toolCallCount[0], step.getThought().length());

        return IterOutcome.NEXT;
    }

    /** WindowOutcome：manageContextWindow 的 (messages, changed)。 */
    record WindowOutcome(List<ChatMessage> messages, boolean changed) {
    }

    // ==================================================================
    // engine.go：工具结果图片 VLM 描述
    // ==================================================================

    private List<String> describeImages(List<String> imageDataURIs) {
        if (imageDescriber == null) {
            return null;
        }
        List<String> descriptions = null;
        for (int i = 0; i < imageDataURIs.size(); i++) {
            String dataURI = imageDataURIs.get(i);
            if (pollCancellation() != null) {
                log.warn("[Agent] Context cancelled, skipping remaining {} tool result images",
                        imageDataURIs.size() - i);
                break;
            }
            byte[] imgBytes;
            try {
                imgBytes = decodeDataURIBytes(dataURI);
            } catch (Exception e) {
                log.warn("[Agent] Failed to decode tool result image {}: {}", i, e.getMessage());
                continue;
            }
            String desc;
            try {
                desc = imageDescriber.describe(imgBytes, TOOL_IMAGE_ANALYSIS_PROMPT);
            } catch (Exception e) {
                log.warn("[Agent] VLM analysis failed for tool result image {}: {}", i, e.getMessage());
                continue;
            }
            String trimmed = desc == null ? "" : desc.trim();
            if (!trimmed.isEmpty()) {
                if (descriptions == null) {
                    descriptions = new ArrayList<>();
                }
                descriptions.add(trimmed);
            }
        }
        return descriptions;
    }

    /** "data:mime;base64,..." → 原始字节；标准解码失败退回无填充解码。 */
    static byte[] decodeDataURIBytes(String dataURI) {
        if (!dataURI.startsWith("data:")) {
            throw new IllegalArgumentException("not a data URI");
        }
        int idx = dataURI.indexOf(";base64,");
        if (idx < 0) {
            throw new IllegalArgumentException("unsupported data URI encoding (expected base64)");
        }
        String raw = dataURI.substring(idx + 8);
        try {
            return java.util.Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            // 一些 MCP 服务器省略尾随 '='——去填充后重试。
            int end = raw.length();
            while (end > 0 && raw.charAt(end - 1) == '=') {
                end--;
            }
            return java.util.Base64.getDecoder().decode(raw.substring(0, end));
        }
    }

    // ==================================================================
    // think.go：LLM 流消费
    // ==================================================================

    /** 流式 LLM 调用的累计输出（对照 streamLLMResult）。 */
    static final class StreamLLMResult {
        String content = "";
        String reasoningContent = "";
        List<com.ragagent.llm.domain.ToolCall> toolCalls;
        TokenUsage usage;
        String finishReason = "";
        String streamError = "";
    }

    /** think 阶段的分片发射回调（对照 emitFunc）。 */
    interface ThinkChunkEmitter {
        void accept(StreamResponse chunk, String fullContent);
    }

    /**
     * LLM 流经 EventBus 直发（对照 streamLLMToEventBus）。emit 为 null 只累计不发射。
     * 流错误（含停顿）→ 抛 {@link AgentEngineException}（message = "LLM stream error: ..."）。
     */
    StreamLLMResult streamLLMToEventBus(List<ChatMessage> messages, ChatOptions opts,
            ThinkChunkEmitter emit) {
        log.debug("[Agent][Stream] Starting LLM stream with {} messages", messages.size());

        // Model-context 编码独占 codec 顺序与临时句柄生命周期。
        List<ChatMessage> encoded = modelContext.encodeMessages(messages);
        java.util.concurrent.BlockingQueue<StreamResponse> stream;
        try {
            stream = chatModel.chatStream(encoded, opts);
        } catch (RuntimeException e) {
            log.error("[Agent][Stream] Failed to start LLM stream: {}",
                    e.getMessage() == null ? e.toString() : e.getMessage());
            throw new AgentEngineException(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        StreamLLMResult result = new StreamLLMResult();
        int[] chunkCount = {0};
        Map<String, Integer> responseTypeCounts = new HashMap<>();
        StreamDecoder answerDecoder = modelContext.streamDecoder();
        StreamDecoder thinkingDecoder = modelContext.streamDecoder();

        // 看门狗在请求发出前置位——time-to-first-token 也在约束内。
        Duration stallTimeout = getLLMStallTimeout();
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
                modelContext.decodeToolCalls(chunk.getToolCalls());
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
            // THINKING + done=true 是生产者中途补的 thinking-done 标记——Go 侧该分片
            // 之后 channel 仍开，后续分片照常消费；这里同样继续。
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
            modelContext.decodeToolCalls(result.toolCalls);
            for (com.ragagent.llm.domain.ToolCall toolCall : result.toolCalls) {
                if (toolCall.getUnresolvedHandles() == null || toolCall.getUnresolvedHandles().isEmpty()) {
                    continue;
                }
                log.warn("[Agent][Stream] Tool {} ({}) contains unresolvable model handle(s): {}",
                        toolCall.getFunction().getName(), toolCall.getId(), toolCall.getUnresolvedHandles());
            }
        }
        List<String> orphans = modelContext.orphanResourceHandles(result.content);
        if (orphans != null && !orphans.isEmpty()) {
            log.warn("[Agent][Stream] Model emitted {} unresolvable resource handle(s): {}",
                    orphans.size(), orphans);
        }

        // 看门狗取消的是 provider 上下文，流只会冒出泛化取消——改述成停顿。
        if (stalled.get()) {
            result.streamError = "LLM stream stalled: no output for "
                    + SandboxExecuteResult.GoDuration.of(stallTimeout);
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
     * 停顿看门狗语义（对照 watchStreamStall）：超过 stallTimeout 无输出 → 置位 stalled
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
                            SandboxExecuteResult.GoDuration.of(
                                    Duration.ofNanos(System.nanoTime() - lastChunkAt.get())),
                            SandboxExecuteResult.GoDuration.of(stallTimeout));
                    stalled.set(true);
                    return null;
                }
                continue;
            }
            return chunk;
        }
    }

    /**
     * 思考过程流经 EventBus（对照 streamThinkingToEventBus）：pending/progress 工具事件、
     * thinking 通道与内联 think 拆分、答案直播。
     */
    ChatResponse streamThinkingToEventBus(List<ChatMessage> messages, List<ChatTool> tools,
            int iteration, String sessionId) {
        int budget = clampCompletionBudgetToContext(tokenEstimator.estimateMessages(messages));
        log.debug("[Agent][Thinking] Iteration-{}: temp={}, tools={}, thinking={}, max_tokens={}",
                iteration + 1, String.format(java.util.Locale.ROOT, "%.2f", config.getTemperature()),
                tools.size(), config.getThinking(), budget);

        ChatOptions opts = new ChatOptions();
        opts.setTemperature(config.getTemperature());
        opts.setMaxCompletionTokens(budget);
        opts.setTools(tools);
        opts.setThinking(config.getThinking());
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
                String toolCallID = mapString(chunk.getData(), "tool_call_id");
                String toolName = mapString(chunk.getData(), "tool_name");
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) chunk.getData().get("arguments");

                if (!toolCallID.isEmpty() && !toolName.isEmpty()
                        && !pendingToolCalls.containsKey(toolCallID)) {
                    pendingToolCalls.put(toolCallID, Boolean.TRUE);
                    emittedEventTypes.merge("tool_call_pending", 1, Integer::sum);
                    eventBus.emit(new Event(toolCallID + "-tool-call-pending",
                            EventType.EVENT_AGENT_TOOL_CALL, sessionId,
                            new AgentToolCallData(toolCallID, toolName, deepSortedGoMap(args),
                                    iteration, ""), null, ""));
                } else if (!toolCallID.isEmpty() && pendingToolCalls.containsKey(toolCallID)
                        && args != null) {
                    emittedEventTypes.merge("tool_call_progress", 1, Integer::sum);
                    eventBus.emit(new Event(toolCallID + "-tool-call-progress",
                            EventType.EVENT_AGENT_TOOL_CALL, sessionId,
                            new AgentToolCallData(toolCallID, toolName, deepSortedGoMap(args),
                                    iteration, ""), null, ""));
                }
            }

            // thinking 工具的流式思考内容
            if (chunk.getResponseType() == ResponseType.THINKING && chunk.getData() != null) {
                if ("thinking_tool".equals(mapString(chunk.getData(), "source"))) {
                    String toolCallID = mapString(chunk.getData(), "tool_call_id");
                    String eventID = thinkingToolIDs.computeIfAbsent(toolCallID,
                            k -> EventIds.generateEventID("thinking-tool"));
                    emittedEventTypes.merge("thinking_tool_chunk", 1, Integer::sum);
                    eventBus.emit(new Event(eventID, EventType.EVENT_AGENT_THOUGHT, sessionId,
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
        eventBus.emit(new Event(thinkingID, EventType.EVENT_AGENT_THOUGHT, sessionId,
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
        eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionId,
                new AgentFinalAnswerData(content, false, false), null, ""));
    }

    /**
     * 一轮 ReAct 的 LLM 调用（瞬态重试 + 优雅降级；对照 callLLMWithRetry）。
     * 返回 null = 优雅降级成功（state.IsComplete 已置位）。
     */
    private ChatResponse callLLMWithRetry(MsgRef messagesRef, List<ChatTool> tools,
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
        if (error != null && !overflowRecovered
                && CompactionOverflow.isOverflowError(error.getMessage() == null ? "" : error.getMessage())) {
            overflowRecovered = true;
            log.warn("[Agent][Round-{}] Provider rejected the request as too large; compacting and retrying once: {}",
                    round, error.getMessage());
            // 引擎的副本保持未清洗。清洗会并掉连续同角色消息，而摘要是条 user 消息、
            // 可能紧贴真用户消息——并掉会把活对话折进摘要信封，下次压缩读不出来。
            List<ChatMessage> compacted = forceCompaction(messages, round);
            messagesRef.items = compacted;
            messages = MessageSanitizer.sanitizeMessages(compacted);
            lastSentMsgCount = compacted.size();
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
            int totalTC = countTotalToolCalls(state.getRoundSteps());
            if (totalTC > 0) {
                log.warn("[Agent] LLM failed but have {} steps with {} tool calls — attempting final answer synthesis from existing results",
                        state.getRoundSteps().size(), totalTC);
                log.warn("[PIPELINE] stage=Agent action=llm_failed_synthesizing steps={} tool_calls={}",
                        state.getRoundSteps().size(), totalTC);
                try {
                    streamFinalAnswerToEventBus(query, state, sessionId, messages);
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
            if (isNaturalStopFinishReason(response.getFinishReason())) {
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

    // ==================================================================
    // act.go：工具调用编排
    // ==================================================================

    /** 内部工具名 → 展示名（对照 toolDisplayNames）。 */
    private static final Map<String, String> TOOL_DISPLAY_NAMES = buildToolDisplayNames();

    private static Map<String, String> buildToolDisplayNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS, "查看外部工具");
        m.put(ToolDefinitions.TOOL_CALL_MCP_TOOL, "调用外部工具");
        m.put(ToolDefinitions.TOOL_THINKING, "深度思考");
        m.put(ToolDefinitions.TOOL_TODO_WRITE, "制定计划");
        m.put(ToolDefinitions.TOOL_GREP_CHUNKS, "关键词搜索");
        m.put(ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, "知识搜索");
        m.put(ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, "查看文档分块");
        m.put(ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH, "查询知识图谱");
        m.put(ToolDefinitions.TOOL_GET_DOCUMENT_INFO, "获取文档信息");
        m.put(ToolDefinitions.TOOL_SEARCH_CONVERSATIONS, "回顾历史对话");
        m.put(ToolDefinitions.TOOL_SEARCH_MEMORY, "查询长期记忆");
        m.put(ToolDefinitions.TOOL_DATABASE_QUERY, "查询数据");
        m.put(ToolDefinitions.TOOL_DATA_ANALYSIS, "数据分析");
        m.put(ToolDefinitions.TOOL_DATA_SCHEMA, "查看数据结构");
        m.put(ToolDefinitions.TOOL_WEB_SEARCH, "搜索网页");
        m.put(ToolDefinitions.TOOL_WEB_FETCH, "获取网页");
        m.put(ToolDefinitions.LEGACY_TOOL_EXECUTE_SKILL_SCRIPT, "执行技能脚本");
        m.put(ToolDefinitions.LEGACY_TOOL_READ_SKILL, "读取技能");
        m.put(ToolDefinitions.TOOL_READ_FILE, "读取文件");
        m.put(ToolDefinitions.TOOL_LIST_SANDBOX_FILES, "列出沙箱文件");
        m.put(ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE, "读取沙箱文件");
        m.put(ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, "写入沙箱文件");
        m.put(ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, "编辑沙箱文件");
        m.put(ToolDefinitions.TOOL_SHELL_EXEC, "执行沙箱命令");
        return m;
    }

    /** 参数不该进提示的工具（对照 toolHintSensitiveArgs；SQL 泄漏实现细节）。 */
    private static final Map<String, Boolean> TOOL_HINT_SENSITIVE_ARGS = Map.of(
            ToolDefinitions.TOOL_DATABASE_QUERY, true);

    /** 工具调用的人读提示，如 `搜索网页("query")`（对照 formatToolHint）。 */
    static String formatToolHint(String name, Map<String, Object> args) {
        String displayName = TOOL_DISPLAY_NAMES.getOrDefault(name, name);
        if (args == null || args.isEmpty() || Boolean.TRUE.equals(TOOL_HINT_SENSITIVE_ARGS.get(name))) {
            return displayName;
        }
        for (Object v : args.values()) {
            if (v instanceof String s) {
                if (s.length() > 40) {
                    s = s.substring(0, 40) + "…";
                }
                return displayName + "(\"" + s + "\")";
            }
        }
        return displayName;
    }

    /** 本轮全部工具调用入口（对照 executeToolCalls）。 */
    private void executeToolCalls(ChatResponse response, AgentStep step, int iteration,
            String sessionId, String assistantMessageID) {
        if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
            return;
        }
        int round = iteration + 1;
        int n = response.getToolCalls().size();

        // 补全预算从中间切断响应 → 每个调用的参数都可能不完整。执行比失败更糟：
        // 截断的 write_sandbox_file 会写半个文件还报成功。
        if (isLengthFinishReason(response.getFinishReason())) {
            log.warn("[Agent][Round-{}] Response hit the completion-token cap (finish={}); refusing {} tool call(s) with possibly truncated arguments",
                    round, response.getFinishReason(), n);
            failTruncatedToolCalls(response, step, iteration, sessionId);
            return;
        }

        log.info("[Agent][Round-{}] Executing {} tool call(s)", round, n);

        if (config.isParallelToolCalls() && n >= 2) {
            executeToolCallsParallel(response, step, iteration, sessionId, assistantMessageID);
            return;
        }
        for (int i = 0; i < n; i++) {
            executeSingleToolCall(response.getToolCalls().get(i), i, step, iteration, round,
                    sessionId, assistantMessageID);
        }
    }

    private void failTruncatedToolCalls(ChatResponse response, AgentStep step, int iteration,
            String sessionId) {
        List<com.ragagent.llm.domain.ToolCall> calls = response.getToolCalls();
        for (int i = 0; i < calls.size(); i++) {
            com.ragagent.llm.domain.ToolCall tc = calls.get(i);
            ToolCall toolCall = new ToolCall();
            toolCall.setId(NormalizeToolCallId.normalize(tc.getId(), tc.getFunction().getName(), i));
            toolCall.setName(tc.getFunction().getName());
            Map<String, Object> rawArgs = new LinkedHashMap<>();
            rawArgs.put("_raw", tc.getFunction().getArguments());
            toolCall.setArgs(rawArgs);
            toolCall.setProviderMetadata(tc.getProviderMetadata());
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(TRUNCATED_ARGUMENTS_ERROR);
            toolCall.setResult(r);
            step.getToolCalls().add(toolCall);
            emitToolOutcome(toolCall, iteration, sessionId);
        }
    }

    /**
     * errgroup 并发（读并行、写屏障、并发上限 8；对照 executeToolCallsParallel）。
     * 租户/主体在引擎线程解析成显式值传入虚拟线程——不共享 ThreadLocal。
     */
    private void executeToolCallsParallel(ChatResponse response, AgentStep step, int iteration,
            String sessionId, String assistantMessageID) {
        int round = iteration + 1;
        List<com.ragagent.llm.domain.ToolCall> calls = response.getToolCalls();
        int n = calls.size();
        log.info("[Agent][Round-{}] Parallel execution of {} tool calls", round, n);

        ToolCall[] results = new ToolCall[n];
        TenantContextSnapshot tenant = TenantContextSnapshot.capture();
        int i = 0;
        while (i < n) {
            if (!ExecutionPolicy.canRunConcurrently(calls.get(i).getFunction().getName())) {
                // 突变是屏障：先等之前的读全部落地，突变完成后再开后面的读。
                results[i] = runToolCall(calls.get(i), i, iteration, round, sessionId,
                        assistantMessageID, tenant);
                i++;
                continue;
            }
            int batchStart = i;
            while (i < n && ExecutionPolicy.canRunConcurrently(calls.get(i).getFunction().getName())) {
                i++;
            }
            int batchSize = i - batchStart;
            Semaphore permits = new Semaphore(Math.min(8, batchSize));
            List<Thread> threads = new ArrayList<>(batchSize);
            for (int k = batchStart; k < i; k++) {
                final int idx = k;
                Thread t = Thread.ofVirtual().unstarted(() -> {
                    tenant.replay();
                    try {
                        permits.acquire();
                        results[idx] = runToolCall(calls.get(idx), idx, iteration, round, sessionId,
                                assistantMessageID, null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("[Agent][Round-{}] tool call interrupted", round);
                        results[idx] = crashedToolCall(calls.get(idx), "tool call interrupted");
                        // 结果槽不允许留 null：留空会让收集循环 emitToolOutcome(null) NPE
                    } catch (Throwable fatal) {
                        // 外围（modelContext 解码/langfuse span/eventBus emit）抛错会让
                        // 线程死亡、results[idx] 保持 null，收集循环直接 NPE 炸掉整轮
                        // （Go 的 errgroup 收集首错后仍产出结果行）。兜底落失败结果。
                        log.warn("[Agent][Round-{}] tool call crashed: {}", round, fatal.toString());
                        results[idx] = crashedToolCall(calls.get(idx),
                                com.ragagent.common.error.BizException.wireText(fatal));
                    } finally {
                        permits.release();
                        TenantContext.clear();
                    }
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        for (ToolCall toolCall : results) {
            step.getToolCalls().add(toolCall);
            emitToolOutcome(toolCall, iteration, sessionId);
        }
    }

    /** 崩溃/中断的工具调用的失败占位结果（保证结果槽非 null，对照 emitToolOutcome 的兜底形态）。 */
    private static ToolCall crashedToolCall(com.ragagent.llm.domain.ToolCall tc, String error) {
        ToolCall crashed = new ToolCall();
        crashed.setId(tc.getId());
        crashed.setName(tc.getToolName());
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        crashed.setResult(r);
        return crashed;
    }

    /** 一个完成工具调用的结果/动作事件（对照 emitToolOutcome；所有路径共用）。 */
    private void emitToolOutcome(ToolCall toolCall, int iteration, String sessionId) {
        ToolResult result = toolCall.getResult();
        if (result == null) {
            result = new ToolResult();
            result.setSuccess(false);
            result.setError("no result");
        }

        eventBus.emit(new Event(toolCall.getId() + "-tool-result",
                EventType.EVENT_AGENT_TOOL_RESULT, sessionId,
                new AgentToolResultData(toolCall.getId(), toolCall.getExecutionName(),
                        result.getOutput(), result.getError(), result.isSuccess(),
                        toolCall.getDuration(), iteration,
                        deepSortedGoMap(sanitizeToolDataForPersist(toolCall.getName(),
                                result.getData()))),
                null, ""));

        eventBus.emit(new Event(toolCall.getId() + "-tool-exec",
                EventType.EVENT_AGENT_TOOL, sessionId,
                new AgentActionData(iteration, toolCall.getExecutionName(),
                        deepSortedGoMap(toolCall.getExecutionArgs()), result.getOutput(),
                        result.isSuccess(), result.getError(), toolCall.getDuration()),
                null, ""));
    }

    private void executeSingleToolCall(com.ragagent.llm.domain.ToolCall tc, int i, AgentStep step,
            int iteration, int round, String sessionId, String assistantMessageID) {
        ToolCall toolCall = runToolCall(tc, i, iteration, round, sessionId, assistantMessageID, null);
        step.getToolCalls().add(toolCall);
        emitToolOutcome(toolCall, iteration, sessionId);
    }

    /**
     * 单个工具调用：参数解析、执行、日志（对照 runToolCall）。可从多线程调用；
     * tenant 为 null 时用当前线程上下文（顺序路径）。
     */
    ToolCall runToolCall(com.ragagent.llm.domain.ToolCall tc, int i, int iteration, int round,
            String sessionId, String assistantMessageID, TenantContextSnapshot tenant) {
        if (tenant == null) {
            // 顺序路径：直接用当前线程上下文（引擎线程）
            return runToolCallInner(tc, i, iteration, round, sessionId, assistantMessageID);
        }
        // 借用快照执行：可并发批次的子线程传 null（子线程自行 replay/clear），
        // 突变屏障分支在**主线程**以非 null 快照调用——必须保存-恢复而非 clear，
        // 否则引擎线程的租户/身份会被清掉，后续轮次的模型/KB 解析全部失败。
        TenantContextSnapshot prev = TenantContextSnapshot.capture();
        tenant.replay();
        try {
            return runToolCallInner(tc, i, iteration, round, sessionId, assistantMessageID);
        } finally {
            prev.replay();
        }
    }

    private ToolCall runToolCallInner(com.ragagent.llm.domain.ToolCall tc, int i, int iteration,
            int round, String sessionId, String assistantMessageID) {
        log.info("[Agent][Round-{}][Tool {}] tenantId={}", round, tc.getFunction().getName(),
                com.ragagent.common.context.TenantContext.currentTenantId());
        tc.setId(NormalizeToolCallId.normalize(tc.getId(), tc.getFunction().getName(), i));
        String total = "?"; // 孤立时未知；调用方记批量大小
        String toolTag = String.format("[Agent][Round-%d][Tool %s (%d/%s)]",
                round, tc.getFunction().getName(), i + 1, total);

        Map<String, Object> args = null;
        String argsStr = tc.getFunction().getArguments();
        RuntimeException argsError = null;
        try {
            args = parseArgsMap(argsStr);
        } catch (Exception e) {
            argsError = e instanceof RuntimeException re ? re : new RuntimeException(e.getMessage(), e);
        }
        if (argsError != null) {
            JsonRepair.RepairResult repaired = JsonRepair.repairJsonDetail(argsStr);
            boolean repairOk = false;
            try {
                args = parseArgsMap(repaired.repaired());
                repairOk = true;
            } catch (Exception ignored) {
                // fall through：解析仍失败
            }
            if (!repairOk) {
                log.error("{} Failed to parse arguments (repair failed): {}", toolTag, argsError.getMessage());
                ToolCall c = new ToolCall();
                c.setId(tc.getId());
                c.setName(tc.getFunction().getName());
                Map<String, Object> rawArgs = new LinkedHashMap<>();
                rawArgs.put("_raw", argsStr);
                c.setArgs(rawArgs);
                c.setProviderMetadata(tc.getProviderMetadata());
                ToolResult r = new ToolResult();
                r.setSuccess(false);
                r.setError("Failed to parse tool arguments: " + argsError.getMessage()
                        + "\n\nIf the JSON looks cut off, the previous round likely hit the output token cap. "
                        + "Retry with complete JSON (required fields first) and a smaller payload.\n\n"
                        + "[Analyze the error above and try a different approach.]");
                c.setResult(r);
                return c;
            }
            // 补齐未终止的字符串/括号能让 payload 解析，但值仍是 provider 挤出的残缺值——
            // 执行会写半个文件或搜半个查询还报成功，所以拒绝。这是无 finish reason 断流的保险带。
            if (repaired.truncated()) {
                log.warn("{} Arguments were cut off mid-emission ({} bytes); refusing to execute",
                        toolTag, argsStr == null ? 0 : argsStr.length());
                ToolCall c = new ToolCall();
                c.setId(tc.getId());
                c.setName(tc.getFunction().getName());
                Map<String, Object> rawArgs = new LinkedHashMap<>();
                rawArgs.put("_raw", argsStr);
                c.setArgs(rawArgs);
                c.setProviderMetadata(tc.getProviderMetadata());
                ToolResult r = new ToolResult();
                r.setSuccess(false);
                r.setError(TRUNCATED_ARGUMENTS_ERROR);
                c.setResult(r);
                return c;
            }
            log.warn("{} Repaired malformed JSON arguments", toolTag);
            // 首趟 model-context 扫不动坏 JSON：执行前解码修复后的 payload，
            // 同时保留 tc.ModelArguments 里的原始 provider 载荷。
            com.ragagent.llm.domain.ToolCall decoded = new com.ragagent.llm.domain.ToolCall();
            decoded.setId(tc.getId());
            decoded.setType(tc.getType());
            decoded.setModelArguments("");
            decoded.setFunction(new com.ragagent.llm.domain.FunctionCall(tc.getFunction().getName(),
                    repaired.repaired()));
            decoded.setProviderMetadata(tc.getProviderMetadata());
            modelContext.decodeToolCalls(List.of(decoded));
            tc.getFunction().setArguments(decoded.getFunction().getArguments());
            tc.setArgumentResolution(decoded.getArgumentResolution());
            tc.setUnresolvedHandles(decoded.getUnresolvedHandles());
            try {
                args = parseArgsMap(tc.getFunction().getArguments());
            } catch (Exception e) {
                ToolCall c = new ToolCall();
                c.setId(tc.getId());
                c.setName(tc.getFunction().getName());
                Map<String, Object> rawArgs = new LinkedHashMap<>();
                rawArgs.put("_raw", tc.getFunction().getArguments());
                c.setArgs(rawArgs);
                c.setProviderMetadata(tc.getProviderMetadata());
                ToolResult r = new ToolResult();
                r.setSuccess(false);
                r.setError("Failed to parse repaired tool arguments: " + e.getMessage());
                c.setResult(r);
                return c;
            }
        }

        // provider 可见的代理调用保持完整；为活事件/持久展示/追踪解析独立的目标身份。
        ToolCallTarget target = null;
        if (tc.getUnresolvedHandles() == null || tc.getUnresolvedHandles().isEmpty()) {
            try {
                JsonNode raw = JSON.readTree(argsStr == null ? "null" : argsStr);
                target = toolRegistry.mcpCallTarget(tc.getFunction().getName(), raw);
            } catch (Exception e) {
                target = null;
            }
        }
        String executionName = tc.getFunction().getName();
        Map<String, Object> executionArgs = args;
        if (target != null) {
            executionName = target.getName();
            executionArgs = target.getArgs();
        }

        log.debug("{} Args: {}", toolTag, tc.getFunction().getArguments());

        Instant toolCallStartTime = Instant.now();

        // UI 进度提示事件
        String toolHint = formatToolHint(executionName, executionArgs);
        eventBus.emit(new Event(tc.getId() + "-tool-hint", EventType.EVENT_AGENT_TOOL_CALL, sessionId,
                new AgentToolCallData(tc.getId(), executionName,
                        deepSortedGoMap(SandboxDiffs.sanitizeSandboxFileCallArgs(executionName,
                                executionArgs)),
                        iteration, toolHint), null, ""));

        log.info("[PIPELINE] stage=Agent action=tool_call_start iteration={} round={} tool={} tool_call_id={} tool_index={}/{}",
                iteration, round, executionName, tc.getId(), i + 1, total);

        // 工具执行的 Langfuse span（trace → agent.execute → agent.round.N → agent.tool.<name>）。
        // database_query 的 SQL 被提示层判敏感；langfuse 同策略：原始参数只报键。
        Map<String, Object> toolSpanInput = buildToolSpanInput(tc, executionArgs,
                Boolean.TRUE.equals(TOOL_HINT_SENSITIVE_ARGS.get(executionName)));
        if (target != null) {
            toolSpanInput.put("mcp_service", target.getServiceName());
            toolSpanInput.put("mcp_tool", target.getToolName());
        }
        Object resolutionValue = toolSpanInput.get("argument_resolution");
        String argumentResolution = resolutionValue instanceof String s ? s : "";
        Map<String, Object> toolSpanMeta = new LinkedHashMap<>();
        toolSpanMeta.put("iteration", iteration);
        toolSpanMeta.put("round", round);
        toolSpanMeta.put("tool_index", i + 1);
        toolSpanMeta.put("tool_call_id", tc.getId());
        toolSpanMeta.put("session_id", sessionId);
        toolSpanMeta.put("argument_resolution", argumentResolution);
        toolSpanMeta.put("unresolved_handle_count",
                tc.getUnresolvedHandles() == null ? 0 : tc.getUnresolvedHandles().size());
        Span toolSpan = LangfuseManager.get().startSpan(new LangfuseManager.SpanOptions(
                "agent.tool." + executionName, toolSpanInput, toolSpanMeta));

        Duration execTimeout = AgentConsts.toolExecutionTimeout(tc.getFunction().getName(),
                tc.getFunction().getArguments());
        // ApprovalCtx 语义（不带每工具超时的父取消源）：Java 侧 approvalCancellation=null
        // 回落外层取消源（同 Go nil 分支），等待人工审批的长等待由 4.6d 接线。
        ToolExecContext toolExecCtx = new ToolExecContext(sessionId, assistantMessageID, "",
                tc.getId(), TenantContext.currentPrincipal() == null ? ""
                        : TenantContext.currentPrincipal().id(),
                eventBus, null, execTimeout.toMillis());

        ToolResult result = null;
        RuntimeException execError = null;
        if (tc.getUnresolvedHandles() != null && !tc.getUnresolvedHandles().isEmpty()) {
            // 临时句柄不是应用身份：幻觉/过期的 cN/dN/bN/wN/iN/res:// 令牌不能到
            // 持久层、外部服务或路由判定。
            execError = new AgentEngineException(
                    "tool arguments contain unresolved model handles: " + tc.getUnresolvedHandles());
        } else {
            try {
                JsonNode raw = JSON.readTree(tc.getFunction().getArguments() == null ? "null"
                        : tc.getFunction().getArguments());
                result = toolRegistry.executeTool(this::pollCancellation, toolExecCtx,
                        tc.getFunction().getName(), raw);
            } catch (JsonProcessingException e) {
                execError = new AgentEngineException(e.getMessage());
            } catch (RuntimeException e) {
                execError = e;
            }
        }
        long duration = Duration.between(toolCallStartTime, Instant.now()).toMillis();

        ToolCall toolCall = new ToolCall();
        toolCall.setTarget(target);
        toolCall.setId(tc.getId());
        toolCall.setName(tc.getFunction().getName());
        toolCall.setArgs(args);
        toolCall.setResult(result);
        toolCall.setDuration(duration);
        toolCall.setProviderMetadata(tc.getProviderMetadata());

        if (execError != null) {
            // Go 的 err.Error()：AppError 穿到这里要**带着 `error code: N, error message: ` 前缀**
            // （工具 return nil, err → 上层 err.Error()）。取 getMessage() 会把前缀丢掉，
            // SSE 终止错误帧的 content 就与 Go 不一致（见 known-issues/09 第三节）。
            String execText = com.ragagent.common.error.BizException.wireText(execError);
            log.error("{} Failed in {}ms: {}", toolTag, duration, execText);
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(execText);
            toolCall.setResult(r);
        } else {
            boolean success = toolCall.getResult() != null && toolCall.getResult().isSuccess();
            int outputLen = toolCall.getResult() == null ? 0 : toolCall.getResult().getOutput().length();
            log.info("{} Completed in {}ms: success={}, output={} chars", toolTag, duration, success,
                    outputLen);
        }

        finishToolSpan(toolSpan, toolCall, execError, duration);

        // Pipeline 监控事件（日志）
        boolean toolSuccess = toolCall.getResult() != null && toolCall.getResult().isSuccess();
        String pipelineError = toolCall.getResult() == null ? "" : toolCall.getResult().getError();
        if (execError != null) {
            log.error("[PIPELINE] stage=Agent action=tool_call_result iteration={} round={} tool={} tool_call_id={} duration_ms={} success={} error=\"{}\"",
                    iteration, round, executionName, tc.getId(), duration, toolSuccess, pipelineError);
        } else if (toolSuccess) {
            log.info("[PIPELINE] stage=Agent action=tool_call_result iteration={} round={} tool={} tool_call_id={} duration_ms={} success={}",
                    iteration, round, executionName, tc.getId(), duration, toolSuccess);
        } else {
            log.warn("[PIPELINE] stage=Agent action=tool_call_result iteration={} round={} tool={} tool_call_id={} duration_ms={} success={} error=\"{}\"",
                    iteration, round, executionName, tc.getId(), duration, toolSuccess, pipelineError);
        }

        if (toolCall.getResult() != null && !toolCall.getResult().getOutput().isEmpty()) {
            String preview = toolCall.getResult().getOutput();
            if (preview.length() > 500) {
                preview = preview.substring(0, 500) + "... (truncated)";
            }
            log.debug("{} Output preview:\n{}", toolTag, preview);
        }
        if (toolCall.getResult() != null && !toolCall.getResult().getError().isEmpty()) {
            log.debug("{} Tool error: {}", toolTag, toolCall.getResult().getError());
        }

        return toolCall;
    }

    /** 对照 json.Unmarshal(argsStr, &map[string]any{})；null 输入 → null map。 */
    private static Map<String, Object> parseArgsMap(String argsStr) throws Exception {
        return JSON.readValue(argsStr == null ? "null" : argsStr,
                JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    /** Langfuse 工具 span 收尾（对照 finishToolSpan）。 */
    private static void finishToolSpan(Span span, ToolCall tc, RuntimeException execErr, long durationMs) {
        if (span == null) {
            return;
        }
        boolean success = tc.getResult() != null && tc.getResult().isSuccess();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("success", success);
        output.put("duration_ms", durationMs);
        if (tc.getResult() != null) {
            if (!tc.getResult().getOutput().isEmpty()) {
                output.put("output", truncateRunes(tc.getResult().getOutput(), LANGFUSE_TOOL_OUTPUT_PREVIEW));
                output.put("output_len", tc.getResult().getOutput().length());
            }
            if (!tc.getResult().getError().isEmpty()) {
                output.put("error", tc.getResult().getError());
            }
            if (tc.getResult().getData() != null && !tc.getResult().getData().isEmpty()) {
                // Data 结构化但可以任意大——只报键形状。
                output.put("data_keys", sortedKeys(tc.getResult().getData()));
            }
            if (tc.getResult().getImages() != null && !tc.getResult().getImages().isEmpty()) {
                output.put("image_count", tc.getResult().getImages().size());
            }
        }
        // span 结果分类：execErr 恒错误；Success=false 的结果也当错误（LLM 会换个思路重试）。
        String spanErr = null;
        if (execErr != null) {
            spanErr = execErr.getMessage();
        } else if (tc.getResult() != null && !tc.getResult().isSuccess()) {
            String msg = tc.getResult().getError();
            if (msg == null || msg.isEmpty()) {
                msg = "tool returned success=false";
            }
            spanErr = msg;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("success", success);
        meta.put("duration_ms", durationMs);
        span.finish(output, meta, spanErr);
    }

    /** Go map 键序（UTF-8 字节序）。 */
    static final Comparator<String> GO_KEY_ORDER = (a, b) -> {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(x.length, y.length);
        for (int idx = 0; idx < n; idx++) {
            if (x[idx] != y[idx]) {
                return Integer.compare(x[idx] & 0xFF, y[idx] & 0xFF);
            }
        }
        return Integer.compare(x.length, y.length);
    };

    private static List<String> sortedKeys(Map<String, Object> data) {
        List<String> keys = new ArrayList<>(data.keySet());
        keys.sort(GO_KEY_ORDER);
        return keys;
    }

    /** 递归按 Go map 键序排序（事件 payload 的 map 契约）；null 原样返回。 */
    static Map<String, Object> deepSortedGoMap(Map<String, Object> in) {
        if (in == null) {
            return null;
        }
        Map<String, Object> out = new TreeMap<>(GO_KEY_ORDER);
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), deepSortValue(e.getValue()));
        }
        return out;
    }

    private static Object deepSortValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>(GO_KEY_ORDER);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), deepSortValue(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(deepSortValue(item));
            }
            return out;
        }
        return v;
    }

    /** Langfuse 入参双面（对照 buildToolSpanInput：model_arguments + resolved_arguments）。 */
    private static Map<String, Object> buildToolSpanInput(com.ragagent.llm.domain.ToolCall tc,
            Map<String, Object> resolvedArgs, boolean sensitive) {
        String modelArguments = tc.getModelArguments();
        if (modelArguments == null || modelArguments.isEmpty()) {
            modelArguments = tc.getFunction().getArguments();
        }
        String resolution = tc.getArgumentResolution();
        if (resolution == null || resolution.isEmpty()) {
            resolution = Registry.ARGUMENT_RESOLUTION_UNCHANGED;
        }
        if (sensitive) {
            List<String> modelArgKeys = null;
            Object parsed = traceArgumentValue(modelArguments);
            if (parsed instanceof Map<?, ?> m) {
                modelArgKeys = new ArrayList<>();
                for (Object k : m.keySet()) {
                    modelArgKeys.add(String.valueOf(k));
                }
                modelArgKeys.sort(GO_KEY_ORDER);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool_call_id", tc.getId());
            out.put("model_arg_keys", modelArgKeys);
            out.put("resolved_arg_keys", resolvedArgs == null ? List.of() : sortedKeys(resolvedArgs));
            out.put("argument_resolution", resolution);
            out.put("unresolved_handle_count",
                    tc.getUnresolvedHandles() == null ? 0 : tc.getUnresolvedHandles().size());
            out.put("args_redacted", true);
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool_call_id", tc.getId());
        out.put("model_arguments", traceArgumentValue(modelArguments));
        out.put("resolved_arguments", deepSortedGoMap(resolvedArgs));
        out.put("argument_resolution", resolution);
        out.put("unresolved_handles", tc.getUnresolvedHandles());
        return out;
    }

    /** 合法 JSON 保结构、坏载荷原样保留（对照 traceArgumentValue）。 */
    private static Object traceArgumentValue(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * persistStripFields / persistStripFieldsByTool 的引擎侧桥（对照 tools/persist.go 的
     * SanitizeToolDataForPersist——本波唯一消费点是 emitToolOutcome；SSE 回放/DB 存储
     * 的其余 persist 函数随 4.6d 落到 tools 包）。
     */
    private static final Map<String, List<String>> PERSIST_STRIP_FIELDS_BY_TOOL = buildPersistStripByTool();

    private static Map<String, List<String>> buildPersistStripByTool() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(ToolDefinitions.TOOL_READ_FILE, List.of("content", "content_base64", "instructions"));
        m.put(ToolDefinitions.TOOL_SHELL_EXEC, List.of("content", "content_base64"));
        m.put(ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE, List.of("content", "content_base64"));
        m.put(ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, List.of("content", "content_base64"));
        m.put(ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, List.of("content", "content_base64"));
        return m;
    }

    /** display_type 携带的批量字段剥离表（对照 persistStripFields）。 */
    private static final Map<String, List<String>> PERSIST_STRIP_FIELDS = Map.of(
            "knowledge_chunks_list", List.of("chunks"),
            "grep_results", List.of("chunk_results"));

    /** 返回一份 DB / SSE 回放安全的 Data 副本（对照 sanitizeToolData）。 */
    static Map<String, Object> sanitizeToolDataForPersist(String toolName, Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(data);
        Object displayTypeValue = data.get("display_type");
        String displayType = displayTypeValue instanceof String s ? s.trim() : "";
        List<String> extraOmit = PERSIST_STRIP_FIELDS_BY_TOOL.get(toolName);
        if (extraOmit != null) {
            for (String key : extraOmit) {
                out.remove(key);
            }
        }
        List<String> byDisplay = PERSIST_STRIP_FIELDS.get(displayType);
        if (byDisplay != null) {
            for (String key : byDisplay) {
                out.remove(key);
            }
        }
        return out;
    }

    // ==================================================================
    // observe.go：上下文窗口管理与响应分析
    // ==================================================================

    private static final int MIN_TOOL_RESULT_TOKENS = 8 * 1024;
    private static final int MAX_TOOL_RESULT_TOKENS = 32 * 1024;
    private static final int TOOL_RESULT_TOKEN_FRACTION = 5; // 20%
    private static final int MIN_FREED_FRACTION = 20; // 5%

    /**
     * 上下文过阈值时把老对话摘要掉（对照 manageContextWindow）。
     * changed 报告消息是否变了，调用方据此作废自己的 token 估计。
     */
    WindowOutcome manageContextWindow(List<ChatMessage> messages, int round, int currentTokens) {
        CompactionSettings settings = activeCompactionSettings();
        if (!settings.shouldCompact(currentTokens)) {
            return new WindowOutcome(messages, false);
        }
        log.info("[Agent][Round-{}] Context at {} tokens, over the {} threshold (window={}, reserved={}, keep_recent={}); compacting",
                round, currentTokens, settings.threshold(), settings.maxContextTokens(),
                settings.reserveTokens(), settings.keepRecentTokens());

        boolean changed = false;
        CompactionOutcome compacted = runCompaction(messages, round, CompactionReason.THRESHOLD);
        if (compacted.ok()) {
            messages = compacted.messages();
            changed = true;
            currentTokens = tokenEstimator.estimateMessages(messages);
            if (!settings.shouldCompact(currentTokens)) {
                return new WindowOutcome(messages, true);
            }
        }

        // 压缩后仍超预算说明重量在 keep-recent 窗口内（切点够不着）——裁工具结果是
        // lossy 的兜底而非常规步骤。
        WindowOutcome trimmed = trimToolResults(messages, round, settings);
        return new WindowOutcome(trimmed.messages(), changed || trimmed.changed());
    }

    /** runCompaction 的 (messages, ok)。 */
    record CompactionOutcome(List<ChatMessage> messages, boolean ok) {
    }

    /**
     * 执行一次压缩并报告上下文是否真的变小（对照 runCompaction）。
     * false = 本轮再试也无用，调用方不得继续重试：腾不出空间的压缩照样付一整个
     * 摘要往返。
     */
    private CompactionOutcome runCompaction(List<ChatMessage> messages, int round, String reason) {
        if (compactor == null) {
            return new CompactionOutcome(messages, false);
        }
        // 耗尽标记挂在消息数上：一旦循环又追加了新轮次，就有新历史可摘要。
        if (compactionExhaustedAt > 0 && messages.size() <= compactionExhaustedAt) {
            return new CompactionOutcome(messages, false);
        }

        CompactionResult result;
        try {
            result = compactor.compact(messages, reason);
        } catch (NothingToCompactException e) {
            log.info("[Agent][Round-{}] Nothing outside the keep-recent budget; skipping compaction", round);
            compactionExhaustedAt = messages.size();
            return new CompactionOutcome(messages, false);
        } catch (RuntimeException e) {
            log.warn("[Agent][Round-{}] Compaction failed: {}", round, e.getMessage());
            compactionExhaustedAt = messages.size();
            return new CompactionOutcome(messages, false);
        }
        // "腾出了"太弱：240/26700 也算进展，循环会每轮白付一次摘要而上下文原地不动。
        if (result.freed() < result.getTokensBefore() / MIN_FREED_FRACTION) {
            log.warn("[Agent][Round-{}] Compaction freed too little ({} → {} tokens); not attempting again at this size",
                    round, result.getTokensBefore(), result.getTokensAfter());
            compactionExhaustedAt = messages.size();
            return new CompactionOutcome(messages, false);
        }

        log.info("[Agent][Round-{}] Compacted ({}): {} → {} tokens, {} → {} messages (split_turn={}, degraded={})",
                round, result.getReason(), result.getTokensBefore(), result.getTokensAfter(),
                result.getMessagesBefore(), result.getMessagesAfter(), result.isSplitTurn(),
                result.isDegraded());
        log.debug("[Agent][Round-{}][ctx] post-compaction: summary={} tail={} (keep_recent={}) | {}",
                round, tokenEstimator.estimateString(result.getSummary()),
                result.getTokensAfter() - tokenEstimator.estimateString(result.getSummary()),
                compactor.settings().keepRecentTokens(),
                ContextDiagnostics.breakdownContext(result.getMessages(), List.of(), tokenEstimator));
        log.info("[PIPELINE] stage=Agent action=context_compacted round={} reason={} tokens_before={} tokens_after={} degraded={}",
                round, result.getReason(), result.getTokensBefore(), result.getTokensAfter(),
                result.isDegraded());
        emitContextCompacted(result, round);

        // usage 基线描述的是压缩前的上下文；留着会让下一轮对着不存在的历史估、立刻再压。
        lastUsage = new TokenUsage();
        lastSentMsgCount = 0;

        return new CompactionOutcome(result.getMessages(), true);
    }

    private void emitContextCompacted(CompactionResult result, int round) {
        eventBus.emit(new Event(EventIds.generateEventID("compaction"),
                EventType.EVENT_CONTEXT_COMPACTED, sessionId,
                new ContextCompactedData(result.getReason(), round, result.getTokensBefore(),
                        result.getTokensAfter(), result.getMessagesBefore(), result.getMessagesAfter(),
                        result.getSummary(), result.isDegraded(), result.isSplitTurn()),
                null, ""));
    }

    /** 响应是否由满窗口塑形而非我们要求的补全预算（对照 responseHitContextLimit）。 */
    private boolean responseHitContextLimit(ChatResponse response) {
        int window = config == null ? 0 : config.getMaxContextTokens();
        return CompactionOverflow.responseHitContextLimit(response, window, getCompletionTokenBudget());
    }

    /** 不看阈值直接压缩：provider 已说窗口满了，让估计见鬼去吧（对照 forceCompaction）。 */
    private List<ChatMessage> forceCompaction(List<ChatMessage> messages, int round) {
        CompactionOutcome compacted = runCompaction(messages, round, CompactionReason.OVERFLOW);
        if (!compacted.ok()) {
            WindowOutcome trimmed = trimToolResults(messages, round, activeCompactionSettings());
            return trimmed.messages();
        }
        return compacted.messages();
    }

    /** Go 的 nil-receiver Settings()（零值 settings）对应物：无窗口时压缩整体停用。 */
    private CompactionSettings activeCompactionSettings() {
        return compactor == null ? CompactionSettings.ofDefaults() : compactor.settings();
    }

    /** 用预览替换工具输出直到装进窗口的一部分（对照 trimToolResults；最后的兜底）。 */
    private WindowOutcome trimToolResults(List<ChatMessage> messages, int round,
            CompactionSettings settings) {
        TrimOutcome trimmed = trimToolResultsToBudget(messages, tokenEstimator,
                toolResultBudget(settings.maxContextTokens()));
        if (!trimmed.ok()) {
            return new WindowOutcome(messages, false);
        }
        log.info("[Agent][Round-{}] Trimmed tool results to the token budget", round);
        return new WindowOutcome(trimmed.messages(), true);
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
    record TrimOutcome(List<ChatMessage> messages, boolean ok) {
    }

    /**
     * 返回下一次模型调用的消息副本（对照 trimToolResultsToBudget）。
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
            ChatMessage copy = shallowCopy(out.get(idx));
            copy.setContent(compactedToolResultMarker(messages.get(idx).getContent()));
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
            out.set(idx, compactToolMessage(messages.get(idx), baseCosts.get(idx) + remaining, estimator));
            remaining = 0;
        }
        return new TrimOutcome(out, true);
    }

    private static ChatMessage shallowCopy(ChatMessage m) {
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

    /** 单条工具消息压到 maxTokens 内（对照 compactToolMessage：keep 值二分）。 */
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

    /** 响应分析的裁决（对照 responseVerdict）。 */
    static final class ResponseVerdict {
        boolean isDone;
        String finalAnswer = "";
        boolean emptyContent;
        AgentStep step;
        String answerID = "";
    }

    /** 自然停 finish reason（对照 isNaturalStopFinishReason）。 */
    static boolean isNaturalStopFinishReason(String reason) {
        String r = reason == null ? "" : reason.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (r) {
            case "stop", "end_turn", "stop_sequence" -> true;
            default -> false;
        };
    }

    /** 截断 finish reason（对照 isLengthFinishReason）。 */
    static boolean isLengthFinishReason(String reason) {
        String r = reason == null ? "" : reason.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (r) {
            case "length", "max_tokens", "max_output_tokens" -> true;
            default -> false;
        };
    }

    /**
     * 检查 LLM 响应的停止条件（对照 analyzeResponse）：自然停无工具调用 → 完成；
     * content_filter 无工具调用 → 完成（终态，避免同一被拦响应无限累积）。
     */
    private ResponseVerdict analyzeResponse(ChatResponse response, AgentStep step,
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
            eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionID,
                    new AgentFinalAnswerData(answer, false, false), null, ""));
            eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionID,
                    new AgentFinalAnswerData("", true, false), null, ""));

            ResponseVerdict v = new ResponseVerdict();
            v.isDone = true;
            v.finalAnswer = answer;
            v.step = step;
            return v;
        }

        // Case 1: LLM 自然停且没有请求任何工具调用。先剥掉内联 <think> 块。
        if (isNaturalStopFinishReason(response.getFinishReason())
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
                    eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionID,
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

    // ---- observe.go：runtime_context / must_use ----

    static String indentLines(String s, String indent) {
        if (s.isEmpty()) {
            return "";
        }
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            if (!lines[i].isEmpty()) {
                sb.append(indent).append(lines[i]);
            } else {
                sb.append(lines[i]);
            }
        }
        return sb.toString();
    }

    static String escapeXMLAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 当前轮的元数据块：current_time + session + 本轮生效的检索范围
     * （对照 buildRuntimeContextBlock）。注入当前 user 消息、不落历史——回放的用户轮
     * 保持裸 Content，过期的范围快照不会误导追问。
     */
    static String buildRuntimeContextBlock(String sessionId, List<AgentPrompts.KnowledgeBaseInfo> kbs,
            List<AgentPrompts.SelectedDocumentInfo> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<runtime_context scope=\"this_turn\">\n");
        sb.append("  <current_time>").append(LocalDate.now().toString()).append("</current_time>\n");
        sb.append("  <session>").append(escapeXMLAttr(sessionId)).append("</session>\n");

        if (kbs != null && !kbs.isEmpty()) {
            // 完整绑定 KB 详情（能力 + 近期文档），模型在一处完成检索路由。
            sb.append("  <bound_knowledge_bases>\n");
            sb.append(indentLines(AgentPrompts.formatKnowledgeBaseList(kbs), "    "));
            sb.append("\n  </bound_knowledge_bases>\n");
        }

        if (docs != null && !docs.isEmpty()) {
            sb.append("  <pinned_documents scope=\"authoritative_for_this_turn\">\n");
            for (AgentPrompts.SelectedDocumentInfo d : docs) {
                if (d == null) {
                    continue;
                }
                String title = d.title();
                if (title.isEmpty()) {
                    title = d.fileName();
                }
                if (title.isEmpty()) {
                    title = d.knowledgeId();
                }
                if (!d.fileType().isEmpty()) {
                    sb.append("    <document knowledge_id=\"").append(escapeXMLAttr(d.knowledgeId()))
                            .append("\" title=\"").append(escapeXMLAttr(title))
                            .append("\" file_type=\"").append(escapeXMLAttr(d.fileType()))
                            .append("\" />\n");
                } else {
                    sb.append("    <document knowledge_id=\"").append(escapeXMLAttr(d.knowledgeId()))
                            .append("\" title=\"").append(escapeXMLAttr(title)).append("\" />\n");
                }
            }
            sb.append("  </pinned_documents>\n");
        }

        sb.append("</runtime_context>");
        return sb.toString();
    }

    /** @mention 的短提示（对照 buildMustUseBlock；工具名已在 schema 里，此处不列）。 */
    static String buildMustUseBlock(List<AgentPrompts.PinnedMCPServiceInfo> mcpServices,
            List<AgentPrompts.PinnedSkillInfo> skills) {
        List<String> lines = new ArrayList<>();
        if (mcpServices != null) {
            for (AgentPrompts.PinnedMCPServiceInfo svc : mcpServices) {
                if (svc == null) {
                    continue;
                }
                if (svc.discoverable() && svc.toolNames() != null && !svc.toolNames().isEmpty()) {
                    lines.add("Use relevant available MCP functions for service @"
                            + sanitizeMustUseField(svc.name()) + " (server_id=\""
                            + sanitizeMustUseField(svc.id()) + "\") before answering. Their descriptions "
                            + "identify the service and original tool names; use "
                            + "discover_mcp_tools if the service needs reconnection or authentication.");
                    continue;
                }
                if (svc.discoverable()) {
                    lines.add("Use discover_mcp_tools(mode=\"list_tools\", server_id=\""
                            + sanitizeMustUseField(svc.id()) + "\") for the selected MCP service @"
                            + sanitizeMustUseField(svc.name()) + ". Describe the required tools, then use "
                            + "the offered functions or call_mcp_tool as available before answering; report "
                            + "connection or authentication failures if the service is unavailable.");
                    continue;
                }
                String prefix = mcpToolNamePrefix(svc);
                if (prefix.isEmpty()) {
                    continue;
                }
                String display = sanitizeMustUseField(svc.name());
                if (display.isEmpty()) {
                    display = sanitizeMustUseField(svc.id());
                }
                lines.add("Must use MCP tools whose names start with " + prefix + " (@" + display
                        + ") to answer the question below.");
            }
        }
        if (skills != null) {
            for (AgentPrompts.PinnedSkillInfo skill : skills) {
                if (skill == null || skill.name().isEmpty()) {
                    continue;
                }
                String name = sanitizeMustUseField(skill.name());
                lines.add("Must call read_file(path=\"skill://" + name + "/SKILL.md\") for @Skill \""
                        + name + "\" before answering.");
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "<must_use>\n" + String.join("\n", lines)
                + "\nThese selections do not replace research into the task's factual content or exclude other "
                + "relevant available sources unless the user explicitly restricts them. Apply selections to the "
                + "relevant parts of the task; an @mention does not authorize unrelated actions. Follow the "
                + "user's current explicit restrictions if they narrow or cancel a selection.\n</must_use>";
    }

    /** 去换行与尖括号，名字越不出 must_use 块（对照 sanitizeMustUseField）。 */
    static String sanitizeMustUseField(String s) {
        return s.replace("\n", " ").replace("\r", " ").replace("<", " ").replace(">", " ").trim();
    }

    /**
     * MCP 服务注册工具的公共前缀（对照 mcpToolNamePrefix）：工具名是
     * mcp_{service}_{tool}，service slug 自己可能带下划线——取最长公共前缀再缩回最后
     * 一个段边界，而不是在第一个下划线处傻切。
     */
    static String mcpToolNamePrefix(AgentPrompts.PinnedMCPServiceInfo svc) {
        if (svc == null || svc.toolNames() == null || svc.toolNames().isEmpty()) {
            return "";
        }
        String head = "mcp_";
        List<String> mcpNames = new ArrayList<>();
        for (String toolName : svc.toolNames()) {
            if (toolName.startsWith(head)) {
                mcpNames.add(toolName);
            }
        }
        if (mcpNames.isEmpty()) {
            return "";
        }
        String prefix = mcpNames.get(0);
        for (String name : mcpNames.subList(1, mcpNames.size())) {
            prefix = commonStringPrefix(prefix, name);
        }
        int idx = prefix.lastIndexOf('_');
        if (idx >= head.length() - 1) {
            prefix = prefix.substring(0, idx + 1);
        }
        if (prefix.length() <= head.length()) {
            return "";
        }
        return prefix;
    }

    static String commonStringPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    /**
     * 当前 LLM 调用的 user-turn 载荷（对照 RenderUserTurnContent，导出）。
     * 只被 Execute 与 finalize 路径使用；不进 rendered_content / 历史。
     */
    public String renderUserTurnContent(String sessionId, String query) {
        registerRuntimeReferences();
        String runtimeCtx = buildRuntimeContextBlock(sessionId, knowledgeBasesInfo, selectedDocs);
        runtimeCtx = modelContext.compactKnownText(runtimeCtx);
        String mustUse = buildMustUseBlock(pinnedMCPServices, pinnedSkills);
        return composeUserTurnContent(List.of(runtimeCtx, mustUse, query));
    }

    /** 绑定 KB / 钉住文档 / 近期 chunk 注册成请求内句柄（对照 registerRuntimeReferences）。 */
    private void registerRuntimeReferences() {
        if (knowledgeBasesInfo != null) {
            for (AgentPrompts.KnowledgeBaseInfo kb : knowledgeBasesInfo) {
                if (kb == null) {
                    continue;
                }
                modelContext.registerKnowledgeBase(kb.id());
                if (kb.recentDocs() == null) {
                    continue;
                }
                for (AgentPrompts.RecentDocInfo doc : kb.recentDocs()) {
                    modelContext.registerDocument(doc.knowledgeId());
                    if (doc.chunkId() != null && !doc.chunkId().isEmpty()) {
                        String title = doc.title();
                        if (title == null || title.isEmpty()) {
                            title = doc.fileName();
                        }
                        modelContext.registerContextChunk(doc.chunkId(), doc.knowledgeId(),
                                firstNonEmptyAgent(doc.knowledgeBaseId(), kb.id()), title, 0, doc.type());
                    }
                }
            }
        }
        if (selectedDocs != null) {
            for (AgentPrompts.SelectedDocumentInfo doc : selectedDocs) {
                if (doc == null) {
                    continue;
                }
                modelContext.registerDocument(doc.knowledgeId());
                modelContext.registerKnowledgeBase(doc.knowledgeBaseId());
            }
        }
    }

    static String firstNonEmptyAgent(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    static String composeUserTurnContent(List<String> parts) {
        List<String> nonEmpty = new ArrayList<>(parts.size());
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                nonEmpty.add(part);
            }
        }
        return String.join("\n\n", nonEmpty);
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

    /** LLM 函数调用用的工具列表（对照 buildToolsForLLM）。 */
    List<ChatTool> buildToolsForLLM() {
        List<FunctionDef> functionDefs = toolRegistry.getModelFunctionDefinitions();
        List<ChatTool> tools = new ArrayList<>(functionDefs.size());
        for (FunctionDef def : functionDefs) {
            tools.add(new ChatTool(def.getName(), def.getDescription(), def.getParameters()));
        }
        return modelContext.encodeTools(tools);
    }

    /**
     * 工具结果进轮内消息历史（对照 appendToolResults，OpenAI tool-calling 格式）。
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
                String resultContent = modelContext.modelToolResultForTool(toolCall.getName(),
                        toolCall.getResult());
                messages.add(ChatMessage.tool(toolCall.getId(), toolCall.getName(), resultContent));
            }
        }
        return messages;
    }

    /** Go json.Marshal(map[string]any) 的字节形态（键序 + HTML 转义 + float 语义）。 */
    private static String goMarshal(Map<String, Object> args) {
        if (args == null) {
            return "null";
        }
        return com.ragagent.agent.tools.GoJsonCodec.write(JSON.valueToTree(deepSortedGoMap(args)));
    }

    /** 工具图片随结果消息走（对照 appendToolImages；VLM 描述在 describeImages）。 */
    private List<ChatMessage> appendToolImages(List<ChatMessage> messages, AgentStep step) {
        return ToolImages.appendToolImages(messages, step,
                config != null && config.isChatModelSupportsVision(),
                images -> {
                    if (imageDescriber == null) {
                        return null;
                    }
                    List<String> raw = new ArrayList<>();
                    for (String uri : images) {
                        byte[] bytes;
                        try {
                            bytes = decodeDataURIBytes(uri);
                        } catch (Exception e) {
                            log.warn("[Agent] Failed to decode tool result image: {}", e.getMessage());
                            continue;
                        }
                        try {
                            raw.add(imageDescriber.describe(bytes, TOOL_IMAGE_ANALYSIS_PROMPT));
                        } catch (Exception e) {
                            log.warn("[Agent] VLM analysis failed for tool result image: {}", e.getMessage());
                        }
                    }
                    return raw;
                });
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

    /** 结果含 KB 内容、跨轮会过期的工具（对照 kbToolNames）。 */
    private static final Map<String, Boolean> KB_TOOL_NAMES = buildKbToolNames();

    private static Map<String, Boolean> buildKbToolNames() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put(ToolDefinitions.TOOL_KNOWLEDGE_SEARCH, true);
        m.put(ToolDefinitions.TOOL_GREP_CHUNKS, true);
        m.put(ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS, true);
        m.put(ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH, true);
        m.put(ToolDefinitions.TOOL_GET_DOCUMENT_INFO, true);
        m.put(ToolDefinitions.TOOL_WIKI_SEARCH, true);
        m.put(ToolDefinitions.TOOL_WIKI_READ_PAGE, true);
        m.put(ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC, true);
        return m;
    }

    /** 历史 KB 工具结果替换成短标记，防 LLM 复用过期检索数据（对照 redactHistoryKBResults）。 */
    static List<ChatMessage> redactHistoryKBResults(List<ChatMessage> llmContext) {
        List<ChatMessage> redacted = new ArrayList<>(llmContext.size());
        for (ChatMessage msg : llmContext) {
            if ("tool".equals(msg.getRole()) && Boolean.TRUE.equals(KB_TOOL_NAMES.get(msg.getName()))) {
                redacted.add(ChatMessage.tool(msg.getToolCallId(), msg.getName(),
                        "[Previous retrieval result omitted — knowledge base may have changed. Please perform a fresh search.]"));
            } else {
                redacted.add(msg);
            }
        }
        return redacted;
    }

    /** 消息数组 + LLM 上下文（对照 buildMessagesWithLLMContext）。 */
    private List<ChatMessage> buildMessagesWithLLMContext(String systemPrompt, String currentQuery,
            String sessionId, List<ChatMessage> llmContext, List<String> imageURLs) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));

        if (llmContext != null && !llmContext.isEmpty()) {
            List<ChatMessage> sanitized;
            if (config.isRetainRetrievalHistory()) {
                sanitized = llmContext;
                log.info("Retaining full retrieval history in context (RetainRetrievalHistory=true)");
            } else {
                // KB 被改过/切过时防止 LLM 复用过期检索数据。
                sanitized = redactHistoryKBResults(llmContext);
                log.info("Added {} history messages to context (KB tool results redacted)", llmContext.size());
            }
            for (ChatMessage msg : sanitized) {
                if ("system".equals(msg.getRole())) {
                    continue;
                }
                if ("user".equals(msg.getRole()) || "assistant".equals(msg.getRole())
                        || "tool".equals(msg.getRole())) {
                    messages.add(msg);
                }
            }
        }

        // 当前用户消息走 finalize 同款注册路径——直接调 buildRuntimeContextBlock 会把
        // 持久 KB/文档 ID 塞进首个请求，而请求内 source registry 还没见过它们。
        ChatMessage userMsg = new ChatMessage("user", renderUserTurnContent(sessionId, currentQuery));
        userMsg.setImages(imageURLs);
        messages.add(userMsg);

        return messages;
    }

    // ==================================================================
    // finalize.go：最终答案合成与完成事件
    // ==================================================================

    /** 最终答案合成流（对照 streamFinalAnswerToEventBus）；失败抛 AgentEngineException。 */
    void streamFinalAnswerToEventBus(String query, AgentState state, String sessionId,
            List<ChatMessage> conversation) {
        int totalToolCalls = countTotalToolCalls(state.getRoundSteps());
        log.info("[Agent][FinalAnswer] Synthesizing from {} steps, {} tool calls",
                state.getRoundSteps().size(), totalToolCalls);
        log.info("[PIPELINE] stage=Agent action=final_answer_start session_id=\"{}\" query=\"{}\" steps={} tool_results={}",
                sessionId, query, state.getRoundSteps().size(), totalToolCalls);

        // 复用活 transcript（含历史/图片/压缩/steer 消息）。工具输出保持 role 与 call ID，
        // 错误恢复/轮次上限合成时绝不把它提成 user 指令。
        List<ChatMessage> messages = new ArrayList<>(conversation);
        messages.add(new ChatMessage("user",
                "Tool execution has ended for this run. Respond to the current task, including "
                        + "the latest user corrections and source restrictions in the conversation. Base claims on "
                        + "the evidence actually obtained; distinguish completed work from remaining work and explain "
                        + "any missing evidence. Use the user's requested language and format. Do not claim that an "
                        + "unperformed action succeeded."));

        // 整个最终答案流共用一个 ID
        String answerID = EventIds.generateEventID("answer");
        log.debug("[Agent][FinalAnswer] AnswerID: {}", answerID);
        boolean[] answerDoneEmitted = {false};

        int budget = clampCompletionBudgetToContext(tokenEstimator.estimateMessages(messages));
        ChatOptions opts = new ChatOptions();
        opts.setTemperature(config.getTemperature());
        opts.setMaxCompletionTokens(budget);
        opts.setPromptCacheKey(sessionId);
        opts.setToolChoice("none");

        StreamLLMResult llmResult = streamLLMToEventBus(messages, opts, (chunk, fullContent) -> {
            // 防御过滤：只发答案内容，跳过思考分片。
            if (chunk.getResponseType() == ResponseType.THINKING) {
                return;
            }
            if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                log.debug("[Agent][FinalAnswer] Emitting answer chunk: {} chars", chunk.getContent().length());
                eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionId,
                        new AgentFinalAnswerData(chunk.getContent(), chunk.isDone(), false), null, ""));
                if (chunk.isDone()) {
                    answerDoneEmitted[0] = true;
                }
            }
        });

        if (!answerDoneEmitted[0]) {
            eventBus.emit(new Event(answerID, EventType.EVENT_AGENT_FINAL_ANSWER, sessionId,
                    new AgentFinalAnswerData("", true, false), null, ""));
        }

        // 合成调用常常是本轮最大的一笔——usage 并进轮累计（与每个 ReAct 轮一致）。
        if (llmResult.usage != null) {
            state.getTurnUsage().accumulate(llmResult.usage);
        }

        // 安全网：剥掉漏进来的残余 <think> 块。
        String fullAnswer = ThinkBlocks.stripThinkBlocks(llmResult.content);
        log.info("[Agent][FinalAnswer] Final answer generated: {} characters", fullAnswer.length());
        log.info("[PIPELINE] stage=Agent action=final_answer_done session_id=\"{}\" answer_len={}",
                sessionId, fullAnswer.length());
        state.setFinalAnswer(fullAnswer);
    }

    /** 轮次耗尽且无自然停时合成最终答案（对照 handleMaxIterations），置 IsComplete。 */
    private void handleMaxIterations(String query, AgentState state, String sessionId,
            List<ChatMessage> messages) {
        log.info("Reached max iterations, generating final answer");
        log.warn("[PIPELINE] stage=Agent action=max_iterations_reached iterations={} max={}",
                state.getCurrentRound(), config.getMaxIterations());

        try {
            streamFinalAnswerToEventBus(query, state, sessionId, messages);
        } catch (RuntimeException e) {
            log.error("Failed to synthesize final answer: {}", e.getMessage());
            log.error("[PIPELINE] stage=Agent action=final_answer_failed error=\"{}\"", e.getMessage());
            state.setFinalAnswer("Sorry, I was unable to generate a complete answer.");
        }
        state.setComplete(true);
    }

    /** 完成事件（对照 emitCompletionEvent；由 executeLoop 的 finally 保证恰好一次）。 */
    private void emitCompletionEvent(AgentState state, String sessionId, String messageId,
            Instant startTime) {
        List<AgentStep> steps = state.getRoundSteps();
        if (state.getPendingSteerMessages() != null && !state.getPendingSteerMessages().isEmpty()) {
            // 停止/模型失败可能发生在投递后、下一响应前；保住边界，不虚构答案。
            steps = new ArrayList<>(state.getRoundSteps());
            AgentStep extra = new AgentStep();
            extra.setIteration(state.getCurrentRound());
            extra.setUserMessagesBefore(new ArrayList<>(state.getPendingSteerMessages()));
            steps.add(extra);
        }
        List<Object> knowledgeRefsInterface = new ArrayList<>(
                state.getKnowledgeRefs() == null ? List.of() : state.getKnowledgeRefs());

        // Go 的 emitCompletionEvent 不设 AgentCompleteData.SessionID（恒 ""）——照抄。
        eventBus.emit(new Event(EventIds.generateEventID("complete"), EventType.EVENT_AGENT_COMPLETE,
                sessionId, new AgentCompleteData("", state.getRoundSteps().size(),
                        state.getFinalAnswer(), knowledgeRefsInterface, goSliceAlwaysPresent(steps),
                        turnUsageOf(state),
                        Duration.between(startTime, Instant.now()).toMillis(), messageId, "", null),
                null, ""));

        log.info("Agent execution completed in {} rounds", state.getCurrentRound());
    }

    /**
     * 轮累计用量；无轮上报用量时返回 {@link NullNode}——Go 的 turnUsage 返回 nil 指针
     * 装进 interface{} 是<b>typed-nil</b>，omitempty 不省略（{@code "usage":null} 恒在）。
     */
    private static Object turnUsageOf(AgentState state) {
        if (state == null || state.getTurnUsage().getTotalTokens() == 0) {
            return NullNode.instance;
        }
        return state.getTurnUsage();
    }

    /**
     * Go interface{} 字段持切片的 omitempty 语义对应物：interface 非 nil 即<b>恒输出</b>
     * （空切片输出 {@code []}；对比：声明为切片类型的字段 len 0 才省略）。Go 的
     * AgentCompleteData.AgentSteps 是 interface{}——{@code "agent_steps":[]} 恒在
     * （实录钉住）。event 包不可改：空列表用 {@link RawValue} 原文过 NON_EMPTY
     * （非空列表走 List 序列化器，形状一致）。
     */
    private static Object goSliceAlwaysPresent(List<?> value) {
        return value.isEmpty()
                ? new com.fasterxml.jackson.databind.util.RawValue("[]")
                : value;
    }

    // ==================================================================
    // steer.go：引擎侧 steer 消费
    // ==================================================================

    /** 换行归一 + trim，与 chat pipeline 对 user query 的处理一致（对照 sanitizeSteerContent）。 */
    static String sanitizeSteerContent(String content) {
        return content.replace("\r\n", "\n").replace("\r", "\n").trim();
    }

    /**
     * 轮边界 drain steer（对照 drainSteerMessages）：压缩后、下一次 LLM 调用前，注入文本
     * 落在保护尾内、计入 lastSentMsgCount 的增量、下一次调用立即可见。
     * 持久化先于追加：失败留下事件待下次重试——先追加会让模型看到历史没记录的文本。
     *
     * @return 注入条数
     */
    int drainSteerMessages(AgentState state, MsgRef messagesRef, String sessionId, String messageID) {
        if (steerSink == null) {
            return 0;
        }
        List<Map<String, Object>> events;
        try {
            events = steerSink.pollSteer(sessionId, messageID, 0);
        } catch (RuntimeException e) {
            log.warn("[Agent] Steer poll failed at round {}: {}", state.getCurrentRound() + 1,
                    e.getMessage());
            return 0;
        }
        if (events == null || events.isEmpty()) {
            return 0;
        }

        int injected = 0;
        for (Map<String, Object> evt : events) {
            String content = sanitizeSteerContent(mapString(evt, "content"));
            if (content.isEmpty()) {
                continue;
            }
            String steerID = mapString(evt, "id");
            String userMessageID = steerSink.persistSteerMessage(sessionId, messageID, steerID,
                    content, evt.get("mentioned_items"), mapString(evt, "channel"));
            if (userMessageID == null || userMessageID.isEmpty()) {
                log.warn("[Agent] Steer persist failed for {}, leaving event pending", steerID);
                continue;
            }
            messagesRef.items.add(new ChatMessage("user", steerMessageContent(content)));
            if (state.getPendingSteerMessages() == null) {
                state.setPendingSteerMessages(new ArrayList<>());
            }
            state.getPendingSteerMessages().add(userMessageID);
            eventBus.emit(new Event(EventIds.generateEventID("injected"),
                    EventType.EVENT_USER_MESSAGE_INJECTED, sessionId,
                    new UserMessageInjectedData(steerID, content, messageID, userMessageID), null, ""));
            injected++;
        }
        if (injected > 0) {
            log.info("[Agent][Round-{}] Injected {} steered user message(s) into the turn",
                    state.getCurrentRound() + 1, injected);
        }
        return injected;
    }

    /** 只给模型输入加投递上下文；持久化行与 UI 保留原文（对照 types.SteerMessageContent）。 */
    static String steerMessageContent(String content) {
        return "<steer_message>\n" + content + "\n</steer_message>\n<continue_task>\n"
                + "This is guidance for the task in progress. Apply it and continue unfinished work "
                + "unless the user explicitly changes or cancels the task.\n</continue_task>";
    }

    /** JSON-decoded map 读字符串（对照 types.MapString）。 */
    static String mapString(Map<String, Object> m, String key) {
        if (m == null) {
            return "";
        }
        Object v = m.get(key);
        return v instanceof String s ? s : "";
    }

    // ==================================================================
    // context_debug.go：引擎段
    // ==================================================================

    /** 本轮请求的预计成本按来源分解 + 阈值对照（对照 logContextPrediction）。 */
    private void logContextPrediction(int round, List<ChatMessage> messages, List<ChatTool> tools,
            int predicted) {
        CompactionSettings settings = activeCompactionSettings();
        ContextDiagnostics.ContextBreakdown b = ContextDiagnostics.breakdownContext(messages, tools,
                tokenEstimator);

        int messagesEst = b.getTotal() - b.getToolSchemas();
        log.debug("[Agent][Round-{}][ctx] predicted={} (baseline_usage={} + delta) | messages={} tool_schemas={} request={} | threshold={} window={} keep_recent={}",
                round, predicted, contextTokensFromUsage(lastUsage), messagesEst, b.getToolSchemas(),
                b.getTotal(), settings.threshold(), settings.maxContextTokens(),
                settings.keepRecentTokens());
        log.debug("[Agent][Round-{}][ctx] breakdown: {}", round, b);

        // 压缩的预估是 messages-only（或 usage+delta）口径；独立校验用同一口径：
        // 有 usage 基线时 provider 已把工具计费，对比全请求；没有就只对比消息。
        int baseline = contextTokensFromUsage(lastUsage);
        int compare = messagesEst;
        if (baseline > 0) {
            compare = b.getTotal();
        }
        if (predicted > 0 && compare > 0) {
            double ratio = (double) predicted / (double) compare;
            if (ratio > 1.5 || ratio < 0.67) {
                log.warn("[Agent][Round-{}][ctx] usage baseline and direct estimate disagree by {}x (predicted={}, estimated={}) — one of them is missing content the other counts",
                        round, String.format(java.util.Locale.ROOT, "%.1f", ratio), predicted, compare);
            }
        }
    }

    /** 预测 vs provider 实际计费（对照 logContextDrift——估计器完整性的 ground truth）。 */
    private void logContextDrift(int round, int predicted, TokenUsage usage) {
        int actual = usage.getPromptTokens();
        if (actual <= 0 || predicted <= 0) {
            return;
        }
        int drift = predicted - actual;
        double pct = (double) drift / (double) actual * 100;

        log.debug("[Agent][Round-{}][ctx] drift: predicted={} actual_prompt={} diff={} ({}%) completion={}",
                round, predicted, actual, String.format(java.util.Locale.ROOT, "%+d", drift),
                String.format(java.util.Locale.ROOT, "%+.1f", pct), usage.getCompletionTokens());

        // 低估是危险方向：会发出被 provider 以体积拒绝的请求。
        if (pct < -25) {
            log.warn("[Agent][Round-{}][ctx] estimate is {}% below the provider's count (predicted={} actual={}) — compaction will fire late and cut too little",
                    round, String.format(java.util.Locale.ROOT, "%.0f", -pct), predicted, actual);
        } else if (pct > 50) {
            log.warn("[Agent][Round-{}][ctx] estimate is {}% above the provider's count (predicted={} actual={}) — compaction will fire on a context that fits",
                    round, String.format(java.util.Locale.ROOT, "%.0f", pct), predicted, actual);
        }
    }
}
