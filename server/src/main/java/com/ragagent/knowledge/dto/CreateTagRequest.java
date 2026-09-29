package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTagRequest(
        @NotBlank(message = "name: 不能为空")
        String name,
        String color,
        Integer sortOrder) {
}
