package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record MoveKnowledgeResponse(
        String taskId,
        String sourceKbId,
        String targetKbId,
        int knowledgeCount) {
}
