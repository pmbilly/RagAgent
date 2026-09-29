package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.knowledge.dto.FaqEntryPayload;
import java.time.OffsetDateTime;
import java.util.List;

/** FAQ 导入成功条目：序号与命中标签。 */
public record FaqSuccessEntry(
        int index,
        long seqId,
        long tagId,
        String tagName,
        String standardQuestion) {
}
