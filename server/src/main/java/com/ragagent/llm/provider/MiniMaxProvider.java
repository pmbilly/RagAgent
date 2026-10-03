package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * MiniMax。
 * 默认 URL 用国内版（MiniMaxCNBaseURL），国际版常量另有其名（见 ProviderBaseURLs）。
 */
public class MiniMaxProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.MINIMAX,
                "MiniMax",
                "MiniMax-M3, MiniMax-M2.7, MiniMax-M2.7-highspeed, etc.",
                Map.of(ModelType.KNOWLEDGE_QA, ProviderBaseURLs.MINIMAX_CN_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for MiniMax provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
