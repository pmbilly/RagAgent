package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.SiliconFlowProvider（siliconflow.go）：硅基流动。
 */
public class SiliconFlowProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.SILICONFLOW,
                "硅基流动 SiliconFlow",
                "deepseek-ai/DeepSeek-V3.1, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.SILICONFLOW_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.SILICONFLOW_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.SILICONFLOW_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.SILICONFLOW_BASE_URL,
                        ModelType.ASR, ProviderBaseURLs.SILICONFLOW_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK,
                        ModelType.VLLM, ModelType.ASR),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // Go 原文只校验 API key
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for SiliconFlow provider");
        }
    }
}
