package com.ragagent.mcp.protocol;

/**
 * MCP 协议层哨兵错误（对照 Go internal/mcp/errors.go 的 9 个 {@code var ErrXxx}）。
 *
 * <p>Go 用 {@code errors.New} 的包级哨兵变量 + {@code errors.Is} 判定；Java 用枚举 +
 * {@link McpException#code()} 判定（语义等价，且枚举天然全局唯一）。</p>
 *
 * <p>{@link #goMessage()} 逐字保留 Go 的 error 文本——这些字符串会出现在返回给调用方的
 * 错误消息里（{@code fmt.Errorf("...: %w", err)} 的包裹链），改字会改变对外可见文案。</p>
 */
public enum McpErrorCode {

    /** 对照 Go ErrUnsupportedTransport。 */
    UNSUPPORTED_TRANSPORT("unsupported transport type"),

    /** 对照 Go ErrNotConnected：操作需要连接，但客户端未连接/未完成 initialize。 */
    NOT_CONNECTED("client not connected"),

    /** 对照 Go ErrAlreadyConnected：重复 connect。 */
    ALREADY_CONNECTED("client already connected"),

    /** 对照 Go ErrInitializeFailed。 */
    INITIALIZE_FAILED("MCP initialize handshake failed"),

    /** 对照 Go ErrToolNotFound。 */
    TOOL_NOT_FOUND("tool not found"),

    /** 对照 Go ErrResourceNotFound。 */
    RESOURCE_NOT_FOUND("resource not found"),

    /** 对照 Go ErrInvalidResponse：服务端响应非法（含协议分页上限被突破）。 */
    INVALID_RESPONSE("invalid response from server"),

    /** 对照 Go ErrTimeout。 */
    TIMEOUT("operation timed out"),

    /** 对照 Go ErrConnectionClosed。 */
    CONNECTION_CLOSED("connection closed"),

    /**
     * 服务端回 401（可能带 RFC 9728 metadata URL）。
     *
     * <p>⚠️ <b>不在 Go errors.go 的 9 个哨兵里</b>——它来自 mcp-go 的
     * {@code transport.ErrAuthorizationRequired}（文字 "authorization required"）。
     * Java 侧需要一个 code 来承载"401"这条信号，故在此登记；Go 侧对应的判定路径是
     * {@code errors.As(err, &transport.AuthorizationRequiredError)}。</p>
     */
    AUTHORIZATION_REQUIRED("authorization required");

    private final String goMessage;

    McpErrorCode(String goMessage) {
        this.goMessage = goMessage;
    }

    /** Go 侧同名哨兵错误的原文（对照 errors.go 每个 errors.New 的字符串）。 */
    public String goMessage() {
        return goMessage;
    }
}
