package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 仅 Embedding/Rerank，无 Chat。
 */
public class JinaProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.JINA,
                "Jina",
                "jina-clip-v1, jina-embeddings-v2-base-zh, etc.",
                Map.of(
                        ModelType.EMBEDDING, ProviderBaseURLs.JINA_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.JINA_BASE_URL),
                List.of(ModelType.EMBEDDING, ModelType.RERANK),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // Go 原文只校验 API key（不校验 base URL / model name）
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Jina AI provider");
        }
    }
}
