package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.GenericProvider（generic.go）：通用 OpenAI 兼容接口。
 * DefaultURLs 为空 map（需要用户自行配置填写），RequiresAuth=false（可能需要也可能不需要）。
 */
public class GenericProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.GENERIC,
                "自定义 (OpenAI兼容接口)",
                "Generic API endpoint (OpenAI-compatible)",
                Map.of(),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK,
                        ModelType.VLLM, ModelType.ASR),
                false);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.baseUrl().isEmpty()) {
            throw BizException.badRequest("base URL is required for generic provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
