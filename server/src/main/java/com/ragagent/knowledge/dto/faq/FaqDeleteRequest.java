package com.ragagent.knowledge.dto.faq;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;

public record FaqDeleteRequest(
        @NotEmpty(message = "ids: 不能为空")
        List<Long> ids) {
}
