package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * OpenRouter。
 */
public class OpenRouterProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.OPENROUTER,
                "OpenRouter",
                "openai/gpt-5.2-chat, google/gemini-3-flash-preview, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.OPENROUTER_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.OPENROUTER_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.OPENROUTER_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // Go 原文只校验 API key
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for OpenRouter provider");
        }
    }
}
