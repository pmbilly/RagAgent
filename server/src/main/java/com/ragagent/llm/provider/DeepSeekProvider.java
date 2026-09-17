package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.DeepSeekProvider（deepseek.go）。
 */
public class DeepSeekProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.DEEPSEEK,
                "DeepSeek",
                "deepseek-chat, deepseek-reasoner, etc.",
                Map.of(ModelType.KNOWLEDGE_QA, ProviderBaseURLs.DEEPSEEK_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for DeepSeek provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
