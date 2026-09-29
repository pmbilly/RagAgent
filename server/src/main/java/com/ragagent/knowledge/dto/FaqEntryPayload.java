package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record FaqEntryPayload(
        Long id,
        @jakarta.validation.constraints.NotBlank(message = "standardQuestion: 不能为空")
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
