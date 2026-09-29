package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.knowledge.dto.FaqEntryPayload;
import java.time.OffsetDateTime;
import java.util.List;

/** FAQ 合并明细：命中的标准问及变化量（答案/相似问/负例）。 */
public record FaqMergeDetail(
        int index,
        String standardQuestion,
        boolean answerChanged,
        int newSimilarCount,
        int newNegativeCount) {
}
