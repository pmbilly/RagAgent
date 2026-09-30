package com.ragagent.knowledge.dto.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;

public record CreateKnowledgeBaseRequest(
        String name,
        String description,
        String type,
        String embeddingModelId,
        String summaryModelId,
        ChunkingConfigView chunkingConfig,
        ImageProcessingConfigView imageProcessingConfig,
        VlmConfigRequest vlmConfig,
        AsrConfigView asrConfig,
        IndexingStrategyView indexingStrategy,
        JsonNode extractConfig,
        JsonNode faqConfig,
        JsonNode questionGenerationConfig,
        JsonNode autoTagConfig,
        JsonNode wikiConfig,
        String storageBackendId,
        String storageProvider,
        String vectorStoreId) {

    /** 空请求体（全默认创建）。 */
    public static CreateKnowledgeBaseRequest empty() {
        return new CreateKnowledgeBaseRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /** VLM 配置的请求形态：比响应视图多 {@code apiKey}（凭据只进不出）。 */
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

    /** 组装待落库实体（未指定字段保持缺省，由服务层默认值链补齐）。 */
    public KnowledgeBase toEntity() {
        KnowledgeBase kb = new KnowledgeBase();
        if (name != null) kb.setName(name);
        kb.setDescription(description);
        if (type != null) kb.setType(type);
        if (embeddingModelId != null) kb.setEmbeddingModelId(embeddingModelId);
        if (summaryModelId != null) kb.setSummaryModelId(summaryModelId);
        if (chunkingConfig != null) kb.setChunkingConfig(chunkingConfig.toDomain());
        if (imageProcessingConfig != null) kb.setImageProcessingConfig(imageProcessingConfig.toDomain());
        if (vlmConfig != null) kb.setVlmConfig(vlmConfig.toDomain());
        if (asrConfig != null) kb.setAsrConfig(asrConfig.toDomain());
        if (indexingStrategy != null) kb.setIndexingStrategy(indexingStrategy.toDomain());
        kb.setExtractConfig(extractConfig);
        kb.setFaqConfig(faqConfig);
        kb.setQuestionGenerationConfig(questionGenerationConfig);
        kb.setAutoTagConfig(autoTagConfig);
        kb.setWikiConfig(wikiConfig);
        kb.setStorageBackendId(storageBackendId);
        if (storageProvider != null) kb.setStorageProvider(storageProvider);
        kb.setVectorStoreId(vectorStoreId);
        kb.normalizeVectorStoreId();
        return kb;
    }
}
