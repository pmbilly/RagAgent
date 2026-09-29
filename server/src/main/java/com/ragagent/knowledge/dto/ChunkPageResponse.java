package com.ragagent.knowledge.dto;

import java.util.List;

/** chunk 分页响应（{@code items/page/pageSize/total}）。 */
public record ChunkPageResponse(List<ChunkResponse> items, int page, int pageSize, long total) {
}
