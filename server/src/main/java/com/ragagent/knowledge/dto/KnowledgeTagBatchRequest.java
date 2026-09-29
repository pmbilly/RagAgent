package com.ragagent.knowledge.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/** 标签批量更新请求：{@code updates} = 标签名 → 文档 ID 列表。 */
public record KnowledgeTagBatchRequest(
        @NotEmpty(message = "updates: 不能为空")
        Map<String, List<String>> updates,
        String kbId) {
}
