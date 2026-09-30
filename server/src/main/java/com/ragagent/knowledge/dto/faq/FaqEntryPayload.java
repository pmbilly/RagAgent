package com.ragagent.knowledge.dto.faq;

import java.util.List;
import jakarta.validation.constraints.NotBlank;

/** FAQ 条目载荷（落库与跨任务传递共用）。 */
public record FaqEntryPayload(
        Long id,
        @NotBlank(message = "standardQuestion: 不能为空")
        String standardQuestion,
        List<String> similarQuestions,
        List<String> negativeQuestions,
        List<String> answers,
        String answerStrategy,
        long tagId,
        String tagName,
        Boolean enabled,
        Boolean recommended) {
}
