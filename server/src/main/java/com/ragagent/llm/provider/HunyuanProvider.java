package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 腾讯混元，OpenAI 兼容模式。
 */
public class HunyuanProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.HUNYUAN,
                "腾讯混元 Hunyuan",
                "hunyuan-pro, hunyuan-standard, hunyuan-embedding, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.HUNYUAN_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.HUNYUAN_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Hunyuan provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
