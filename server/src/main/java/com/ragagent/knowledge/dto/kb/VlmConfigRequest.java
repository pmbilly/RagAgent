package com.ragagent.knowledge.dto.kb;

import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;

/** VLM 配置的写入形状：比视图多一个 {@code apiKey}。 */
public record VlmConfigRequest(
        Boolean enabled,
        String modelId,
        String descriptionLanguage,
        String customInstructions,
        String modelName,
        String baseUrl,
        String apiKey,
        String interfaceType) {

    public KnowledgeBaseVlmConfig toDomain() {
        KnowledgeBaseVlmConfig c = new KnowledgeBaseVlmConfig();
        c.setEnabled(Boolean.TRUE.equals(enabled));
        if (modelId != null) c.setModelId(modelId);
        c.setDescriptionLanguage(descriptionLanguage);
        c.setCustomInstructions(customInstructions);
        if (modelName != null) c.setModelName(modelName);
        if (baseUrl != null) c.setBaseUrl(baseUrl);
        if (apiKey != null) c.setApiKey(apiKey);
        if (interfaceType != null) c.setInterfaceType(interfaceType);
        return c;
    }
}
