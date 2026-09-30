package com.ragagent.knowledge.domain;

import com.ragagent.common.web.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;

/** ImageProcessingConfig：单字段 */
public class KnowledgeBaseImageProcessingConfig {

    private String modelId = "";

    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }

    /** 从 KB 配置 jsonb 读取（null/解析失败 → 默认值）。 */
    public static KnowledgeBaseImageProcessingConfig from(JsonNode node) {
        if (node == null || node.isNull()) {
            return new KnowledgeBaseImageProcessingConfig();
        }
        try {
            KnowledgeBaseImageProcessingConfig parsed = JsonMappers.lenient().convertValue(node, KnowledgeBaseImageProcessingConfig.class);
            return parsed == null ? new KnowledgeBaseImageProcessingConfig() : parsed;
        } catch (IllegalArgumentException e) {
            return new KnowledgeBaseImageProcessingConfig();
        }
    }
}
