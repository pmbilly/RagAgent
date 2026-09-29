package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record UpdateLastImportDisplayStatusRequest(
        @jakarta.validation.constraints.NotBlank(message = "displayStatus: 不能为空")
        @jakarta.validation.constraints.Pattern(regexp = "open|close", message = "displayStatus: 必须为 open 或 close")
        String displayStatus) {
}
