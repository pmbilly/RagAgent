package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 原生 Anthropic Messages API 的厂商元数据。
 */
public class AnthropicProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.ANTHROPIC,
                "Anthropic",
                "Claude models via native Anthropic Messages API",
                Map.of(ModelType.KNOWLEDGE_QA, ProviderBaseURLs.ANTHROPIC_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Anthropic provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
