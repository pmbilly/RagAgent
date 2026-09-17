package com.ragagent.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 处理待审批工具调用的请求体（对照 Go handler 的
 * {@code resolveToolApprovalBody}，mcp_service.go:646-650）。
 *
 * <p>{@code modified_args} 在 Go 里是 {@code json.RawMessage}（原始字节），
 * 因为下游要把它**原样**交给工具。Java 侧用 {@link JsonNode} 承载：
 * 它保留对象/数组结构，且 {@code NullNode} 能区分 JSON 的 {@code null}
 * 与"字段缺失"，正是 Go 那段 {@code trimmed != "null"} 探测所需要的。</p>
 */
public record ResolveToolApprovalRequest(
        @JsonProperty("decision") String decision,
        @JsonProperty("modified_args") JsonNode modifiedArgs,
        @JsonProperty("reason") String reason) {
}
