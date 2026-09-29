package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record BatchDeleteRequest(
        @NotBlank(message = "kbId: 不能为空")
        String kbId,
        List<String> ids) {
}
