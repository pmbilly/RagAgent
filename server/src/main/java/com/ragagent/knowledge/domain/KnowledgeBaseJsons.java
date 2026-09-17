package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * KB 配置 jsonb 的 Jackson 读取辅助（对照 Go GORM Scan 的 json 反序列化）。
 * 读取失败语义与 Go Scan 一致：解析失败回退默认值（indexing_strategy → 默认策略）。
 */
public final class KnowledgeBaseJsons {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KnowledgeBaseJsons() {}

    public static KbChunkingConfig readChunking(JsonNode node) {
        if (node == null || node.isNull()) {
            return new KbChunkingConfig();
        }
        return MAPPER.convertValue(node, KbChunkingConfig.class);
    }

    public static KbImageProcessingConfig readImageProcessing(JsonNode node) {
        if (node == null || node.isNull()) {
            return new KbImageProcessingConfig();
        }
        return MAPPER.convertValue(node, KbImageProcessingConfig.class);
    }

    public static KbIndexingStrategy readIndexing(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return KbIndexingStrategy.defaultStrategy();
        }
        try {
            KbIndexingStrategy s = MAPPER.convertValue(node, KbIndexingStrategy.class);
            return s == null ? KbIndexingStrategy.defaultStrategy() : s;
        } catch (IllegalArgumentException e) {
            return KbIndexingStrategy.defaultStrategy();
        }
    }
}
