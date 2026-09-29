package com.ragagent.knowledge.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * FAQ 检索请求（matchCount 由 controller 钳 [10,200] 后再传 service）。
 */
public final class FaqSearchDtos {

    private FaqSearchDtos() {
    }

    public record FaqSearchRequest(
            @jakarta.validation.constraints.NotBlank(message = "queryText: 不能为空")
            String queryText,
            double vectorThreshold,
            int matchCount,
            List<Long> firstPriorityTagIds,
            List<Long> secondPriorityTagIds,
            boolean onlyRecommended) {
    }

}
