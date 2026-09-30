package com.ragagent.knowledge.dto.chunk;


/** 批量重析结果：重析条数 + 任务 ID。 */
public record ReparseTaskData(long reparseCount, String taskId) {
}
