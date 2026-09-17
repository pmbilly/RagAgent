package com.ragagent.llm.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.provider.OpenAIProvider;
import com.ragagent.llm.provider.ProviderName;

/**
 * 单次生成的补全预算（对照 Go internal/models/chat/completion_budget.go）。
 *
 * 核心契约：**一个内部预算，出站恰好一个字段**。OpenAI 视 max_tokens 与
 * max_completion_tokens 为互斥；火山 Ark 之类的网关会直接拒绝同时携带两者的请求
 * （Tencent/WeKnora#3014）。ChatOptions 上的两个字段是同一预算的输入别名
 * （YAML、老调用方、agent UI 各喂其中一个），都设时取较新的 MaxCompletionTokens
 * ——该逻辑在 {@link com.ragagent.llm.domain.ChatOptions#completionBudget()}。
 */
public enum CompletionBudget {

    MAX_TOKENS("max_tokens"),
    MAX_COMPLETION_TOKENS("max_completion_tokens");

    private final String wireName;

    CompletionBudget(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * 为本 provider+model 选出 Chat Completions JSON 的键名
     * （对照 Go wireCompletionTokenField）。
     *
     * 默认 max_completion_tokens（OpenAI Chat Completions / Azure / Ark）。
     * 只有文档（或 Pi 目录）使用旧名的 provider 留在 max_tokens；
     * 仅 WeKnora 使用、文档写 max_tokens 的主机（LKEAP）也在列。
     * 未知的 OpenAI 兼容主机——包括阿里云 DashScope——保持默认。
     * GPT-5 / o 系列永远用 max_completion_tokens。
     */
    public static CompletionBudget wireField(ProviderName name, String model) {
        if (OpenAIProvider.isOpenAIReasoningOrGPT5Model(model)) {
            return MAX_COMPLETION_TOKENS;
        }
        if (name == null) {
            return MAX_COMPLETION_TOKENS;
        }
        return switch (name) {
            case DEEPSEEK,      // api-docs.deepseek.com: 仅 max_tokens
                 ZHIPU,         // open.bigmodel.cn: 仅 max_tokens (Pi isZai)
                 SILICONFLOW,   // docs.siliconflow.com schema: max_tokens
                 MOONSHOT,      // Pi useMaxTokens (moonshot.ai)
                 NVIDIA,        // Pi useMaxTokens (NIM / vLLM)
                 GENERIC,       // 自托管 vLLM 通常忽略新字段
                 GPUSTACK,      // 私有 vLLM 类运行时
                 LKEAP          // cloud.tencent.com/document/product/1772/115969: 仅 max_tokens
                    -> MAX_TOKENS;
            default -> MAX_COMPLETION_TOKENS;
        };
    }

    /** 把预算写进请求体；budget<=0 时不动（对照 Go applyCompletionBudget）。 */
    public void apply(ObjectNode body, int budget) {
        if (body == null || budget <= 0) {
            return;
        }
        body.put(wireName, budget);
    }
}
