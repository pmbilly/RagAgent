package com.ragagent.knowledge.dto;

import java.util.List;

/** FAQ 条目分页结果。 */
public record FaqEntryPage(List<FaqEntry> items, int page, int pageSize, long total) {
}
