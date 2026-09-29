package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public record KnowledgeTagBatchRequest(
        @NotEmpty(message = "updates: 不能为空")
        Map<String, List<String>> updates,
        String kbId) {
}
