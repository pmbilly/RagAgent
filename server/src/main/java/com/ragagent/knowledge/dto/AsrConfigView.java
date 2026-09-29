package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeBaseAsrConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseChunkingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseImageProcessingConfig;
import com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

/** ASR（音频转写）配置视图。 */
public record AsrConfigView(boolean enabled, String modelId, String language) {

    public static AsrConfigView from(KnowledgeBaseAsrConfig c) {
        return c == null ? null : new AsrConfigView(c.isEnabled(), c.getModelId(), c.getLanguage());
    }

    public KnowledgeBaseAsrConfig toDomain() {
        KnowledgeBaseAsrConfig c = new KnowledgeBaseAsrConfig();
        c.setEnabled(enabled);
        c.setModelId(modelId);
        c.setLanguage(language);
        return c;
    }
}
