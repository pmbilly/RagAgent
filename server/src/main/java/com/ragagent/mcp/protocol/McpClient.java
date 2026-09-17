package com.ragagent.mcp.protocol;

import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpTool;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端（对照 Go internal/mcp/client.go:25-52 的 {@code MCPClient} 接口）。
 *
 * <p>方法名一律小驼峰，与 Go 的导出方法一一对应；参数里的 {@code ctx} 换成
 * {@link McpContext}（见该类的对照说明）。</p>
 *
 * <p>状态机与 Go 完全一致（三层门禁）：</p>
 * <ol>
 *   <li>{@code connect} 之前调 {@code initialize} → {@code ErrNotConnected}；</li>
 *   <li>{@code initialize} 之前调 list/call/read → {@code ErrNotConnected}
 *       （Go 检查的是 initialized 标志，不是 connected）；</li>
 *   <li>重复 {@code connect} → {@code ErrAlreadyConnected}。</li>
 * </ol>
 */
public interface McpClient extends AutoCloseable {

    /** 建立连接（对照 Go {@code Connect}）。已连接时抛 {@code ErrAlreadyConnected}。 */
    void connect(McpContext ctx);

    /** 断开连接（对照 Go {@code Disconnect}）；幂等。 */
    void disconnect();

    /**
     * initialize 握手 + {@code notifications/initialized} 通知（对照 Go {@code Initialize}）。
     *
     * @throws McpOAuthRequiredException 服务端要求 OAuth 且广告了 RFC 9728 metadata URL
     */
    InitializeResult initialize(McpContext ctx);

    /** 列出工具（对照 Go {@code ListTools}）。 */
    List<McpTool> listTools(McpContext ctx);

    /** 列出资源（对照 Go {@code ListResources}）。 */
    List<McpResource> listResources(McpContext ctx);

    /** 调用工具（对照 Go {@code CallTool}）。 */
    CallToolResult callTool(String name, Map<String, Object> args, McpContext ctx);

    /** 读取资源（对照 Go {@code ReadResource}）。 */
    ReadResourceResult readResource(String uri, McpContext ctx);

    /** 对照 Go {@code IsConnected}。 */
    boolean isConnected();

    /** 对照 Go {@code GetServiceID}。 */
    String serviceId();

    /**
     * initialize 里服务端下发的 instructions（对照 Go {@code mcpGoClient.ServerInstructions}）。
     * Go 的接口没有这个方法，manager 用类型断言取（manager.go:46-51）；Java 直接放进接口，
     * 语义等价且省掉断言。
     */
    default String serverInstructions() {
        return "";
    }

    @Override
    default void close() {
        disconnect();
    }
}
