package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.knowledge.dto.FaqEntryPayload;
import java.time.OffsetDateTime;
import java.util.List;

public record FaqMergeDetail(
        int index,
        String standardQuestion,
        boolean answerChanged,
        int newSimilarCount,
        int newNegativeCount) {
}
