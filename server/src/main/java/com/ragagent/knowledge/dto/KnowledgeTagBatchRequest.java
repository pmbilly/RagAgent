package com.ragagent.knowledge.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/** 标签批量更新请求：{@code updates} = 标签名 → 文档 ID 列表。 */
public record KnowledgeTagBatchRequest(
        @NotEmpty(message = "updates: 不能为空")
        Map<String, List<String>> updates,
        String kbId) {
}
