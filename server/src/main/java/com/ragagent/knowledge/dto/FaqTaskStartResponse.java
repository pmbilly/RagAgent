package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.knowledge.dto.FaqEntryPayload;
import java.time.OffsetDateTime;
import java.util.List;

/** FAQ 任务启动响应（任务 ID）。 */
public record FaqTaskStartResponse(String taskId) {
}
