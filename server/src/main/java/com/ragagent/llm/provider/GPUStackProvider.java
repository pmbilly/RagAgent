package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * GPUStack。
 * Rerank 用独立 BaseURL（/v1 而非 /v1-openai）。
 */
public class GPUStackProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.GPUSTACK,
                "GPUStack",
                "Choose your deployed model on GPUStack",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.GPUSTACK_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.GPUSTACK_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.GPUSTACK_RERANK_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.GPUSTACK_BASE_URL,
                        ModelType.ASR, ProviderBaseURLs.GPUSTACK_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK,
                        ModelType.VLLM, ModelType.ASR),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // 校验顺序 = Go 原文：baseURL → API key → model name
        if (config.baseUrl().isEmpty()) {
            throw BizException.badRequest("base URL is required for GPUStack provider");
        }
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for GPUStack provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
