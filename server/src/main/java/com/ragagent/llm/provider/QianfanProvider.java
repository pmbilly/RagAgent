package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.QianfanProvider（qianfan.go）：百度千帆。
 */
public class QianfanProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.QIANFAN,
                "百度千帆 Baidu Cloud",
                "ernie-5.0-thinking-preview, embedding-v1, bce-reranker-base, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.QIANFAN_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.QIANFAN_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.QIANFAN_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.QIANFAN_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // 校验顺序 = Go 原文：baseURL → API key → model name
        if (config.baseUrl().isEmpty()) {
            throw BizException.badRequest("base URL is required for Qianfan provider");
        }
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Qianfan provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
