package com.ragagent.knowledge.dto.faq;

import java.util.List;
import jakarta.validation.constraints.NotBlank;

public record FaqSearchRequest(
        @NotBlank(message = "queryText: 不能为空")
        String queryText,
        double vectorThreshold,
        int matchCount,
        List<Long> firstPriorityTagIds,
        List<Long> secondPriorityTagIds,
        boolean onlyRecommended) {
}
