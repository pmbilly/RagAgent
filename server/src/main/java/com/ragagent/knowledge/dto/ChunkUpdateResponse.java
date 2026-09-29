package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

public record ChunkUpdateResponse(ChunkResponse chunk, String description, String summaryStatus) {
}
