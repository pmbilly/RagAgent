package com.ragagent.mcp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.mcp.domain.McpMetadata;
import com.ragagent.mcp.domain.McpMetadataSummary;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.mapper.McpMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对照 Go internal/application/repository/mcp_metadata_test.go。
 *
 * <p>覆盖：复合主键 (tenant, service, principal) 的作用域、<b>陈旧快照不得覆盖新快照</b>、
 * 计数摘要只回计数不回 payload。</p>
 */
@SpringBootTest
class McpMetadataRepositoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private McpMetadataRepository repo;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private static McpTool tool(String name, String description, String schema) {
        try {
            return new McpTool(name, description, JSON.readTree(schema));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void persistsCompleteScopedSnapshotsAndRejectsOlderWrites() throws Exception {
        String schema = "{\"type\":\"object\",\"oneOf\":[{\"required\":[\"id\"]}],"
                + "\"properties\":{\"id\":{\"type\":\"string\"}},\"additionalProperties\":false}";

        OffsetDateTime syncedAt = OffsetDateTime.now(ZoneOffset.UTC);
        McpMetadata newer = new McpMetadata();
        newer.setTenantId(1L);
        newer.setServiceId("svc");
        newer.setPrincipal("user:a");
        newer.setConfigFingerprint("new");
        newer.setSyncedAt(syncedAt);
        newer.setInstructions("Server instructions");
        newer.setTools(List.of(tool("查询", "Full original description", schema)));
        repo.saveMetadata(newer);

        McpMetadata older = new McpMetadata();
        older.setTenantId(1L);
        older.setServiceId("svc");
        older.setPrincipal("user:a");
        older.setConfigFingerprint("new");
        older.setSyncedAt(syncedAt.minusMinutes(1));
        older.setTools(List.of());
        repo.saveMetadata(older);

        McpMetadata got = repo.getMetadata(1, "svc", "user:a");
        assertNotNull(got);
        assertEquals(1, got.getTools().size(),
                "更旧的刷新（更早的 synced_at）绝不能覆盖新快照");
        assertEquals("查询", got.getTools().get(0).getName());
        assertEquals("Full original description", got.getTools().get(0).getDescription());
        assertEquals(schema, JSON.writeValueAsString(got.getTools().get(0).getInputSchema()),
                "inputSchema 必须原样保留（含 oneOf / additionalProperties 等未建模关键字）");
        assertEquals("Server instructions", got.getInstructions());

        // 作用域隔离：别的租户 / 别的 principal / 别的服务都查不到
        assertNull(repo.getMetadata(2, "svc", "user:a"));
        assertNull(repo.getMetadata(1, "svc", "user:b"));
        assertNull(repo.getMetadata(1, "another", "user:a"));

        McpMetadata empty = new McpMetadata();
        empty.setTenantId(1L);
        empty.setServiceId("svc");
        empty.setPrincipal("user:a");
        empty.setConfigFingerprint("new");
        empty.setSyncedAt(syncedAt.plusSeconds(1));
        empty.setTools(List.of());
        repo.saveMetadata(empty);

        got = repo.getMetadata(1, "svc", "user:a");
        assertNotNull(got);
        assertEquals(0, got.getTools().size(),
                "成功同步的空目录必须退休掉被移除的工具");
    }

    @Test
    void listSummariesCountsToolsWithoutReturningPayloads() {
        McpMetadata withTools = new McpMetadata();
        withTools.setTenantId(1L);
        withTools.setServiceId("svc");
        withTools.setPrincipal("");
        withTools.setConfigFingerprint("fp");
        withTools.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        withTools.setServerName("Orders");
        withTools.setTools(List.of(
                tool("a", "secret-sized description", "{}"),
                tool("b", "", "{}")));
        repo.saveMetadata(withTools);

        McpMetadata oauth = new McpMetadata();
        oauth.setTenantId(1L);
        oauth.setServiceId("oauth");
        oauth.setPrincipal("user:a");
        oauth.setConfigFingerprint("fp2");
        oauth.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        oauth.setTools(List.of(tool("only-a", "", "{}")));
        repo.saveMetadata(oauth);

        List<McpMetadataSummary> rows = repo.listMetadataSummaries(1, List.of("", "user:a"));
        assertEquals(2, rows.size());
        Map<String, McpMetadataSummary> byId = new java.util.LinkedHashMap<>();
        for (McpMetadataSummary row : rows) {
            byId.put(row.getServiceId(), row);
        }
        assertEquals(2, byId.get("svc").getToolCount());
        assertEquals(1, byId.get("oauth").getToolCount());
        assertEquals("Orders", byId.get("svc").getServerName());
        assertEquals("fp", byId.get("svc").getConfigFingerprint());

        assertEquals(0, repo.listMetadataSummaries(1, List.of("user:b")).size(),
                "别人的 principal 快照不能出现在我的摘要里");
        assertEquals(0, repo.listMetadataSummaries(1, List.of()).size(),
                "空 principals 直接返回空（Go 返回 nil, nil）");
    }
}
