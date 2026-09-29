package com.ragagent.knowledge.dto;

import java.util.List;

public record AddSimilarQuestionsRequest(
        @jakarta.validation.constraints.NotEmpty(message = "similarQuestions: 不能为空")
        List<String> similarQuestions) {
}
