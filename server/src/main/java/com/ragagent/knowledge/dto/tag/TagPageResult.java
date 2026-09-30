package com.ragagent.knowledge.dto.tag;

import java.util.List;

/** 标签分页结果（对外形状；仓储内为 {@code repository.TagPage}）。 */
public record TagPageResult(List<KnowledgeTagWithStats> items, int page, int pageSize, long total) {
}
