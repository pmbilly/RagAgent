package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

/** chunk 编辑响应：更新后的 chunk + 描述 + 摘要状态。 */
public record ChunkUpdateResponse(ChunkResponse chunk, String description, String summaryStatus) {
}
