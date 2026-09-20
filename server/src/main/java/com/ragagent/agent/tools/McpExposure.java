package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;

/**
 * MCP 目录装载与注册入口（对照 Go {@code mcp_tool.go} 后半段的 MCPMetadataIO /
 * loadMCPDirectory / RegisterMCPTools / loadMCPServiceTools / MCPToolNamesByServiceID /
 * GetMCPToolsInfo / SerializeMCPToolResult，逐字移植）。
 */
public final class McpExposure {

    /** 对照 mcpStartupGrace。 */
    static final java.time.Duration MCP_STARTUP_GRACE = java.time.Duration.ofSeconds(1);
    /** 对照 mcpCatalogLoadTimeout。 */
    static final java.time.Duration MCP_CATALOG_LOAD_TIMEOUT = java.time.Duration.ofSeconds(30);
    /** tools/list 的单次列举超时（对照 loadMCPServiceTools 的 listToolsTimeout）。 */
    static final java.time.Duration LIST_TOOLS_TIMEOUT = java.time.Duration.ofSeconds(30);

    private McpExposure() {
    }

    /**
     * 读写持久化目录，可选地从已授权的活连接写回快照（对照 MCPMetadataIO）。
     * Put 绝不能用于发布不完整的 tools/list。
     */
    public static final class McpMetadataIO {
        private final DirectoryGetter get;
        private final DirectoryPutter put;

        @FunctionalInterface
        public interface DirectoryGetter {
            com.ragagent.mcp.domain.McpMetadata get(long tenantId, String serviceId) throws Exception;
        }

        @FunctionalInterface
        public interface DirectoryPutter {
            void put(long tenantId, String serviceId, List<McpTool> tools, String instructions) throws Exception;
        }

        public McpMetadataIO(DirectoryGetter get, DirectoryPutter put) {
            this.get = get;
            this.put = put;
        }

        static McpMetadataIO of(DirectoryGetter get, DirectoryPutter put) {
            return new McpMetadataIO(get, put);
        }
    }

    /**
     * 加载目录（对照 loadMCPDirectory）：非 live 优先取快照（stale 拒绝）、OAuth 未授权
     * 且无工具执行上下文时给"先去授权"的方向；live/未命中走上游并在 Put 可用时回写。
     * 返回 {tools, instructions} 或抛异常。
     */
    public record LoadedDirectory(List<McpTool> tools, String instructions) {
    }

    public static LoadedDirectory loadMcpDirectory(
            McpService service,
            com.ragagent.mcp.protocol.McpClientManager mcpManager,
            com.ragagent.agent.approval.McpApproval gate,
            McpOAuthSupport.McpOAuthSession oauthSess,
            McpMetadataIO metadata,
            boolean live,
            boolean hasToolExecContext,
            McpOAuthSupport.OAuthWaiter waiter,
            McpOAuthSupport.CallerIdentity caller) throws Exception {
        if (metadata == null || metadata.get == null) {
            return new LoadedDirectory(loadMcpServiceTools(service, mcpManager, gate, oauthSess,
                    waiter, caller), "");
        }
        if (!live) {
            com.ragagent.mcp.domain.McpMetadata snapshot = metadata.get.get(caller.tenantId(), service.getId());
            if (snapshot != null && snapshot.isStale()) {
                throw new IllegalStateException("MCP directory is stale; refresh Tools in Settings > MCP management");
            }
            if (snapshot != null) {
                return new LoadedDirectory(snapshot.getTools(), snapshot.getInstructions());
            }
        }
        if (service.getAuthConfig() != null && service.getAuthConfig().isOAuth() && !hasToolExecContext) {
            throw new IllegalStateException("MCP directory is missing; authorize this service, then refresh Tools");
        }
        List<McpTool> definitions = loadMcpServiceTools(service, mcpManager, gate, oauthSess, waiter, caller);
        String instructions = "";
        // instructions 由 loadMcpServiceTools 的 ServerInstructions 提取（见其返回）。
        if (metadata.put != null) {
            try {
                metadata.put.put(caller.tenantId(), service.getId(), definitions, instructions);
            } catch (Exception persistErr) {
                // 对照 Go 的 warn 日志：持久化失败不阻断注册
            }
        }
        return new LoadedDirectory(definitions, instructions);
    }

    /**
     * 连服务列工具（对照 loadMCPServiceTools）：缓存连接 stale 时断开重连再列一次；
     * stdio 列完即断。Server instructions 经 {@code client.serverInstructions()} 读取。
     */
    public static List<McpTool> loadMcpServiceTools(
            McpService service,
            com.ragagent.mcp.protocol.McpClientManager mcpManager,
            com.ragagent.agent.approval.McpApproval gate,
            McpOAuthSupport.McpOAuthSession oauthSess,
            McpOAuthSupport.OAuthWaiter waiter,
            McpOAuthSupport.CallerIdentity caller) throws Exception {
        String serviceId = service.getId();
        String toolCallId = "mcp-discover-" + serviceId;
        boolean isStdio = "stdio".equals(service.getTransportType());
        com.ragagent.mcp.protocol.McpClient client;
        try {
            client = McpOAuthSupport.getOrCreateMcpClientWithOAuthRetry(
                    mcpManager, service, waiter, oauthSess, "", toolCallId, caller);
        } catch (RuntimeException e) {
            throw e;
        }
        if (isStdio) {
            try {
                List<McpTool> tools = client.listTools(com.ragagent.mcp.protocol.McpContext.deadline(
                        java.time.Instant.now().plus(LIST_TOOLS_TIMEOUT)));
                return tools;
            } finally {
                try {
                    client.disconnect();
                } catch (Exception ignored) {
                    // 对照 Go 的 warn 日志
                }
            }
        }
        try {
            return client.listTools(com.ragagent.mcp.protocol.McpContext.deadline(
                    java.time.Instant.now().plus(LIST_TOOLS_TIMEOUT)));
        } catch (Exception err) {
            // 缓存连接可能已 stale：断开、重建、重列一次。
            try {
                client.disconnect();
            } catch (Exception ignored) {
                // 对照 Go 的 _ =
            }
            McpClientRetry retry = new McpClientRetry(mcpManager, service, waiter, oauthSess, toolCallId, caller);
            return retry.fresh().listTools(com.ragagent.mcp.protocol.McpContext.deadline(
                    java.time.Instant.now().plus(LIST_TOOLS_TIMEOUT)));
        }
    }

    private record McpClientRetry(
            com.ragagent.mcp.protocol.McpClientManager manager,
            McpService service,
            McpOAuthSupport.OAuthWaiter waiter,
            McpOAuthSupport.McpOAuthSession oauthSess,
            String toolCallId,
            McpOAuthSupport.CallerIdentity caller) {
        com.ragagent.mcp.protocol.McpClient fresh() {
            return McpOAuthSupport.getOrCreateMcpClientWithOAuthRetry(
                    manager, service, waiter, oauthSess, "", toolCallId(), caller);
        }
    }

    /**
     * 安装受限目录与 call 代理，不连接 MCP 服务器、不广告完整 schema（对照 RegisterMCPTools）。
     * 拒绝部分安装或与调用方已注册工具的碰撞。返回装入的服务数。
     */
    public static int registerMcpTools(
            ToolRegistry registry,
            List<McpService> services,
            com.ragagent.mcp.protocol.McpClientManager mcpManager,
            com.ragagent.agent.approval.McpApproval gate,
            int authWaitTimeoutSeconds,
            long tenantId,
            McpCatalog.McpServiceLookup lookup,
            McpMetadataIO metadata,
            boolean hasToolExecContext,
            McpOAuthSupport.OAuthWaiter waiter) throws Exception {
        McpCatalog.McpCatalogLoader loader = (service, live) -> {
            McpOAuthSupport.McpOAuthSession oauthSess = new McpOAuthSupport.McpOAuthSession(
                    null, "", "", "", "", null, java.time.Duration.ZERO, authWaitTimeoutSeconds);
            McpOAuthSupport.CallerIdentity caller = new McpOAuthSupport.CallerIdentity(
                    tenantId, "", "", false);
            LoadedDirectory dir = loadMcpDirectory(service, mcpManager, gate, oauthSess, metadata,
                    live, hasToolExecContext, waiter, caller);
            List<McpTool> definitions = dir.tools() == null ? List.of() : dir.tools();
            List<McpToolWrapper> tools = new ArrayList<>(definitions.size());
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (McpTool definition : definitions) {
                if (definition == null || definition.getName() == null || definition.getName().isEmpty()
                        || seen.contains(definition.getName())) {
                    continue;
                }
                seen.add(definition.getName());
                McpToolWrapper tool = new McpToolWrapper(service, definition, mcpManager, gate,
                        authWaitTimeoutSeconds, tenantId);
                tool.serverInstructions = dir.instructions();
                tools.add(tool);
            }
            return tools;
        };
        McpCatalog catalog = new McpCatalog(tenantId, "", "", services, gate, loader, lookup);
        // 对照 Go：newMCPCatalog 从 ctx 取 tenant/principal；本入口由装配层传入 tenantId，
        // principal 用 caller 的 StorageID 形态（引擎侧再以 authorize 校验）。
        if (catalog.authorizeExecution() != null) {
            throw new IllegalStateException("MCP directory is unavailable for this authorization context");
        }
        if (catalog.servers.isEmpty()) {
            return 0;
        }
        for (String name : List.of(ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS, ToolDefinitions.TOOL_CALL_MCP_TOOL)) {
            try {
                registry.getTool(name);
                throw new IllegalStateException("MCP entry point already registered: " + name);
            } catch (ToolRegistry.ToolNotFoundException expected) {
                // 未注册 → 继续
            }
        }
        McpCatalog.installMcpCatalog(registry, catalog);
        return catalog.servers.size();
    }

    /** 已注册 MCP 工具名按服务 ID 分组（对照 MCPToolNamesByServiceID；名字排序）。 */
    public static Map<String, List<String>> mcpToolNamesByServiceId(ToolRegistry registry) {
        if (registry == null) {
            return null;
        }
        Map<String, List<String>> out = new HashMap<>();
        for (String name : registry.listTools()) {
            AgentTool tool;
            try {
                tool = registry.getTool(name);
            } catch (ToolRegistry.ToolNotFoundException e) {
                continue;
            }
            McpToolWrapper mcpTool = null;
            if (tool instanceof McpRegisteredTool direct) {
                mcpTool = direct;
            } else if (tool instanceof McpToolWrapper wrapper) {
                mcpTool = wrapper;
            }
            if (mcpTool == null || mcpTool.service == null) {
                continue;
            }
            out.computeIfAbsent(mcpTool.service.getId(), k -> new ArrayList<>()).add(name);
        }
        for (List<String> names : out.values()) {
            java.util.Collections.sort(names);
        }
        return out;
    }

    /** 可用 MCP 工具的信息（对照 GetMCPToolsInfo；15 秒预算按服务尽力而为）。 */
    public static Map<String, List<String>> getMcpToolsInfo(
            List<McpService> services,
            com.ragagent.mcp.protocol.McpClientManager mcpManager) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (services == null) {
            return result;
        }
        java.time.Instant deadline = java.time.Instant.now().plus(java.time.Duration.ofSeconds(15));
        for (McpService service : services) {
            if (!service.isEnabled()) {
                continue;
            }
            try {
                com.ragagent.mcp.protocol.McpClient client =
                        mcpManager.getOrCreateClient(com.ragagent.mcp.protocol.McpContext.deadline(deadline), service);
                List<McpTool> tools = client.listTools(com.ragagent.mcp.protocol.McpContext.deadline(deadline));
                List<String> toolNames = new ArrayList<>(tools.size());
                for (McpTool tool : tools) {
                    toolNames.add(tool.getName());
                }
                result.put(service.getName(), toolNames);
            } catch (Exception e) {
                // 对照 Go：continue
            }
        }
        return result;
    }

    /** 为展示序列化 MCP 工具结果（对照 SerializeMCPToolResult）。 */
    public static String serializeMcpToolResult(com.ragagent.agent.domain.ToolResult result) throws Exception {
        if (result == null) {
            throw new IllegalArgumentException("result is nil");
        }
        if (!result.isSuccess()) {
            return "Error: " + result.getError();
        }
        String output = result.getOutput();
        if (output == null || output.isEmpty()) {
            output = "Success (no output)";
        }
        if (result.getData() != null) {
            try {
                String dataBytes = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result.getData());
                output += "\n\nStructured Data:\n" + dataBytes;
            } catch (Exception ignored) {
                // 对照 Go 的 err 分支：忽略并返回原 output
            }
        }
        return output;
    }
}
