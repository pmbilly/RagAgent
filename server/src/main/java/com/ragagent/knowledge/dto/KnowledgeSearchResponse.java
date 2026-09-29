package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/** 跨库搜索响应（{@code items/hasMore/total}）。 */
public record KnowledgeSearchResponse(List<KnowledgeResponse> items, boolean hasMore, long total) {
}
