package com.ragagent.knowledge.dto;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import com.ragagent.knowledge.domain.KnowledgeBase;

/**
 * 知识库域传输对象：更新、混合检索、复制请求与重建索引响应。
 * 线格式 = Java 字段名（camelCase）；跨字段条件校验
 * （如 hybrid-search 的 queryText/embedding 二选一）在 controller 内判定。
 */
public final class KnowledgeBaseDtos {

    private KnowledgeBaseDtos() {
    }

    /** 更新知识库请求：name 传空串校验失败（null = 不变更，controller 判定）。 */
    public record UpdateKbRequest(String name, String description, JsonNode config) {
    }

    /**
     * 创建知识库请求（camelCase；不再把数据库实体当请求体）。
     *
     * <p>全部字段可选：缺省由服务层默认值链补齐（类型默认 document、索引策略默认
     * 向量+关键词、存储走租户默认）。配置项复用响应侧视图类型；VLM 因需写入
     * {@code apiKey}（响应侧有意剔除）而单独声明请求形态。</p>
     */
    public record CreateKbRequest(
            String name,
            String description,
            String type,
            String embeddingModelId,
            String summaryModelId,
            KnowledgeBaseConfigViews.ChunkingConfigView chunkingConfig,
            KnowledgeBaseConfigViews.ImageProcessingConfigView imageProcessingConfig,
            VlmConfigRequest vlmConfig,
            KnowledgeBaseConfigViews.AsrConfigView asrConfig,
            KnowledgeBaseConfigViews.IndexingStrategyView indexingStrategy,
            JsonNode extractConfig,
            JsonNode faqConfig,
            JsonNode questionGenerationConfig,
            JsonNode autoTagConfig,
            JsonNode wikiConfig,
            String storageBackendId,
            String storageProvider,
            String vectorStoreId) {

        /** 空请求体（全默认创建）。 */
        public static CreateKbRequest empty() {
            return new CreateKbRequest(null, null, null, null, null, null, null, null, null,
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

    /** 混合检索请求：query_text 与 query_embedding 至少其一（precomputed-vector 语义）。 */
    public record HybridSearchRequest(
            String queryText,
            float[] queryEmbedding,
            Double vectorThreshold,
            Double keywordThreshold,
            Integer matchCount,
            Boolean disableKeywordsMatch,
            Boolean disableVectorMatch,
            Boolean skipContextEnrichment,
            List<String> knowledgeBaseIds,
            List<String> knowledgeIds,
            List<String> tagIds) {
    }

    /** 复制知识库请求：target_id 缺省 = 创建新库。 */
    public record CopyKbRequest(
            @jakarta.validation.constraints.NotBlank(message = "sourceId: 不能为空")
            String sourceId,
            String targetId,
            String taskId) {
    }


    /** 重建索引响应（裸资源，无信封；字段名即 Java 字段名）。 */
    public record RebuildIndexResponse(long documentCount) {
    }
}
