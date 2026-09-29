package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

/** chunk 分页响应（{@code items/page/pageSize/total}）。 */
public record ChunkPageResponse(List<ChunkResponse> items, int page, int pageSize, long total) {
}
