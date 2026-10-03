package com.ragagent.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 错误对象。
 *
 * <p>映射规则：</p>
 * <ul>
 *   <li>已知 code → 哨兵文案打底；若服务端 message 非空且与哨兵文案不同，则拼成
 *       {@code "<哨兵>: <服务端 message>"}；</li>
 *   <li>未知 code → 直接用服务端 message。</li>
 * </ul>
 *
 * <p>统一承载为 {@link McpException}（code = {@link McpErrorCode#INVALID_RESPONSE}）：
 * 协议客户端层的哨兵错误不是 JSON-RPC 错误码。</p>
 */
public record JsonRpcError(int code, String message, JsonNode data) {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int REQUEST_INTERRUPTED = -32800;
    public static final int RESOURCE_NOT_FOUND = -32002;
    public static final int URL_ELICITATION_REQUIRED = -32042;

    public static JsonRpcError from(JsonNode node) {
        int code = node.path("code").asInt(0);
        String message = node.path("message").asText("");
        JsonNode data = node.has("data") ? node.get("data") : null;
        return new JsonRpcError(code, message, data);
    }

    /** 转成 {@link McpException}。 */
    public McpException asException() {
        String sentinel = sentinelMessage();
        String text = sentinel != null
                ? ((message == null || message.isEmpty() || message.equals(sentinel))
                        ? sentinel
                        : sentinel + ": " + message)
                : (message == null ? "" : message);
        return new McpException(McpErrorCode.INVALID_RESPONSE, text);
    }

    /** 已知 JSON-RPC 错误码对应的哨兵文案。 */
    private String sentinelMessage() {
        return switch (code) {
            case PARSE_ERROR -> "parse error";
            case INVALID_REQUEST -> "invalid request";
            case METHOD_NOT_FOUND -> "method not found";
            case INVALID_PARAMS -> "invalid params";
            case INTERNAL_ERROR -> "internal error";
            case REQUEST_INTERRUPTED -> "request interrupted";
            case RESOURCE_NOT_FOUND -> "resource not found";
            default -> null;
        };
    }
}
