package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

/** chunk 编辑请求：内容、启用态 + 乐观锁 {@code expectedRevision}。 */
public record UpdateChunkRequest(
        String content,
        Boolean enabled,
        Integer expectedRevision) {
}
