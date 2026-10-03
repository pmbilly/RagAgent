package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 智谱 BigModel。
 */
public class ZhipuProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.ZHIPU,
                "智谱 BigModel",
                "glm-4.7, embedding-3, rerank, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.ZHIPU_CHAT_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.ZHIPU_EMBEDDING_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.ZHIPU_RERANK_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.ZHIPU_CHAT_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Zhipu AI");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
