package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;

/** VLM（图像描述）配置视图——**不含** {@code apiKey}。 */
public record VlmConfigView(
        boolean enabled,
        String modelId,
        String descriptionLanguage,
        String customInstructions,
        String modelName,
        String baseUrl,
        String interfaceType) {

    public static VlmConfigView from(KnowledgeBaseVlmConfig c) {
        if (c == null) {
            return null;
        }
        return new VlmConfigView(c.isEnabled(), c.getModelId(), c.getDescriptionLanguage(),
                c.getCustomInstructions(), c.getModelName(), c.getBaseUrl(), c.getInterfaceType());
    }
}
