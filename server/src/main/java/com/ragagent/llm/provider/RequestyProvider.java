package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * Requesty。
 */
public class RequestyProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.REQUESTY,
                "Requesty",
                "openai/gpt-4o-mini, anthropic/claude-sonnet-4-5, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.REQUESTY_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.REQUESTY_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.REQUESTY_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // Go 原文只校验 API key
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Requesty provider");
        }
    }
}
