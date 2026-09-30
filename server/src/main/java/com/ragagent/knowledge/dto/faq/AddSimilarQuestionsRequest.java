package com.ragagent.knowledge.dto.faq;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;

public record AddSimilarQuestionsRequest(
        @NotEmpty(message = "similarQuestions: 不能为空")
        List<String> similarQuestions) {
}
