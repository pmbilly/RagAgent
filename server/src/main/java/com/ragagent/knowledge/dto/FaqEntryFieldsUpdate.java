package com.ragagent.knowledge.dto;


/** FAQ 字段三态更新：{@code null} 表示不变更该字段。 */
public record FaqEntryFieldsUpdate(Boolean enabled, Boolean recommended, Long tagId) {
}
