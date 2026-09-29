package com.ragagent.agent;

/**
 * agent 轮次补全预算。
 * 沙箱写文件预算已随沙箱裁剪退役。
 */
public final class AgentBudgets {

    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 200000;

    /**
     * smart-reasoning 的每轮缺省预算。
     * 4096 与典型 OpenAI 兼容供应商的默认值一致，足够普通的工具调用 JSON。
     */
    public static final int DEFAULT_SMART_REASONING_MAX_COMPLETION_TOKENS = 4096;

    /** RAG 回答预算。 */
    public static final int DEFAULT_QUICK_ANSWER_MAX_COMPLETION_TOKENS = 2048;

    public static final String AGENT_MODE_QUICK_ANSWER = "quick-answer";
    public static final String AGENT_MODE_SMART_REASONING = "smart-reasoning";

    private AgentBudgets() {
    }

    /**
     * 未配置时的单 agent 默认预算：
     * quick-answer 2048，smart-reasoning 4096。
     */
    public static int defaultMaxCompletionTokens(String agentMode) {
        if (AGENT_MODE_SMART_REASONING.equals(agentMode)) {
            return DEFAULT_SMART_REASONING_MAX_COMPLETION_TOKENS;
        }
        return DEFAULT_QUICK_ANSWER_MAX_COMPLETION_TOKENS;
    }

    /**
     * 一个 ReAct LLM 轮次的补全预算。
     */
    public static int agentRoundMaxCompletionTokens(int configured) {
        if (configured > 0) {
            return configured;
        }
        return defaultMaxCompletionTokens(AGENT_MODE_SMART_REASONING);
    }
}
