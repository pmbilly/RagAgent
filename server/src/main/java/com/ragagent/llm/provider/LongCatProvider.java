package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.LongCatProvider（longcat.go）：美团 LongCat AI。
 */
public class LongCatProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.LONGCAT,
                "LongCat AI",
                "LongCat-Flash-Chat, LongCat-Flash-Thinking, etc.",
                Map.of(ModelType.KNOWLEDGE_QA, ProviderBaseURLs.LONGCAT_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // 校验顺序 = Go 原文：baseURL → API key → model name
        if (config.baseUrl().isEmpty()) {
            throw BizException.badRequest("base URL is required for LongCat provider");
        }
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for LongCat provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
