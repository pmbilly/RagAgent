package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

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
