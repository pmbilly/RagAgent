package com.ragagent.knowledge.dto;


/** 搬移任务创建响应：任务 ID + 源/目标库 + 文档数。 */
public record MoveKnowledgeResponse(
        String taskId,
        String sourceKbId,
        String targetKbId,
        int knowledgeCount) {
}
