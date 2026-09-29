package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

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
