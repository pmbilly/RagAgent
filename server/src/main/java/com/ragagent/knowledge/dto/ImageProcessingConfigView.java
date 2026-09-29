package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeBaseAsrConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

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
