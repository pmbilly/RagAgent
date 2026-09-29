package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public record RenameFolderRequest(
        @NotBlank(message = "from: 不能为空")
        String from,
        @NotBlank(message = "to: 不能为空")
        String to) {
}
