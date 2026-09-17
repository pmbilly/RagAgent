package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.VolcengineProvider（volcengine.go）：火山引擎 Ark。
 * Chat/VLLM 用同一 URL，Embedding（多模态）与 Rerank（知识库托管）各用独立 URL。
 */
public class VolcengineProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.VOLCENGINE,
                "火山引擎 Volcengine",
                "doubao-1-5-pro-32k-250115, doubao-embedding-vision-250615, doubao-seed-rerank, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.VOLCENGINE_CHAT_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.VOLCENGINE_EMBEDDING_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.VOLCENGINE_RERANK_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.VOLCENGINE_CHAT_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Volcengine Ark provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
