package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public record CreateFromUrlRequest(
        @NotBlank(message = "url: 不能为空")
        String url,
        String fileName,
        String fileType,
        String title,
        String channel) {
}
