package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** FAQ 字段三态更新：{@code null} 表示不变更该字段。 */
public record FaqEntryFieldsUpdate(Boolean enabled, Boolean recommended, Long tagId) {
}
