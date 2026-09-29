package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.knowledge.dto.FaqEntryPayload;
import java.time.OffsetDateTime;
import java.util.List;

public record FaqBatchUpsertPayload(
        @jakarta.validation.constraints.NotNull(message = "entries: 不能为空")
        List<FaqEntryPayload> entries,
        @jakarta.validation.constraints.NotBlank(message = "mode: 必须为 append 或 replace")
        @jakarta.validation.constraints.Pattern(regexp = "append|replace", message = "mode: 必须为 append 或 replace")
        String mode,
        String knowledgeId,
        String taskId,
        boolean dryRun) {
}
