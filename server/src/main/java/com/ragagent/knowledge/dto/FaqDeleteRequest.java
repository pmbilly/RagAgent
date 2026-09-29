package com.ragagent.knowledge.dto;

import java.util.List;

public record FaqDeleteRequest(
        @jakarta.validation.constraints.NotEmpty(message = "ids: 不能为空")
        List<Long> ids) {
}
