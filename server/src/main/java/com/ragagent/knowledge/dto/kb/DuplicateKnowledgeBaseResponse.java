package com.ragagent.knowledge.dto.kb;


/** KB 复制（settings-only 同步）响应：源/目标 ID + 新 KB 视图。 */
public record DuplicateKnowledgeBaseResponse(
        String sourceId,
        String targetId,
        KnowledgeBaseResponse knowledgeBase) {
}
