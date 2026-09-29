package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/** 重复文档冲突载荷：命中的既有文档 ID（409 特殊信封）。 */
public record DuplicateKnowledgeDetails(String existingKnowledgeId) {
}
