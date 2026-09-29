package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

public record CopyKnowledgeBaseRequest(
        @jakarta.validation.constraints.NotBlank(message = "sourceId: 不能为空")
        String sourceId,
        String targetId,
        String taskId) {
}
