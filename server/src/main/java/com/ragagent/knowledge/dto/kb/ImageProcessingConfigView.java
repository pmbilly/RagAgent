package com.ragagent.knowledge.dto.kb;

import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;

/** 图像处理配置视图（仅模型 ID，描述由 VLM 配置驱动）。 */
public record ImageProcessingConfigView(String modelId) {

    public static ImageProcessingConfigView from(KnowledgeBaseImageProcessingConfig c) {
        return c == null ? null : new ImageProcessingConfigView(c.getModelId());
    }

    public KnowledgeBaseImageProcessingConfig toDomain() {
        KnowledgeBaseImageProcessingConfig c = new KnowledgeBaseImageProcessingConfig();
        c.setModelId(modelId);
        return c;
    }
}
