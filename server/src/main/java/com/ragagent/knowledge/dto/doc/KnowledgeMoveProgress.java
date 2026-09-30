package com.ragagent.knowledge.dto.doc;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** 跨库搬移进度（轮询 {@code GET /knowledge/move/progress/{taskId}}）。 */
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
