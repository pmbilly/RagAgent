package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

/** chunk 回滚请求：目标版本 + 乐观锁 {@code expectedRevision}。 */
public record RevertChunkRequest(
        @NotNull(message = "revision: 不能为空")
        Integer revision,
        Integer expectedRevision) {
}
