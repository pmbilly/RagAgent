package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public record CreateManualRequest(
        @NotBlank(message = "title: 不能为空")
        String title,
        String content,
        String status,
        String channel) {
}
