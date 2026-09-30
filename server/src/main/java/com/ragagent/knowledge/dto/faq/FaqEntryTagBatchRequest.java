package com.ragagent.knowledge.dto.faq;

import java.util.Map;
import jakarta.validation.constraints.NotEmpty;

public record FaqEntryTagBatchRequest(
        @NotEmpty(message = "updates: 不能为空")
        Map<Long, Long> updates) {
}
