package com.ragagent.knowledge.dto;


/** KB 副本创建响应：任务 ID + 源/目标 ID。 */
public record CopyKnowledgeBaseResponse(
        String taskId,
        String sourceId,
        String targetId) {
}
