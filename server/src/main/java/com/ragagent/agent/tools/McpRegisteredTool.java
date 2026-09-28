package com.ragagent.agent.tools;

import java.util.List;

import com.ragagent.agent.domain.ToolResult;

/**
 * 绑定一个授权目录与 schema 的模型可见定义（对照 Go {@code mcp_exposure.go} 的
 * MCPRegisteredTool，逐字移植）。它的定义虽然预先广告过，也<b>不得</b>绕过目录的
 * 实时权限与配置检查。实现 {@link McpCatalogGuardedTool}——registry 在 schema 校验
 * 之前先做鉴权（4.5a 预留的接缝在此接入）。
 */
public class McpRegisteredTool extends McpToolWrapper implements McpCatalogGuardedTool {

    private final McpCatalog catalog;
    private final String ref;

    McpRegisteredTool(McpToolWrapper bound, McpCatalog catalog, String ref) {
        super(bound.service, bound.mcpTool, bound.mcpManager, bound.gate, bound.authWaitTimeoutSeconds,
                bound.tenantId);
        this.registeredName = bound.registeredName;
        this.serverInstructions = bound.serverInstructions;
        this.withOAuthWaiter(bound.oauthWaiter());
        this.catalog = catalog;
        this.ref = ref;
    }

    /** 绑定的目录引用（MCPCallTool 的 enum 与 MCPCallTarget 都要用）。 */
    public String ref() {
        return ref;
    }

    /** 描述标识外部服务与原始工具（对照 Description 覆写）。 */
    @Override
    public String getDescription() {
        return String.format("[MCP service %s, server_id=%s, tool=%s (external)] %s",
                ToolRegistry.quotedGo(service.getName()),
                ToolRegistry.quotedGo(service.getId()),
                ToolRegistry.quotedGo(mcpTool.getName()),
                mcpTool.getDescription());
    }

    /** registry 的鉴权接缝（对照 registry.ExecuteTool 的类型断言分支）。 */
    @Override
    public String authorizeCatalog() {
        return catalog.authorizeExecution();
    }

    /** 调用前复验目录再执行绑定的工具（对照 Execute 覆写）。 */
    @Override
    public ToolResult execute(ToolRequest request) {
        McpCatalog.SnapshotResult snap = catalog.snapshot(service.getId(), false);
        if (snap.error() != null) {
            return McpCatalog.mcpDiscoveryFailure(snap.error(), "unavailable");
        }
        if (snap.tools() != null) {
            for (McpToolWrapper current : snap.tools()) {
                if (!McpCatalog.mcpToolRef(current).equals(ref)) {
                    continue;
                }
                String checkErr = catalog.checkEnabled(current);
                if (checkErr != null) {
                    return McpCatalog.mcpDiscoveryFailure(checkErr, "unavailable");
                }
                // 用最新服务配置，保留广告名供审批/审计记录。registry 已按该 schema 校验过。
                McpToolWrapper target = new McpToolWrapper(
                        current.service, current.mcpTool, current.mcpManager, current.gate,
                        current.authWaitTimeoutSeconds, current.tenantId);
                target.registeredName = getName();
                target.serverInstructions = current.serverInstructions;
                target.withOAuthWaiter(this.oauthWaiter());
                return target.execute(request);
            }
        }
        return McpCatalog.mcpDiscoveryFailure(
                "MCP tool definition changed or was removed; rediscover before calling",
                "unavailable");
    }
}
