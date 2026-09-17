package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.LiteLLMProvider（litellm.go）。
 *
 * LiteLLM（https://github.com/BerriAI/litellm）暴露单一 OpenAI 兼容端点，背后路由到 100+
 * 厂商（OpenAI/Anthropic/Gemini/Bedrock/Vertex/Azure/...），故与其它网关型 provider 一样接
 * WeKnora 的 OpenAI 兼容传输层。
 */
public class LiteLLMProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.LITELLM,
                "LiteLLM",
                "Self-hosted LiteLLM proxy: one OpenAI-compatible endpoint to 100+ providers.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.LITELLM_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.LITELLM_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.LITELLM_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // Go 原文只校验 API key
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for LiteLLM provider");
        }
    }
}
