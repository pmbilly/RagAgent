package com.ragagent.mcp.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.error.BizException;
import com.ragagent.mcp.domain.McpMetadata;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.domain.McpToolApproval;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go {@code mcp_usage_instructions.go} 的 {@code buildMCPUsageInput} /
 * {@code mcpUsageExcerpt}。
 *
 * <p>这段是**提示注入的第一道防线**：只把白名单字段（名称/描述/说明）喂给 LLM，
 * 绝不序列化连接配置或凭据；同时用 100 条 / 24000 字符的预算挡住超大目录。</p>
 */
class McpUsageInputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static McpService service(String name) {
        McpService s = new McpService();
        s.setName(name);
        return s;
    }

    private static McpTool tool(String name, String description) {
        return new McpTool(name, description, null);
    }

    private static McpMetadata snapshot(List<McpTool> tools) {
        McpMetadata m = new McpMetadata();
        m.setServerName("srv");
        m.setServerDescription("desc");
        m.setInstructions("instructions");
        m.setTools(new ArrayList<>(tools));
        return m;
    }

    @Test
    void excerptTrimsAndTruncatesByRune() {
        assertEquals("abc", excerpt("  abc  ", 10));
        // Go: string(runes[:limit-1]) + "…"
        assertEquals("abc…", excerpt("abcdefgh", 4));
        // 按码点而非 UTF-16 char 计数
        assertEquals("中文…", excerpt("中文字符串", 3));
    }

    private static String excerpt(String value, int limit) {
        return McpServiceController.mcpUsageExcerpt(value, limit);
    }

    @Test
    void buildsWhitelistedPayloadInGoFieldOrder() throws Exception {
        String json = McpServiceController.buildMCPUsageInput(
                service("svc"), snapshot(List.of(tool("t1", "d1"))), List.of());

        JsonNode node = JSON.readTree(json);
        assertEquals(List.of("name", "server_name", "server_description", "server_instructions", "tools"),
                List.copyOf(iterableToList(node.fieldNames())),
                "字段序 = Go 匿名 struct 声明序");
        assertEquals("svc", node.get("name").asText());
        assertEquals("srv", node.get("server_name").asText());
        assertEquals("desc", node.get("server_description").asText());
        assertEquals("instructions", node.get("server_instructions").asText());
        assertEquals(1, node.get("tools").size());
        assertEquals("t1", node.get("tools").get(0).get("name").asText());
        // 连接配置与凭据绝不出现在输入里
        assertFalse(json.contains("url"));
        assertFalse(json.contains("api_key"));
        assertFalse(json.contains("token"));
    }

    @Test
    void excludedDisabledTools() {
        McpToolApproval disabled = new McpToolApproval();
        disabled.setToolName("t2");
        disabled.setEnabled(false);

        String json = McpServiceController.buildMCPUsageInput(
                service("svc"), snapshot(List.of(tool("t1", "d1"), tool("t2", "d2"))),
                List.of(disabled));

        assertTrue(json.contains("\"t1\""), json);
        assertFalse(json.contains("\"t2\""), "被禁用的工具不得进入摘要输入：" + json);
    }

    @Test
    void rejectsWhenNoEnabledTools() {
        BizException e = assertThrows(BizException.class, () -> McpServiceController.buildMCPUsageInput(
                service("svc"), snapshot(List.of()), List.of()));
        assertEquals("no enabled MCP tools are available to summarize", e.appError().message());
    }

    @Test
    void capsToolCountAt100AndReportsOmitted() throws Exception {
        List<McpTool> tools = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            tools.add(tool("tool-" + i, ""));
        }
        String json = McpServiceController.buildMCPUsageInput(service("svc"), snapshot(tools), List.of());

        JsonNode node = JSON.readTree(json);
        assertEquals(100, node.get("tools").size(), "工具条数上限 100");
        assertEquals(20, node.get("omitted_tools").asInt(), "超出的条数记在 omitted_tools");
    }

    @Test
    void omittedToolsKeyAbsentWhenNothingOmitted() throws Exception {
        String json = McpServiceController.buildMCPUsageInput(
                service("svc"), snapshot(List.of(tool("t1", "d1"))), List.of());
        assertFalse(json.contains("omitted_tools"), "omitempty：没有省略时不出该键：" + json);
    }

    @Test
    void budgetStopsAddingToolsButKeepsGoing() throws Exception {
        List<McpTool> tools = new ArrayList<>();
        // 每个工具描述 2000 码点（正好是单字段上限），预算 24000 → 最多 12 个
        String big = "x".repeat(2000);
        for (int i = 0; i < 20; i++) {
            tools.add(tool("t" + i, big));
        }
        String json = McpServiceController.buildMCPUsageInput(service("svc"), snapshot(tools), List.of());

        JsonNode node = JSON.readTree(json);
        assertTrue(node.get("tools").size() <= 12, "字符预算必须挡住超量工具");
        assertEquals(20 - node.get("tools").size(), node.get("omitted_tools").asInt());
    }

    private static List<String> iterableToList(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
