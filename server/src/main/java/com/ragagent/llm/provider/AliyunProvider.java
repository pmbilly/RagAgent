package com.ragagent.llm.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go provider.AliyunProvider + aliyun.go 的模型判定函数。
 */
public class AliyunProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.ALIYUN,
                "阿里云 DashScope",
                "qwen-plus, tongyi-embedding-vision-plus, qwen3-rerank, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.ALIYUN_CHAT_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.ALIYUN_CHAT_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.ALIYUN_RERANK_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.ALIYUN_CHAT_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Aliyun DashScope");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }

    /**
     * 对照 Go IsQwenThinkingModel：检查模型名是否为支持思维链的 Qwen 模型。
     * 支持思维链的模型需要特殊处理 enable_thinking 参数。
     * 全部用 strings.HasPrefix(ToLower(name), ...) 前缀匹配，保序照抄（Go 原文 4 个分支）。
     */
    public static boolean isQwenThinkingModel(String modelName) {
        String lowerName = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        return lowerName.startsWith("qwen3")
                || lowerName.startsWith("qwen-plus")
                || lowerName.startsWith("qwen-max")
                || lowerName.startsWith("qwen-turbo");
    }

    /** 对照 Go IsQwen3Model：仅 Qwen3 家族 */
    public static boolean isQwen3Model(String modelName) {
        return (modelName == null ? "" : modelName.toLowerCase(Locale.ROOT)).startsWith("qwen3");
    }

    /**
     * 对照 Go IsDeepSeekModel：模型名是否含 "deepseek"（不区分大小写）。
     * DeepSeek 模型不支持 tool_choice 参数。
     */
    public static boolean isDeepSeekModel(String modelName) {
        return (modelName == null ? "" : modelName.toLowerCase(Locale.ROOT)).contains("deepseek");
    }
}
