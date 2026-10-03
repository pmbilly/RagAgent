package com.ragagent.mcp.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 处理待审批工具调用的请求体。
 *
 * <p>{@code modified_args} 需要把原始 JSON **原样**交给工具，
 * 用 {@link JsonNode} 承载：它保留对象/数组结构，且 {@code NullNode} 能区分
 * JSON 的 {@code null} 与"字段缺失"——前置校验据此拒绝裸 null。</p>
 */
public record ResolveToolApprovalRequest(
        String decision,
        JsonNode modifiedArgs,
        String reason) {
}
