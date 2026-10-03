package com.ragagent.llm.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * OpenAI。
 */
public class OpenAIProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.OPENAI,
                "OpenAI",
                "gpt-5.2, gpt-5-mini, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.OPENAI_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.OPENAI_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.OPENAI_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.OPENAI_BASE_URL,
                        ModelType.ASR, ProviderBaseURLs.OPENAI_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK,
                        ModelType.VLLM, ModelType.ASR),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for OpenAI provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }

    /**
     * 判断模型是否为 OpenAI / Azure OpenAI 的
     * 推理类（o-series）或 GPT-5 系列模型。
     *
     * <p>这些模型在 OpenAI Chat Completions API 中：
     * <ul>
     *   <li>不再支持 {@code max_tokens}，必须使用 {@code max_completion_tokens}；</li>
     *   <li>仅支持默认的 {@code temperature=1}、{@code top_p=1}，且不支持
     *       {@code frequency_penalty} / {@code presence_penalty} 等采样参数（传非默认值会被拒绝）。</li>
     * </ul>
     *
     * <p>参考：
     * https://platform.openai.com/docs/api-reference/chat 与
     * https://learn.microsoft.com/azure/ai-services/openai/how-to/reasoning
     *
     * <p>仅基于模型名做启发式匹配；对于 Azure OpenAI，因为模型名实际上是 deployment 名，
     * 用户若用了自定义部署名我们无法识别，此时仍会按普通模型处理（保持原行为）。
     *
     * <p>保真要点：先 TrimSpace 再 ToLower；空串直接 false；gpt-5 用前缀匹配；
     * o1/o3/o4 必须**精确相等或紧跟 "-"**，避免误命中 "openai-gpt-4"、"olympus-1"、"o3xtra"。
     */
    public static boolean isOpenAIReasoningOrGPT5Model(String modelName) {
        String name = (modelName == null ? "" : modelName.trim()).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return false;
        }
        if (name.startsWith("gpt-5")) {
            return true;
        }
        // o1 / o1-mini / o1-preview / o3 / o3-mini / o4-mini ...
        // 必须精确匹配，避免误命中 "openai-..." 之类。
        for (String prefix : new String[]{"o1", "o3", "o4"}) {
            if (name.equals(prefix) || name.startsWith(prefix + "-")) {
                return true;
            }
        }
        return false;
    }
}
