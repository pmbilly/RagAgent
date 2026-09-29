package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record KnowledgeMoveProgress(
        String taskId,
        String sourceKbId,
        String targetKbId,
        String status,
        int progress,
        int total,
        int processed,
        int failed,
        String message,
        String error,
        long createdAt,
        long updatedAt) {

    @JsonIgnore
    public boolean isTerminal() {
        return "completed".equals(status) || "failed".equals(status);
    }
}
