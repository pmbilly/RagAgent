package com.ragagent.llm.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ragagent.common.error.BizException;

/**
 * 月之暗面 Moonshot / Kimi。
 */
public class MoonshotProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.MOONSHOT,
                "月之暗面 Moonshot",
                "kimi-k2-turbo-preview, moonshot-v1-8k-vision-preview, etc.",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.MOONSHOT_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.MOONSHOT_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.VLLM),
                true);
    }

    @Override
    public void validateConfig(Config config) {
        // 校验顺序 = Go 原文：baseURL → API key → model name
        if (config.baseUrl().isEmpty()) {
            throw BizException.badRequest("base URL is required for Moonshot provider");
        }
        if (config.apiKey().isEmpty()) {
            throw BizException.badRequest("API key is required for Moonshot provider");
        }
        if (config.modelName().isEmpty()) {
            throw BizException.badRequest("model name is required");
        }
    }

    /**
     * 判断该 Moonshot/Kimi 模型是否只接受 temperature=1。
     *
     * <p>以下模型拒绝除 1 以外的任何 temperature：
     * <ul>
     *   <li>moonshot-v1 系列（moonshot-v1-8k / moonshot-v1-32k / moonshot-v1-128k）</li>
     *   <li>kimi-k2.5 与 kimi-k2.6（API 文档中无 temperature 参数）</li>
     * </ul>
     *
     * <p>kimi-k2 / kimi-k2-turbo / kimi-k2-thinking 接受完整 [0,1] 区间，不受影响。
     *
     * <p>注意：先 TrimSpace 再 ToLower（Go 原文如此），故首尾空白不影响判定；
     * kimi 分支是**精确相等**而非前缀，kimi-k2.5-turbo 之类不命中。
     */
    public static boolean isMoonshotFixedTempModel(String modelName) {
        String name = (modelName == null ? "" : modelName.trim()).toLowerCase(Locale.ROOT);
        if (name.startsWith("moonshot-v1")) {
            return true;
        }
        // kimi-k2.5, kimi-k2.6 — no temperature parameter supported
        return name.equals("kimi-k2.5") || name.equals("kimi-k2.6");
    }
}
