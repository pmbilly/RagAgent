package com.ragagent.agent;

/**
 * agent 轮次补全预算（对照 Go internal/types/agent.go L19-99 的预算族；
 * const.go 的 getCompletionTokenBudget 调用它们）。纯函数，波 4.6 引擎装配时复用。
 */
public final class AgentBudgets {

    /** 对照 DefaultMaxContextTokens。 */
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 200000;

    /**
     * 无 write_sandbox_file/edit_sandbox_file 权时的每轮预算（对照
     * DefaultSmartReasoningMaxCompletionTokens）。4096 与典型 OpenAI 兼容供应商的
     * 默认值一致，足够普通的工具调用 JSON。
     */
    public static final int DEFAULT_SMART_REASONING_MAX_COMPLETION_TOKENS = 4096;

    /**
     * 可写/可编辑沙箱文件的每轮预算（对照 DefaultAgentMaxCompletionTokens）。
     * 这些工具调用把文件体装在 JSON 里；4096 的上限会在流中间截断
     * （finish_reason=length）。
     */
    public static final int DEFAULT_AGENT_MAX_COMPLETION_TOKENS = 24576;

    /** RAG 回答预算（对照 DefaultQuickAnswerMaxCompletionTokens）。 */
    public static final int DEFAULT_QUICK_ANSWER_MAX_COMPLETION_TOKENS = 2048;

    /** 沙箱写作预算下限（对照 MinSandboxWriteCompletionTokens）。 */
    public static final int MIN_SANDBOX_WRITE_COMPLETION_TOKENS = 8192;

    /** 对照 AgentModeQuickAnswer（internal/types/custom_agent.go L38）。 */
    public static final String AGENT_MODE_QUICK_ANSWER = "quick-answer";
    /** 对照 AgentModeSmartReasoning（internal/types/custom_agent.go L40）。 */
    public static final String AGENT_MODE_SMART_REASONING = "smart-reasoning";

    private AgentBudgets() {
    }

    /**
     * 该 agent 是否可能注册 write_sandbox_file / edit_sandbox_file（对照
     * NeedsSandboxWriteCompletionBudget）。这些工具跟着绑定的沙箱走，
     * 不在 allowed_tools 清单里。
     */
    public static boolean needsSandboxWriteCompletionBudget(String sandboxConfigID) {
        return sandboxConfigID != null && !sandboxConfigID.trim().isEmpty();
    }

    /**
     * 未配置时的单 agent 默认预算（对照 DefaultMaxCompletionTokens）：
     * quick-answer 2048，smart-reasoning 4096，带沙箱的 smart-reasoning 24576。
     */
    public static int defaultMaxCompletionTokens(String agentMode, String sandboxConfigID) {
        if (AGENT_MODE_SMART_REASONING.equals(agentMode)) {
            if (needsSandboxWriteCompletionBudget(sandboxConfigID)) {
                return DEFAULT_AGENT_MAX_COMPLETION_TOKENS;
            }
            return DEFAULT_SMART_REASONING_MAX_COMPLETION_TOKENS;
        }
        return DEFAULT_QUICK_ANSWER_MAX_COMPLETION_TOKENS;
    }

    /**
     * 一个 ReAct LLM 轮次的补全预算（对照 AgentRoundMaxCompletionTokensFor）。
     * 未配置 + 有沙箱用大的写文件预算；配置值小于写文件下限时抬到下限。
     */
    public static int agentRoundMaxCompletionTokensFor(int configured, String sandboxConfigID) {
        if (configured > 0) {
            if (needsSandboxWriteCompletionBudget(sandboxConfigID)
                    && configured < MIN_SANDBOX_WRITE_COMPLETION_TOKENS) {
                return MIN_SANDBOX_WRITE_COMPLETION_TOKENS;
            }
            return configured;
        }
        return defaultMaxCompletionTokens(AGENT_MODE_SMART_REASONING, sandboxConfigID);
    }

    /** 对照 AgentRoundMaxCompletionTokens（无沙箱绑定）。 */
    public static int agentRoundMaxCompletionTokens(int configured) {
        return agentRoundMaxCompletionTokensFor(configured, "");
    }
}
