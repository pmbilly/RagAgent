package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record DuplicateKnowledgeBaseResponse(
        String sourceId,
        String targetId,
        KnowledgeBaseResponse knowledgeBase) {
}
