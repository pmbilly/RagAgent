package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * Google Gemini。
 * Chat 走 OpenAI 兼容端点，Embedding 走原生 Gemini API（两个 URL 不同，勿混）。
 */
public class GeminiProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.GEMINI,
                "Google Gemini",
                "gemini-3-flash-preview, gemini-2.5-pro, gemini-embedding-2, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.GEMINI_OPENAI_COMPAT_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.GEMINI_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Google Gemini provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }
}
