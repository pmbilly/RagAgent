package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.AzureOpenAIProvider（azure_openai.go）。
 *
 * 唯一的 ExtraFields 使用者（api_version，default "2024-10-21"），其余 provider 的
 * ExtraFields 均为 Go 零值 nil。
 */
public class AzureOpenAIProvider implements Provider {

    /**
     * Go 侧该 URL 是 Info() 里的内联字面量（azure_openai.go 无 const），
     * 此处提取为私有常量以便 5 处复用，值一字不改。
     */
    private static final String RESOURCE_ENDPOINT = "https://{resource}.openai.azure.com";

    @Override
    public ProviderInfo info() {
        return new ProviderInfo(
                ProviderName.AZURE_OPEN_AI,
                "Azure OpenAI",
                "gpt-4o, gpt-4, text-embedding-ada-002, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, RESOURCE_ENDPOINT,
                        ModelType.EMBEDDING, RESOURCE_ENDPOINT,
                        ModelType.RERANK, RESOURCE_ENDPOINT,
                        ModelType.VLLM, RESOURCE_ENDPOINT,
                        ModelType.ASR, RESOURCE_ENDPOINT),
                // 注意：DefaultURLs 含 Rerank，但 ModelTypes 不含 Rerank（Go 原样如此）
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.VLLM, ModelType.ASR),
                true,
                List.of(new ExtraFieldConfig(
                        "api_version", "API Version", "string",
                        false, "2024-10-21", "e.g. 2024-10-21")));
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Azure OpenAI provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("deployment name (model name) is required");
        }
        if (config.baseUrl().isEmpty()) {
            throw BizException.badRequest("Azure resource endpoint (base URL) is required");
        }
    }
}
