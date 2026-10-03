package com.ragagent.mcp.protocol;

/**
 * MCP 协议层异常。
 *
 * <p>用 {@link #hasCode(McpErrorCode)} 判定类别。消息形如
 * {@code "failed to list tools: ..."}（前缀 + 底层原因），保留原始 cause 链。</p>
 */
public class McpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** null = 该错误不对应任何哨兵类别（如 SSRF 校验失败）。 */
    private final McpErrorCode code;

    public McpException(McpErrorCode code) {
        this(code, code.goMessage(), null);
    }

    public McpException(McpErrorCode code, String message) {
        this(code, message, null);
    }

    /** 构造"无哨兵"异常（无法按 code 判定类别）。 */
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

    /** @return 哨兵类别；null 表示无对应哨兵 */
    public McpErrorCode code() {
        return code;
    }

    public boolean hasCode(McpErrorCode expected) {
        return code == expected;
    }
}
