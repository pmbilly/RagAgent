package com.ragagent.knowledge.dto;

import com.ragagent.knowledge.domain.KnowledgeTag;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;

public record DeleteTagRequest(List<Long> excludeIds) {
}
