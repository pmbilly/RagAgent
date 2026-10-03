package com.ragagent.mcp.protocol;

/**
 * MCP 协议层哨兵错误。
 *
 * <p>每个枚举值携带一条固定的对外错误文案（见 {@link #goMessage()}）——这些字符串会出现在
 * 返回给调用方的错误消息里（异常包裹链），改字会改变对外可见文案。</p>
 */
public enum McpErrorCode {

    UNSUPPORTED_TRANSPORT("unsupported transport type"),

    /** 操作需要连接，但客户端未连接/未完成 initialize。 */
    NOT_CONNECTED("client not connected"),

    /** 重复 connect。 */
    ALREADY_CONNECTED("client already connected"),

    INITIALIZE_FAILED("MCP initialize handshake failed"),

    TOOL_NOT_FOUND("tool not found"),

    RESOURCE_NOT_FOUND("resource not found"),

    /** 服务端响应非法（含协议分页上限被突破）。 */
    INVALID_RESPONSE("invalid response from server"),

    TIMEOUT("operation timed out"),

    CONNECTION_CLOSED("connection closed"),

    /**
     * 服务端回 401（可能带 RFC 9728 metadata URL）。
     *
     * <p>⚠️ 专门承载"401"这条信号的哨兵文案 {@code "authorization required"}，
     * 与其余哨兵同表登记。</p>
     */
    AUTHORIZATION_REQUIRED("authorization required");

    private final String goMessage;

    McpErrorCode(String goMessage) {
        this.goMessage = goMessage;
    }

    /** 对外固定的错误文案。 */
    public String goMessage() {
        return goMessage;
    }
}
