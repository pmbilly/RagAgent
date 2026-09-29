package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** FAQ 字段批量更新：按 ID / 按标签装载，{@code excludeIds} 用于排除。 */
public record FaqEntryFieldsBatchUpdate(
        Map<Long, FaqEntryFieldsUpdate> byId,
        Map<Long, FaqEntryFieldsUpdate> byTag,
        List<Long> excludeIds) {
}
