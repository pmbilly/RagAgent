package com.ragagent.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.TestSchema;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpConfigFingerprint;
import com.ragagent.mcp.domain.McpMetadata;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.mapper.McpMetadataRepository;
import com.ragagent.mcp.mapper.McpOAuthRepository;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对照 Go internal/application/service/mcp_metadata_test.go。
 *
 * <p>Go 用 httptest + mcp-go 的 in-process server 做端到端刷新；Java 用
 * {@link com.sun.net.httpserver.HttpServer} 起一个最小的 JSON-RPC stub
 * （content-type: application/json，正是 Streamable HTTP 传输接受的形态之一）。</p>
 */
@SpringBootTest
class McpMetadataServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_DESCRIPTION = "Full description. ".repeat(100);
    private static final String TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},"
                    + "\"oneOf\":[{\"required\":[\"id\"]}],\"additionalProperties\":false}";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private McpServiceMapper mcpServiceMapper;
    @Autowired
    private McpMetadataRepository metadataRepo;
    @Autowired
    private McpOAuthRepository oauthRepo;
    @Autowired
    private SsrfGuard ssrfGuard;

    private McpServiceService svc;
    private McpMetadataService metadata;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        ssrfGuard.reloadWhitelist("127.0.0.1,example.com");
        svc = new McpServiceService(mcpServiceMapper, oauthRepo, null, ssrfGuard,
                Optional.empty(), Optional.empty());
        metadata = new McpMetadataService(mcpServiceMapper, metadataRepo, Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void seedService(String id, String url, McpAuthType authType) {
        McpService s = new McpService();
        s.setId(id);
        s.setTenantId(1L);
        s.setName("Orders");
        s.setDescription("My overview");
        s.setUsageInstructions("My guidance");
        s.setEnabled(true);
        s.setTransportType("http-streamable");
        s.setUrl(url);
        if (authType != null) {
            McpAuthConfig auth = new McpAuthConfig();
            auth.setAuthType(authType);
            s.setAuthConfig(auth);
        }
        OffsetDateTime ts = OffsetDateTime.now(ZoneOffset.UTC);
        s.setCreatedAt(ts);
        s.setUpdatedAt(ts);
        mcpServiceMapper.insert(s);
    }

    private static McpTool tool(String name, String description) {
        try {
            return new McpTool(name, description, JSON.readTree(TOOL_SCHEMA));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 可离线验证的部分（persist / get / stale / 作用域 / 限额） ─────────

    @Test
    void neverSynchronizedReturnsNullAndDocumentationEditsDoNotInvalidate() {
        seedService("svc", "https://example.com/mcp", null);

        assertNull(metadata.getMCPMetadata(1, "svc"), "只读已落库快照；从未同步过 = null");

        metadata.persistMCPMetadata(1, "svc",
                List.of(tool("live", "from chat")), "from-chat");

        McpMetadata got = metadata.getMCPMetadata(1, "svc");
        assertNotNull(got);
        assertEquals("live", got.getTools().get(0).getName());
        assertEquals("from-chat", got.getInstructions());
        assertFalse(got.isStale());

        // 改文档不改上游身份
        svc.updateMCPService(serviceFixture("svc", "Edited overview", "Edited guidance"),
                McpServiceService.updateFields("description", "usageInstructions"));
        got = metadata.getMCPMetadata(1, "svc");
        assertFalse(got.isStale(), "文档编辑不能让快照变陈旧");
        assertEquals("Edited guidance", svc.getMCPServiceByID(1, "svc").getUsageInstructions());

        // 改连接配置必须让快照变陈旧
        svc.updateMCPService(serviceFixtureWithUrl("svc", "https://example.com/changed"), null);
        got = metadata.getMCPMetadata(1, "svc");
        assertTrue(got.isStale(), "URL 变了 → 上游身份变了 → 快照陈旧");
    }

    private static McpService serviceFixture(String id, String description, String usageInstructions) {
        McpService u = new McpService();
        u.setId(id);
        u.setTenantId(1L);
        u.setDescription(description);
        u.setUsageInstructions(usageInstructions);
        return u;
    }

    private static McpService serviceFixtureWithUrl(String id, String url) {
        McpService u = new McpService();
        u.setId(id);
        u.setTenantId(1L);
        u.setUrl(url);
        return u;
    }

    @Test
    void anotherTenantMustNotAccessTheSnapshot() {
        seedService("svc", null, null);
        metadata.persistMCPMetadata(1, "svc", List.of(tool("t", "")), "i");

        McpMetadataException e = assertThrows(McpMetadataException.class,
                () -> metadata.getMCPMetadata(2, "svc"));
        assertEquals(McpMetadataException.Kind.SERVICE_NOT_FOUND, e.kind());
    }

    @Test
    void commitRejectsInvalidToolNames() {
        seedService("svc", null, null);
        McpService service = mcpServiceMapper.getByIdForTenant(1L, "svc");

        McpMetadataException dup = assertThrows(McpMetadataException.class,
                () -> metadata.commitMCPMetadata(1, service, "", List.of(tool("a", ""), tool("a", "")),
                        "", "", "", "", OffsetDateTime.now(ZoneOffset.UTC)));
        assertEquals(McpMetadataException.Kind.INVALID_TOOLS, dup.kind());

        McpMetadataException empty = assertThrows(McpMetadataException.class,
                () -> metadata.commitMCPMetadata(1, service, "", List.of(tool("", "")),
                        "", "", "", "", OffsetDateTime.now(ZoneOffset.UTC)));
        assertEquals(McpMetadataException.Kind.INVALID_TOOLS, empty.kind());
    }

    @Test
    void commitRejectsDirectoriesOverEightMiB() {
        seedService("svc", null, null);
        McpService service = mcpServiceMapper.getByIdForTenant(1L, "svc");
        String huge = "x".repeat(9 * 1024 * 1024);

        McpMetadataException e = assertThrows(McpMetadataException.class,
                () -> metadata.commitMCPMetadata(1, service, "", List.of(tool("big", huge)),
                        "", "", "", "", OffsetDateTime.now(ZoneOffset.UTC)));
        assertEquals(McpMetadataException.Kind.TOO_LARGE, e.kind());
    }

    @Test
    void commitRejectsWhenConnectionChangedDuringRefresh() {
        seedService("svc", "https://example.com/mcp", null);
        McpService staleView = mcpServiceMapper.getByIdForTenant(1L, "svc");

        // 模拟"刷新途中连接配置被改"：库里换了 URL，内存里还是旧视图
        jdbc.update("UPDATE mcp_services SET url = ? WHERE id = ?",
                "https://example.com/other", "svc");

        McpMetadataException e = assertThrows(McpMetadataException.class,
                () -> metadata.commitMCPMetadata(1, staleView, "", List.of(tool("t", "")),
                        "", "", "", "", OffsetDateTime.now(ZoneOffset.UTC)));
        assertEquals(McpMetadataException.Kind.CONNECTION_CHANGED, e.kind());
        assertNull(metadataRepo.getMetadata(1, "svc", ""), "被拒的提交不得留下部分目录");
    }

    @Test
    void oauthSnapshotsAreScopedToTheAuthorisingPrincipal() {
        seedService("svc", "https://example.com/mcp", null);
        McpMetadata saved = persist("svc", List.of(tool("shared", "")));

        // 切换成 OAuth 服务后：没有 principal 就根本读不了
        McpService stored = mcpServiceMapper.getByIdForTenant(1L, "svc");
        McpAuthConfig auth = new McpAuthConfig();
        auth.setAuthType(McpAuthType.OAUTH);
        stored.setAuthConfig(auth);
        mcpServiceMapper.updatePartial(stored);

        McpMetadataException e = assertThrows(McpMetadataException.class,
                () -> metadata.getMCPMetadata(1, "svc"));
        assertEquals(McpMetadataException.Kind.PRINCIPAL_REQUIRED, e.kind());

        TenantContext.Principal userA = new TenantContext.Principal(McpPrincipal.WEB_USER, "a");
        TenantContext.Principal userB = new TenantContext.Principal(McpPrincipal.WEB_USER, "b");

        // A 存自己的快照，B 看不到
        TenantContext.set(1L, userA, null, false, "a", false);
        McpMetadata personal = new McpMetadata();
        personal.setTenantId(1L);
        personal.setServiceId("svc");
        personal.setPrincipal(McpPrincipal.storageId(userA));
        personal.setConfigFingerprint(McpConfigFingerprint.of(stored));
        personal.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        personal.setInstructions(saved.getInstructions());
        personal.setTools(List.of(tool("personal", "")));
        metadataRepo.saveMetadata(personal);

        McpMetadata gotA = metadata.getMCPMetadata(1, "svc");
        assertNotNull(gotA);
        assertFalse(gotA.isStale());
        assertEquals("personal", gotA.getTools().get(0).getName());

        TenantContext.set(1L, userB, null, false, "b", false);
        assertNull(metadata.getMCPMetadata(1, "svc"),
                "绝不能回落到别人的或曾经共享的快照");
    }

    @Test
    void listSummariesPicksTheRightPrincipalPerService() {
        seedService("plain", null, null);
        seedService("oauth", null, null);
        persist("plain", List.of(tool("a", ""), tool("b", "")));

        // oauth 服务 + A 的私有快照
        McpService oauthService = mcpServiceMapper.getByIdForTenant(1L, "oauth");
        McpAuthConfig auth = new McpAuthConfig();
        auth.setAuthType(McpAuthType.OAUTH);
        oauthService.setAuthConfig(auth);
        mcpServiceMapper.updatePartial(oauthService);
        TenantContext.Principal userA = new TenantContext.Principal(McpPrincipal.WEB_USER, "a");
        TenantContext.set(1L, userA, null, false, "a", false);
        McpMetadata personal = new McpMetadata();
        personal.setTenantId(1L);
        personal.setServiceId("oauth");
        personal.setPrincipal(McpPrincipal.storageId(userA));
        personal.setConfigFingerprint(McpConfigFingerprint.of(oauthService));
        personal.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        personal.setTools(List.of(tool("only-a", "")));
        metadataRepo.saveMetadata(personal);

        List<McpService> services = mcpServiceMapper.listForTenant(1L);
        Map<String, com.ragagent.mcp.domain.McpMetadataSummary> summaries =
                metadata.listMCPMetadataSummaries(1L, services);

        assertEquals(2, summaries.get("plain").getToolCount());
        assertEquals(1, summaries.get("oauth").getToolCount(),
                "OAuth 服务只认当前 principal 的那一份");
        assertFalse(summaries.get("oauth").getPrincipal().isEmpty());

        // 没有 principal 时 OAuth 服务被跳过，非 OAuth 服务照常
        TenantContext.clear();
        Map<String, com.ragagent.mcp.domain.McpMetadataSummary> anonymous =
                metadata.listMCPMetadataSummaries(1L, services);
        assertNotNull(anonymous.get("plain"));
        assertNull(anonymous.get("oauth"));
    }

    private McpMetadata persist(String id, List<McpTool> tools) {
        metadata.persistMCPMetadata(1, id, tools, "from-chat");
        return metadata.getMCPMetadata(1, id);
    }

    // ── 端到端刷新（本地 stub MCP 服务端） ───────────────────────────────

    @Test
    void refreshConnectsUpstreamAndKeepsLastSnapshotOnFailure() throws Exception {
        HttpServer upstream = startStubServer();
        try {
            seedService("svc", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/mcp", null);

            McpMetadata saved = metadata.refreshMCPMetadata(1, "svc");
            assertEquals("Original server instructions", saved.getInstructions());
            assertEquals("Orders", saved.getServerName());
            assertEquals("2", saved.getServerVersion());
            assertEquals("Full description", saved.getServerDescription());
            assertEquals(1, saved.getTools().size());
            assertEquals(TOOL_DESCRIPTION, saved.getTools().get(0).getDescription());
            assertEquals(TOOL_SCHEMA,
                    JSON.writeValueAsString(saved.getTools().get(0).getInputSchema()));
        } finally {
            upstream.stop(0);
        }

        // 上游已关闭：刷新必须失败，且保留上一次快照
        assertThrows(com.ragagent.common.error.BizException.class,
                () -> metadata.refreshMCPMetadata(1, "svc"));
        McpMetadata got = metadata.getMCPMetadata(1, "svc");
        assertNotNull(got);
        assertEquals(1, got.getTools().size(), "失败的刷新必须保留已落库的快照");
        assertFalse(got.isStale());
    }

    /** 最小可用的 MCP 服务端：application/json 响应 + initialize 的 session 头。 */
    private HttpServer startStubServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                JsonNode request = JSON.readTree(raw);
                String method = request.path("method").asText("");
                JsonNode id = request.get("id");
                switch (method) {
                    case "initialize" -> {
                        ObjectNode result = JSON.createObjectNode();
                        result.put("protocolVersion", "2024-11-05");
                        result.set("capabilities", JSON.createObjectNode());
                        ObjectNode info = result.putObject("serverInfo");
                        info.put("name", "Orders");
                        info.put("version", "2");
                        info.put("description", "Full description");
                        result.put("instructions", "Original server instructions");
                        exchange.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
                        respond(exchange, id, result);
                    }
                    case "tools/list" -> {
                        ObjectNode result = JSON.createObjectNode();
                        ObjectNode tool = JSON.createObjectNode();
                        tool.put("name", "get_order");
                        tool.put("description", TOOL_DESCRIPTION);
                        tool.set("inputSchema", JSON.readTree(TOOL_SCHEMA));
                        result.putArray("tools").add(tool);
                        respond(exchange, id, result);
                    }
                    default -> {
                        // notifications/initialized 等通知：202 = 已受理，无响应体
                        exchange.sendResponseHeaders(202, -1);
                        exchange.close();
                    }
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, JsonNode id,
                                ObjectNode result) throws IOException {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id != null) {
            envelope.set("id", id);
        }
        envelope.set("result", result);
        byte[] body = JSON.writeValueAsBytes(envelope);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 供调试：打印 utf-8 字节数（对齐 Go len(string)） */
    static int utf8Length(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }
}
