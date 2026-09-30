package com.ragagent.knowledge.dto.kb;


/** 重复文档冲突载荷：命中的既有文档 ID（409 特殊信封）。 */
public record DuplicateKnowledgeDetails(String existingKnowledgeId) {
}
