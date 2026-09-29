package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

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
