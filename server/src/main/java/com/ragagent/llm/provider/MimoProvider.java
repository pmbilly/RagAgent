package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 小米 Mimo。
 */
public class MimoProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.MIMO,
                "小米 MiMo",
                "mimo-v2-flash",
                Map.of(ModelType.KNOWLEDGE_QA, ProviderBaseURLs.MIMO_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Mimo provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
