package com.ragagent.knowledge.dto.chunk;


/** 批量删除结果：删除条数 + 任务 ID。 */
public record BatchTaskData(long deletedCount, String taskId) {
}
