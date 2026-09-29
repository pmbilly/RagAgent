package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * KB 配置 jsonb 的 Jackson 读取辅助。
 */
public final class KnowledgeBaseJsons {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KnowledgeBaseJsons() {}

    public static KnowledgeBaseChunkingConfig readChunking(JsonNode node) {
        if (node == null || node.isNull()) {
            return new KnowledgeBaseChunkingConfig();
        }
        return MAPPER.convertValue(node, KnowledgeBaseChunkingConfig.class);
    }

    public static KnowledgeBaseImageProcessingConfig readImageProcessing(JsonNode node) {
        if (node == null || node.isNull()) {
            return new KnowledgeBaseImageProcessingConfig();
        }
        return MAPPER.convertValue(node, KnowledgeBaseImageProcessingConfig.class);
    }

    public static KnowledgeBaseIndexingStrategy readIndexing(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return KnowledgeBaseIndexingStrategy.defaultStrategy();
        }
        try {
            KnowledgeBaseIndexingStrategy s = MAPPER.convertValue(node, KnowledgeBaseIndexingStrategy.class);
            return s == null ? KnowledgeBaseIndexingStrategy.defaultStrategy() : s;
        } catch (IllegalArgumentException e) {
            return KnowledgeBaseIndexingStrategy.defaultStrategy();
        }
    }
}
