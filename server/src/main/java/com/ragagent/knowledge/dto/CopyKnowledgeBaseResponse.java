package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CopyKnowledgeBaseResponse(
        String taskId,
        String sourceId,
        String targetId) {
}
