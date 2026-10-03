package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * Azure OpenAI。
 *
 * 唯一的 ExtraFields 使用者（api_version，default "2024-10-21"），其余 provider 的
 * ExtraFields 均未配置。
 */
public class AzureOpenAIProvider implements Provider {

    /**
     * Azure 端点是含资源名的模板（{resource} 按租户替换），故提取为私有常量以便 5 处复用。
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
