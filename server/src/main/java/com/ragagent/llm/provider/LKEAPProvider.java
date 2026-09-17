package com.ragagent.llm.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.LKEAPProvider + lkeap.go（腾讯云知识引擎原子能力）。
 * 支持 DeepSeek-R1, DeepSeek-V3 系列模型，具备思维链能力。
 */
public class LKEAPProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.LKEAP,
                "腾讯云 LKEAP",
                "DeepSeek-R1, DeepSeek-V3, lke-reranker-base 等",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.LKEAP_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.LKEAP_RERANK_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.RERANK),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for LKEAP provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }

    /**
     * 对照 Go IsLKEAPDeepSeekV3Model：DeepSeek V3.x 系列。
     * V3.x 系列支持通过 Thinking 参数控制思维链开关。
     */
    public static boolean isLKEAPDeepSeekV3Model(String modelName) {
        return (modelName == null ? "" : modelName.toLowerCase(Locale.ROOT)).contains("deepseek-v3");
    }

    /**
     * 对照 Go IsLKEAPDeepSeekR1Model：DeepSeek R1 系列。
     * R1 系列默认开启思维链。
     */
    public static boolean isLKEAPDeepSeekR1Model(String modelName) {
        return (modelName == null ? "" : modelName.toLowerCase(Locale.ROOT)).contains("deepseek-r1");
    }

    /** 对照 Go IsLKEAPThinkingModel：R1 或 V3 即支持思维链 */
    public static boolean isLKEAPThinkingModel(String modelName) {
        return isLKEAPDeepSeekR1Model(modelName) || isLKEAPDeepSeekV3Model(modelName);
    }
}
