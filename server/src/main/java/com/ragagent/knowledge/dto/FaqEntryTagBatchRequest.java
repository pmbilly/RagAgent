package com.ragagent.knowledge.dto;

import java.util.Map;

public record FaqEntryTagBatchRequest(
        @jakarta.validation.constraints.NotEmpty(message = "updates: 不能为空")
        Map<Long, Long> updates) {
}
