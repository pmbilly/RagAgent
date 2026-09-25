package com.ragagent.mcp.protocol;

import java.time.Duration;

/**
 * MCP 协议常量（对照 Go client.go 顶部常量与 client.go:477-481 的目录上限）。
 */
public final class McpProtocol {

    /**
     * initialize 协商的协议版本（照 Go 侧依赖 mcp-go v0.52.0 的
     * {@code mcp.LATEST_PROTOCOL_VERSION}，见 {@code mcp/types.go:139}）。
     *
     * <p><b>该值是依赖派生的</b>——Go 仓升级 mcp-go 时它随之上移，本常量必须跟着钉。
     * 2026-09-25 对齐：旧值 {@code 2024-11-05} 属于更早的 SDK 时代（Go 已升到 v0.52.0），
     * 差异与影响见 known-issues/06 的 W5γ4.21。</p>
     */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    /**
     * 服务端应答版本的白名单（照 mcp-go v0.52.0 {@code mcp.ValidProtocolVersions}，
     * {@code mcp/types.go:142-147}）：initialize 应答里的版本不在此表内即报错。
     */
    public static final java.util.List<String> VALID_PROTOCOL_VERSIONS =
            java.util.List.of(PROTOCOL_VERSION, "2025-06-18", "2025-03-26", "2024-11-05");

    /** 对照 mcp-go 的 {@code slices.Contains(mcp.ValidProtocolVersions, v)}（client/client.go:232）。 */
    public static boolean isSupportedProtocolVersion(String version) {
        return version != null && VALID_PROTOCOL_VERSIONS.contains(version);
    }

    /** clientInfo.name（Go 写作 "WeKnora"；Java 侧服务标识沿用同一字符串，前端/服务端日志可对照）。 */
    public static final String CLIENT_NAME = "WeKnora";

    public static final String CLIENT_VERSION = "1.0.0";

    /** 服务未配 AdvancedConfig.timeout 时的默认超时（对照 Go {@code 30 * time.Second}）。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** manager 初始化握手的硬上限（对照 Go manager.go:189-195 的 60s 封顶）。 */
    public static final Duration MAX_INITIALIZE_TIMEOUT = Duration.ofSeconds(60);

    /** 空闲连接清理周期（对照 Go manager.go:275 的 5 分钟）。 */
    public static final Duration IDLE_CLEANUP_INTERVAL = Duration.ofMinutes(5);

    // ---- 传输层常量（对照 mcp-go client/transport/constants.go + 各传输实现）----

    /** 会话 ID 响应头；服务端在 initialize 响应里下发，后续请求必须回传。 */
    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";

    /** 协议版本头；initialize 协商成功后带上（对照 mcp-go HeaderKeyProtocolVersion）。 */
    public static final String HEADER_PROTOCOL_VERSION = "Mcp-Protocol-Version";

    /** Streamable HTTP 的 Accept（对照 mcp-go {@code "application/json, text/event-stream"}）。 */
    public static final String ACCEPT_STREAMABLE = "application/json, text/event-stream";

    /** 传统 HTTP+SSE 建流时的 Accept。 */
    public static final String ACCEPT_SSE = "text/event-stream";

    /** SSE endpoint 事件的等待上限（对照 mcp-go WithEndpointTimeout 默认 30s）。 */
    public static final Duration SSE_ENDPOINT_TIMEOUT = Duration.ofSeconds(30);

    /** SSE `event:` 名——建流首帧给出 POST 地址（MCP 2024-11-05 HTTP+SSE 传输）。 */
    public static final String SSE_EVENT_ENDPOINT = "endpoint";

    /** SSE `event:` 名——JSON-RPC 消息帧。 */
    public static final String SSE_EVENT_MESSAGE = "message";

    // ---- 工具目录上限（对照 Go client.go:477-481）：租户可控的端点不可信 ----

    /** 分页上限：恶意/死循环服务端不能把目录无限撑大。 */
    public static final int MAX_TOOL_LIST_PAGES = 100;

    /** 单服务工具总数上限；超限**整体拒绝**，不发布部分目录。 */
    public static final int MAX_TOOLS_PER_SERVICE = 2000;

    /** 单个工具 inputSchema 的字节上限。 */
    public static final int MAX_TOOL_SCHEMA_BYTES = 256 * 1024;

    // ---- 方法名 ----

    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_INITIALIZED_NOTIFICATION = "notifications/initialized";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    public static final String METHOD_RESOURCES_LIST = "resources/list";
    public static final String METHOD_RESOURCES_READ = "resources/read";

    private McpProtocol() {
    }
}
