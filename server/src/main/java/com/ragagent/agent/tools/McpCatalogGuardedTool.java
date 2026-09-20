package com.ragagent.agent.tools;

/**
 * MCP 目录守卫接缝（对照 Go registry.go:170-176 对 {@code *MCPRegisteredTool} 的类型断言）。
 *
 * <p>Go：注册的 MCP 工具在执行前先走 {@code direct.catalog.authorize(ctx)}——
 * <b>鉴权先于 schema 校验</b>，连参数错误的 details 都不能暴露其他引擎主体的
 * 已注册工具定义。波 4.5c 的 {@code MCPRegisteredTool} 实现本接口接入。</p>
 *
 * <p>失败时 registry 返回 {@code mcpDiscoveryFailure(err, "unavailable")} 等价物：
 * {@code success=false, error=<鉴权错误>, data={"status":"unavailable"}}。</p>
 */
public interface McpCatalogGuardedTool {

    /**
     * @return 鉴权通过返回 null；失败返回错误文案（原样进 ToolResult.error）。
     */
    String authorizeCatalog();
}
