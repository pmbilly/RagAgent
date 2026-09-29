package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.knowledge.dto.FaqEntryPayload;
import java.time.OffsetDateTime;
import java.util.List;

public record FaqFailedEntry(
        int index,
        String reason,
        String failureType,
        boolean partialFailure,
        String tagName,
        String standardQuestion,
        List<String> similarQuestions,
        List<String> negativeQuestions,
        List<String> answers,
        boolean answerAll,
        boolean disabled,
        List<String> removedSimilarQuestions,
        List<String> removedNegativeQuestions) {
}
