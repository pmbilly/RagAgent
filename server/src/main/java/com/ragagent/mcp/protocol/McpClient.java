package com.ragagent.mcp.protocol;

import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpTool;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端。
 *
 * <p>状态机（三层门禁）：</p>
 * <ol>
 *   <li>{@code connect} 之前调 {@code initialize} → {@code ErrNotConnected}；</li>
 *   <li>{@code initialize} 之前调 list/call/read → {@code ErrNotConnected}
 *       （检查的是 initialized 标志，不是 connected）；</li>
 *   <li>重复 {@code connect} → {@code ErrAlreadyConnected}。</li>
 * </ol>
 */
public interface McpClient extends AutoCloseable {

    /** 建立连接。已连接时抛 {@code ErrAlreadyConnected}。 */
    void connect(McpContext ctx);

    /** 断开连接；幂等。 */
    void disconnect();

    /**
     * initialize 握手 + {@code notifications/initialized} 通知。
     *
     * @throws McpOAuthRequiredException 服务端要求 OAuth 且广告了 RFC 9728 metadata URL
     */
    InitializeResult initialize(McpContext ctx);

    /** 列出工具。 */
    List<McpTool> listTools(McpContext ctx);

    /** 列出资源。 */
    List<McpResource> listResources(McpContext ctx);

    /** 调用工具。 */
    CallToolResult callTool(String name, Map<String, Object> args, McpContext ctx);

    /** 读取资源。 */
    ReadResourceResult readResource(String uri, McpContext ctx);

    boolean isConnected();

    String serviceId();

    /**
     * initialize 里服务端下发的 instructions。
     */
    default String serverInstructions() {
        return "";
    }

    @Override
    default void close() {
        disconnect();
    }
}
