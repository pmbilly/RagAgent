package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateFromUrlRequest(
        @NotBlank(message = "url: 不能为空")
        String url,
        String fileName,
        String fileType,
        String title,
        String channel) {
}
