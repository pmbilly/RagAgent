package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.McpCatalog.McpDiscoveryArgs;
import com.ragagent.agent.tools.McpCatalog.McpServerSummary;
import com.ragagent.agent.tools.McpCatalog.McpToolSummary;
import com.ragagent.agent.tools.McpCatalog.McpDiscoveryPage;
import com.ragagent.mcp.domain.McpService;

/**
 * MCP 目录的分页与安装：游标分页（绑定查询与可见行）、快照浅拷贝、
 * 目录工具批量注册进 {@link ToolRegistry}。
 */
final class McpCatalogPagination {

    private McpCatalogPagination() {
    }

    /**

     * 游标同时绑定查询与其当前可见行。权限或快照变化使游标失效，

     * 而不是跳过未见的条目。

     */
    static ToolResult paginateMcp(McpDiscoveryPage page, McpDiscoveryArgs args, int outputBudget) {
        // 每页标一次（不是每条），且只在真有远端文本处：list_servers 返回的是本地配置的服务名。
        if (!"list_servers".equals(args.mode())) {
            page.notice = McpCatalog.MCP_EXTERNAL_DATA_NOTICE;
            page.nextStep = "Choose a tool, then use discover_mcp_tools(mode=\"describe\", "
                    + "server_id=<its server_id>, tool_name=<its name>) to read the full input_schema and obtain "
                    + "a callable tool_ref. Do not call from this summary.";
        }
        // 服务顺序按 ID。运行状态与可编辑的展示元数据不改变成员资格，不得使进行中的遍历失效。
        List<String> serverIds = new ArrayList<>();
        if (page.servers != null) {
            for (McpServerSummary server : page.servers) {
                serverIds.add(server.serverId);
            }
        }
        // fingerprint 的序列化键序固定：Mode/Server/Query + Servers + Tools。
        Map<String, Object> fingerprintSeed = new LinkedHashMap<>();
        fingerprintSeed.put("Mode", args.mode());
        fingerprintSeed.put("Server", args.serverId());
        fingerprintSeed.put("Query", args.query());
        fingerprintSeed.put("Servers", serverIds);
        List<Object> toolSeeds = new ArrayList<>();
        if (page.tools != null) {
            for (McpToolSummary t : page.tools) {
                Map<String, Object> ts = new LinkedHashMap<>();
                ts.put("tool_ref", t.toolRef);
                ts.put("server_id", t.serverId);
                ts.put("server_name", t.serverName);
                ts.put("name", t.name);
                ts.put("description", t.description);
                toolSeeds.add(ts);
            }
        }
        fingerprintSeed.put("Tools", toolSeeds);
        String fingerprint;
        try {
            fingerprint = McpCatalog.hex(McpCatalog.sha256(McpCatalog.GO_ENCODER.writeValueAsBytes(fingerprintSeed)));
        } catch (Exception e) {
            fingerprint = "";
        }
        int start = 0;
        if (!args.cursor().isEmpty()) {
            String err = null;
            int offset = 0;
            try {
                byte[] decoded = McpCatalog.b64UrlDecode(args.cursor());
                JsonNode cursorNode = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(decoded);
                String f = cursorNode.path("f").asText("");
                int o = cursorNode.path("o").asInt(-1);
                if (!f.equals(fingerprint) || o < 0) {
                    err = "invalid";
                } else {
                    offset = o;
                }
            } catch (Exception e) {
                err = "invalid";
            }
            if (err != null) {
                return McpCatalog.mcpDiscoveryFailure(
                        "directory cursor is invalid or the directory changed; restart listing without cursor",
                        "error");
            }
            start = offset;
        }
        int total = page.tools == null ? 0 : page.tools.size();
        if ("list_servers".equals(args.mode())) {
            total = page.servers == null ? 0 : page.servers.size();
        }
        if (start > total) {
            return McpCatalog.mcpDiscoveryFailure("directory cursor is out of range", "error");
        }
        page.total = total;
        int end = Math.min(start + args.limit(), total);
        while (true) {
            McpDiscoveryPage resultPage = shallowCopyPage(page);
            resultPage.hasMore = end < total;
            if (resultPage.hasMore) {
                Map<String, Object> cursor = new LinkedHashMap<>();
                cursor.put("f", fingerprint);
                cursor.put("o", end);
                try {
                    resultPage.nextCursor = McpCatalog.b64UrlEncode(McpCatalog.GO_ENCODER.writeValueAsBytes(cursor));
                } catch (Exception ignored) {
                    // 序列化不会失败；保形
                }
            }
            if ("list_servers".equals(args.mode())) {
                resultPage.servers = page.servers == null ? null : new ArrayList<>(page.servers.subList(start, end));
            } else if (page.tools != null) {
                // 内部保留定义引用供游标失效用，但只有 describe 向模型暴露可调用 ref。先拷贝再清空。
                List<McpToolSummary> window = new ArrayList<>();
                for (McpToolSummary t : page.tools.subList(start, end)) {
                    McpToolSummary copy = new McpToolSummary();
                    copy.toolRef = t.toolRef;
                    copy.serverId = t.serverId;
                    copy.serverName = t.serverName;
                    copy.name = t.name;
                    copy.description = t.description;
                    window.add(copy);
                }
                for (McpToolSummary t : window) {
                    t.toolRef = "";
                }
                resultPage.tools = window;
            } else {
                resultPage.tools = null;
            }
            ToolResult result;
            try {
                result = McpCatalog.mcpJsonResult(resultPage.toMap(false));
            } catch (Exception e) {
                return McpCatalog.mcpDiscoveryFailure("MCP definition is not valid JSON", "error");
            }
            if (result.getOutput().codePointCount(0, result.getOutput().length()) <= outputBudget) {
                return result;
            }
            if (end - start <= 1) {
                return McpCatalog.mcpDiscoveryFailure("one directory entry exceeds the output budget", "error");
            }
            end--;
        }
    }

    static McpDiscoveryPage shallowCopyPage(McpDiscoveryPage p) {
        McpDiscoveryPage c = new McpDiscoveryPage();
        c.mode = p.mode;
        c.nextStep = p.nextStep;
        c.notice = p.notice;
        c.serverName = p.serverName;
        c.servers = p.servers;
        c.tools = p.tools;
        c.total = p.total;
        c.hasMore = p.hasMore;
        c.nextCursor = p.nextCursor;
        c.status = p.status;
        return c;
    }

    static void installMcpCatalog(ToolRegistry registry, McpCatalog c) {
        // 有界的目录预览给模型路由提示，不暴露凭据、工具或 schema。全列表可经分页到达。
        List<String> ids = new ArrayList<>(c.servers.keySet());
        java.util.Collections.sort(ids);
        String preview = "";
        for (String id : ids) {
            McpService service = c.servers.get(id).service;
            McpServerSummary row = new McpServerSummary();
            row.serverId = id;
            row.name = service.getName();
            row.status = "not_loaded";
            row.usageInstructions = McpCatalog.shortMcpDescription(service.effectiveUsageInstructions());
            String encoded;
            try {
                encoded = McpCatalog.GO_ENCODER.writeValueAsString(row.toMap());
            } catch (Exception e) {
                continue;
            }
            if (preview.codePointCount(0, preview.length()) + encoded.codePointCount(0, encoded.length()) > 2000) {
                break;
            }
            preview = preview + encoded + "\n";
        }
        String description = McpCatalog.MCP_DISCOVERY_DESCRIPTION + String.format(
                "\nAuthorized services: %d. If a server is listed below, call list_tools or describe; "
                        + "use list_servers only for services that do not fit this preview:\n",
                ids.size()) + preview;
        // ID 对这个受限定目录是稳定的。把它们枚举进 schema，模型就会选授权的标识符
        // 而不是从散文里复现自由格式的 UUID。空目录省掉 enum，list_servers 仍是合法调用。
        String discoveryParameters = McpCatalog.mcpSchemaWithEnum(McpCatalog.MCP_DISCOVERY_SCHEMA, "server_id", ids);
        registry.registerTool(new McpDiscoverTool(
                ToolDefinitions.TOOL_DISCOVER_MCP_TOOLS, description, discoveryParameters, c));
        registry.registerTool(new McpCallTool(
                ToolDefinitions.TOOL_CALL_MCP_TOOL,
                "Call an authorized MCP tool using tool_ref returned by discover_mcp_tools. Read its "
                        + "full input_schema with describe before calling; listing does not enable execution. "
                        + "Pass the original tool arguments in arguments as a JSON object, never a JSON-encoded string. "
                        + "Discovery does not bypass approval or permissions.",
                McpCatalog.MCP_CALL_SCHEMA, c, registry));
    }
}
