package com.ragagent.knowledge.dto;

import java.util.List;

/** 文档列表分页响应。 */
public record KnowledgeListResponse(List<KnowledgeResponse> items, long page, long pageSize, long total) {
}
