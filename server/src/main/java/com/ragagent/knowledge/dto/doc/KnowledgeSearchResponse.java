package com.ragagent.knowledge.dto.doc;

import java.util.List;

/** 跨库搜索响应（{@code items/hasMore/total}）。 */
public record KnowledgeSearchResponse(List<KnowledgeResponse> items, boolean hasMore, long total) {
}
