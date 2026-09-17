package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.ModelScopeProvider（modelscope.go）：魔搭 ModelScope，OpenAI 兼容模式。
 */
public class ModelScopeProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.MODELSCOPE,
                "魔搭 ModelScope",
                "Qwen/Qwen3-8B, Qwen/Qwen3-Embedding-8B, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.MODELSCOPE_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.MODELSCOPE_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.MODELSCOPE_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // 校验顺序 = Go 原文：baseURL → API key → model name
        if (config.baseUrl().isEmpty()) {
            throw BizException.badRequest("base URL is required for ModelScope provider");
        }
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for ModelScope provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
