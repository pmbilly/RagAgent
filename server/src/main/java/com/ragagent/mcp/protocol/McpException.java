package com.ragagent.mcp.protocol;

/**
 * MCP 协议层异常（对照 Go internal/mcp/errors.go 的哨兵 error + client.go 里的 {@code fmt.Errorf(... %w)} 包裹）。
 *
 * <p>Go 的调用方用 {@code errors.Is(err, ErrNotConnected)} 判定类别；Java 用
 * {@link #hasCode(McpErrorCode)}。消息一律取 Go 的包裹文案（如
 * {@code "failed to list tools: ..."}），保留原始 cause 链。</p>
 */
public class McpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** null = 该错误在 Go 侧只是普通 {@code fmt.Errorf}，不对应任何哨兵（如 SSRF 校验失败）。 */
    private final McpErrorCode code;

    public McpException(McpErrorCode code) {
        this(code, code.goMessage(), null);
    }

    public McpException(McpErrorCode code, String message) {
        this(code, message, null);
    }

    /** 构造"无哨兵"异常（对照 Go 的普通 {@code fmt.Errorf("...")}——errors.Is 判定不到任何哨兵）。 */
    public McpException(String message) {
        this(null, message, null);
    }

    public McpException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public McpException(McpErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** @return 对应 Go errors.go 的哨兵；null 表示 Go 侧无对应哨兵 */
    public McpErrorCode code() {
        return code;
    }

    public boolean hasCode(McpErrorCode expected) {
        return code == expected;
    }
}
