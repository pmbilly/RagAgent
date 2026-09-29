package com.ragagent.knowledge.dto;

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
