package com.ragagent.knowledge.dto.kb;
import jakarta.validation.constraints.NotBlank;


public record CopyKnowledgeBaseRequest(
        @NotBlank(message = "sourceId: 不能为空")
        String sourceId,
        String targetId,
        String taskId) {
}
