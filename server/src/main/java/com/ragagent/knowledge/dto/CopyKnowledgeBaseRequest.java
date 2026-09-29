package com.ragagent.knowledge.dto;


public record CopyKnowledgeBaseRequest(
        @jakarta.validation.constraints.NotBlank(message = "sourceId: 不能为空")
        String sourceId,
        String targetId,
        String taskId) {
}
