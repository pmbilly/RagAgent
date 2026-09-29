package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/** 文档更新请求：{@code title} 为空不变更；{@code description}/{@code customMetadata} 用 JsonNode 区分「未传 / 传 null / 传值」。 */
public record UpdateKnowledgeRequest(String title, JsonNode description, JsonNode customMetadata) {
}
