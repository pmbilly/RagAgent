package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * call_mcp_tool：解析目录引用并调用其 MCP 目标。Parameters 只广告完整定义已在
 * 当前函数列表里的引用；执行仍复验作用域、schema 与策略。
 */
public class McpCallTool extends BaseTool {

    private final McpCatalog catalog;
    private final ToolRegistry registry;

    McpCallTool(String name, String description, String parameters, McpCatalog catalog, ToolRegistry registry) {
        super(name, description, parameters);
        this.catalog = catalog;
        this.registry = registry;
    }

    /** 只广告已 describe 的 refs（mcpPrepared 时来自注册的引用）。 */
    @Override
    public JsonNode getParameters() {
        List<String> refs = new ArrayList<>();
        List<McpRegisteredTool> registered = registry.mcpRegisteredTools();
        if (registry.isMcpPrepared()) {
            for (McpRegisteredTool tool : registered) {
                refs.add(tool.ref());
            }
        }
        refs.sort(String::compareTo);
        String schema = McpCatalog.mcpSchemaWithEnum(McpCatalog.MCP_CALL_SCHEMA, "tool_ref", refs);
        return parseTree(schema);
    }

    private static JsonNode parseTree(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid mcp call schema", e);
        }
    }

    /** 解析 catalog 目标并跑共享执行管线。 */
    @Override
    public ToolResult execute(ToolRequest request) {
        ResolveResult resolved = resolve(request, request.args());
        if (resolved.error() != null) {
            return McpCatalog.mcpDiscoveryFailure(resolved.error(), "unavailable");
        }
        return registry.executeInternal(request.cancellation(), request.execMeta(), resolved.tool(), resolved.argsNode());
    }

    record ResolveResult(McpToolWrapper tool, JsonNode argsNode, String error) {
    }

    /** 只在受限定目录内解析，绝不碰全局 registry。 */
    private ResolveResult resolve(ToolRequest request, JsonNode raw) {
        String authErr = catalog.authorizeExecution();
        if (authErr != null) {
            return new ResolveResult(null, null, authErr);
        }
        McpCatalog.DecodeResult decoded;
        try {
            decoded = McpCatalog.decodeMcpCall(raw);
        } catch (IllegalArgumentException e) {
            return new ResolveResult(null, null, e.getMessage());
        }
        String ref = decoded.toolRef();
        McpToolWrapper known = catalog.cachedTool(ref);
        if (known == null) {
            return new ResolveResult(null, null,
                    "unknown tool_ref; do not construct references from names. Copy server_id from "
                            + "discover_mcp_tools source summaries (or list_servers), then list_tools and describe "
                            + "the exact tool. Wait for describe and copy its tool_ref verbatim before retrying");
        }
        McpCatalog.SnapshotResult snap = catalog.snapshot(known.service.getId(), false);
        if (snap.error() != null) {
            return new ResolveResult(null, null, snap.error());
        }
        if (snap.tools() != null) {
            for (McpToolWrapper tool : snap.tools()) {
                if (McpCatalog.mcpToolRef(tool).equals(ref)) {
                    String checkErr = catalog.checkEnabled(tool);
                    if (checkErr != null) {
                        return new ResolveResult(null, null, checkErr);
                    }
                    if (!catalog.knownCallableRef(ref)) {
                        return new ResolveResult(null, null, String.format(
                                "tool schema has not been described; use discover_mcp_tools(mode=\"describe\", "
                                        + "server_id=%s, tool_name=%s) before calling",
                                ToolRegistry.quotedGo(tool.service.getId()),
                                ToolRegistry.quotedGo(tool.mcpTool.getName())));
                    }
                    return new ResolveResult(tool, decoded.arguments(), null);
                }
            }
        }
        return new ResolveResult(null, null, "MCP tool is no longer available or enabled; rediscover its definition");
    }

    /** 展示层的鉴权（registry.mcpCallTarget 用；与执行鉴权同源）。 */
    String authorizeForPresentation() {
        return catalog.authorizeExecution();
    }

    McpToolWrapper cachedToolForPresentation(String ref) {
        return catalog.cachedTool(ref);
    }

    boolean knownCallableForPresentation(String ref) {
        return catalog.knownCallableRef(ref);
    }
}
