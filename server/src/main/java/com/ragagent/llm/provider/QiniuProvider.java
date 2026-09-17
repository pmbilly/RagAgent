package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.QiniuProvider（qiniu.go）：七牛云，OpenAI 兼容模式。
 */
public class QiniuProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.QINIU,
                "七牛云 Qiniu",
                "deepseek/deepseek-v3.2-251201, z-ai/glm-4.7, etc.",
                Map.of(ModelType.KNOWLEDGE_QA, ProviderBaseURLs.QINIU_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // 校验顺序 = Go 原文：baseURL → API key → model name
        if (config.baseUrl().isEmpty()) {
            throw BizException.badRequest("base URL is required for Qiniu provider");
        }
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Qiniu provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
