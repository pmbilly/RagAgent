package com.ragagent.agent;

/**
 * agent 轮次补全预算（对照 Go internal/types/agent.go 的预算族）。
 * 沙箱写文件预算（DefaultAgentMaxCompletionTokens 族）随沙箱裁剪退役。
 */
public final class AgentBudgets {

    /** 对照 DefaultMaxContextTokens。 */
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 200000;

    /**
     * smart-reasoning 的每轮缺省预算（对照 DefaultSmartReasoningMaxCompletionTokens）。
     * 4096 与典型 OpenAI 兼容供应商的默认值一致，足够普通的工具调用 JSON。
     */
    public static final int DEFAULT_SMART_REASONING_MAX_COMPLETION_TOKENS = 4096;

    /** RAG 回答预算（对照 DefaultQuickAnswerMaxCompletionTokens）。 */
    public static final int DEFAULT_QUICK_ANSWER_MAX_COMPLETION_TOKENS = 2048;

    /** 对照 AgentModeQuickAnswer（internal/types/custom_agent.go L38）。 */
    public static final String AGENT_MODE_QUICK_ANSWER = "quick-answer";
    /** 对照 AgentModeSmartReasoning（internal/types/custom_agent.go L40）。 */
    public static final String AGENT_MODE_SMART_REASONING = "smart-reasoning";

    private AgentBudgets() {
    }

    /**
     * 未配置时的单 agent 默认预算（对照 DefaultMaxCompletionTokens）：
     * quick-answer 2048，smart-reasoning 4096。
     */
    public static int defaultMaxCompletionTokens(String agentMode) {
        if (AGENT_MODE_SMART_REASONING.equals(agentMode)) {
            return DEFAULT_SMART_REASONING_MAX_COMPLETION_TOKENS;
        }
        return DEFAULT_QUICK_ANSWER_MAX_COMPLETION_TOKENS;
    }

    /**
     * 一个 ReAct LLM 轮次的补全预算（对照 AgentRoundMaxCompletionTokensFor）。
     */
    public static int agentRoundMaxCompletionTokens(int configured) {
        if (configured > 0) {
            return configured;
        }
        return defaultMaxCompletionTokens(AGENT_MODE_SMART_REASONING);
    }
}
