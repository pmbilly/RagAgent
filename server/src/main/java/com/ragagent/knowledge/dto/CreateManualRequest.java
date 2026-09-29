package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateManualRequest(
        @NotBlank(message = "title: 不能为空")
        String title,
        String content,
        String status,
        String channel) {
}
