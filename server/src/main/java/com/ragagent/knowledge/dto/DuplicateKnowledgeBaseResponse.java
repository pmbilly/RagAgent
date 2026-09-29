package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** KB 复制（settings-only 同步）响应：源/目标 ID + 新 KB 视图。 */
public record DuplicateKnowledgeBaseResponse(
        String sourceId,
        String targetId,
        KnowledgeBaseResponse knowledgeBase) {
}
