package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public record MoveKnowledgeRequest(
        @NotEmpty(message = "knowledgeIds: 不能为空")
        List<String> knowledgeIds,
        @NotBlank(message = "source_kbId: 不能为空")
        String sourceKbId,
        @NotBlank(message = "target_kbId: 不能为空")
        String targetKbId,
        @NotBlank(message = "mode: 不能为空")
        @Pattern(regexp = "reuse_vectors|reparse", message = "mode: 必须为 reuse_vectors 或 reparse")
        String mode) {
}
