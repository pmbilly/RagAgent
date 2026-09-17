package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.NvidiaProvider（nvidia.go）。
 */
public class NvidiaProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.NVIDIA,
                "NVIDIA",
                "deepseek-ai-deepseek-v3_1, nv-embed-v1, rerank-qa-mistral-4b, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.NVIDIA_CHAT_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.NVIDIA_CHAT_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.NVIDIA_RERANK_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.NVIDIA_CHAT_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for NVIDIA");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
