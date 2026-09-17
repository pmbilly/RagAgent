package com.ragagent.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 响应（对照 mcp-go {@code client/transport.JSONRPCResponse}）。
 *
 * <p>{@code result} 保留为原始 {@link JsonNode}（对照 Go 的 {@code json.RawMessage}），
 * 由调用方各自解析——{@code DefaultMcpClient#listRawTools} 刻意走原始 JSON 通道，
 * 因为 SDK 的强类型 ToolInputSchema 会丢掉 {@code oneOf} 这类根级关键字、并把
 * {@code definitions} 改写成 {@code $defs} 却不改引用（Go 注释明确说明，client.go:483-486）。</p>
 */
public record JsonRpcResponse(String jsonrpc, JsonNode id, JsonNode result, JsonRpcError error) {

    public static JsonRpcResponse from(JsonNode node) {
        JsonNode idNode = node.get("id");
        JsonNode result = node.get("result");
        JsonRpcError err = node.hasNonNull("error") ? JsonRpcError.from(node.get("error")) : null;
        return new JsonRpcResponse(node.path("jsonrpc").asText(null), idNode, result, err);
    }

    /** 通知/流式消息的判定：没有 id 的报文不是响应（对照 Go {@code response.ID.IsNil()}）。 */
    public boolean isNotification() {
        return id == null || id.isNull();
    }

    public boolean hasError() {
        return error != null;
    }

    /** 对照 Go {@code response.Error.AsError()}。 */
    public McpException errorAsException() {
        return error == null ? null : error.asException();
    }

    public String idKey() {
        if (isNotification()) {
            return "";
        }
        return id.isTextual() ? id.asText() : id.asText(String.valueOf(id.asLong()));
    }
}
