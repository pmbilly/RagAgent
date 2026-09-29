package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.domain.KnowledgeBaseVlmConfig;
import java.util.List;

public record RebuildIndexResponse(long documentCount) {
}
