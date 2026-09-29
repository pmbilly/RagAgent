package com.ragagent.knowledge.dto;

import java.util.List;

public record FaqSearchRequest(
        @jakarta.validation.constraints.NotBlank(message = "queryText: 不能为空")
        String queryText,
        double vectorThreshold,
        int matchCount,
        List<Long> firstPriorityTagIds,
        List<Long> secondPriorityTagIds,
        boolean onlyRecommended) {
}
