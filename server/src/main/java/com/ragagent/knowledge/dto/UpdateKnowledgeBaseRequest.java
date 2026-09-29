package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** KB 更新请求：名称/描述 + 配置 jsonb。 */
public record UpdateKnowledgeBaseRequest(String name, String description, JsonNode config) {
}
