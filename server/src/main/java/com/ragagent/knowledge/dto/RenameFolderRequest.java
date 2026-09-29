package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record RenameFolderRequest(
        @NotBlank(message = "from: 不能为空")
        String from,
        @NotBlank(message = "to: 不能为空")
        String to) {
}
