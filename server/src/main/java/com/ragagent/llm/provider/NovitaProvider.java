package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.NovitaProvider（novita.go）：Novita AI。
 */
public class NovitaProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.NOVITA,
                "Novita AI",
                "moonshotai/kimi-k2.5, zai-org/glm-5, minimax/minimax-m2.7, qwen/qwen3-embedding-0.6b, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.NOVITA_OPENAI_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.NOVITA_OPENAI_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.NOVITA_OPENAI_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Novita provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
