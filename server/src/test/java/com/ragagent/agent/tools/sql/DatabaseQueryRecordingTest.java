package com.ragagent.agent.tools.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.sql.DatabaseQueryTool.QueryResult;
import com.ragagent.agent.tools.sql.DatabaseQueryTool.SqlQueryExecutor;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.agent.tools.ToolRequest;
import com.ragagent.agent.tools.GoRecording45B;
import com.ragagent.agent.tools.RecordingSupport;

/**
 * 波 4.5b 回放：database_query（对照 Go 实录，探针 {@code zz_rec45b_dbquery_test.go}）。
 *
 * <p>与 Go 探针连同一 dev PG（localhost:15432，租户 10002）：本测试自行种同一份
 * 种子（id 前缀 aa45bq），经 {@link JdbcExecutor} 真跑注入后的 SQL 端到端验证
 * （tenant 过滤 / soft-delete / hidden-KB / chunk-enabled / scope 注入都在
 * 语料的结果集里可见）。dev PG 不可达时测试显式失败（不允许跳过）。</p>
 */
class DatabaseQueryRecordingTest {

    static Connection db;

    @BeforeAll
    static void seed() throws Exception {
        String url = System.getenv().getOrDefault("ZZ_PG_JDBC",
                "jdbc:postgresql://localhost:15432/WeKnora");
        String user = System.getenv().getOrDefault("ZZ_PG_USER", "postgres");
        String pass = System.getenv().getOrDefault("ZZ_PG_PASS", "postgres123!@#");
        db = DriverManager.getConnection(url, user, pass);
        try (var st = db.createStatement()) {
            st.execute("DELETE FROM knowledge_tag_relations WHERE knowledge_id LIKE 'aa45bq%' OR tag_id LIKE 'aa45bq%'");
            st.execute("DELETE FROM chunks WHERE id LIKE 'aa45bq%'");
            st.execute("DELETE FROM knowledges WHERE id LIKE 'aa45bq%'");
            st.execute("DELETE FROM knowledge_bases WHERE id LIKE 'aa45bq%'");
        }
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO knowledge_bases (id, tenant_id, name, embedding_model_id, summary_model_id,"
                        + " is_temporary, type) VALUES (?, ?, ?, 'm-emb', 'm-sum', ?, 'document')")) {
            Object[][] kbs = {
                    {"aa45bq01", 10002, "BQ Alpha", false},
                    {"aa45bq02", 10002, "BQ Beta", false},
                    {"aa45bq09", 10002, "BQ Temp", true},
            };
            for (Object[] kb : kbs) {
                ps.setString(1, (String) kb[0]);
                ps.setInt(2, (Integer) kb[1]);
                ps.setString(3, (String) kb[2]);
                ps.setBoolean(4, (Boolean) kb[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source)"
                        + " VALUES (?, ?, ?, 'file', ?, 'upload')")) {
            Object[][] ks = {
                    {"aa45bqk1", 10002, "aa45bq01", "Alpha Doc One"},
                    {"aa45bqk2", 10002, "aa45bq01", "Alpha Doc Two"},
                    {"aa45bqk3", 10002, "aa45bq02", "Beta Doc"},
                    {"aa45bqk9", 999, "aa45bq01", "Other Tenant Doc"},
            };
            for (Object[] k : ks) {
                ps.setString(1, (String) k[0]);
                ps.setInt(2, (Integer) k[1]);
                ps.setString(3, (String) k[2]);
                ps.setString(4, (String) k[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        Instant base = Instant.parse("2024-07-01T00:00:00Z");
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO chunks (id, tenant_id, knowledge_base_id, knowledge_id, content, chunk_index,"
                        + " is_enabled, start_at, end_at, chunk_type, created_at, deleted_at)"
                        + " VALUES (?, ?, ?, ?, ?, 0, ?, 0, 99, 'text', ?, ?)")) {
            Object[][] cs = {
                    {"aa45bqc1", 10002, "aa45bq01", "aa45bqk1", "alpha particle content", true, null},
                    {"aa45bqc2", 10002, "aa45bq01", "aa45bqk1", "beta gamma content", true, null},
                    {"aa45bqc3", 10002, "aa45bq01", "aa45bqk2", "alpha disabled chunk", false, null},
                    {"aa45bqc4", 10002, "aa45bq01", "aa45bqk2", "alpha deleted chunk", true,
                            Timestamp.from(base)},
                    {"aa45bqc9", 999, "aa45bq01", "aa45bqk1", "alpha ghost tenant", true, null},
            };
            for (Object[] c : cs) {
                ps.setString(1, (String) c[0]);
                ps.setInt(2, (Integer) c[1]);
                ps.setString(3, (String) c[2]);
                ps.setString(4, (String) c[3]);
                ps.setString(5, (String) c[4]);
                ps.setBoolean(6, (Boolean) c[5]);
                if (c[6] == null) {
                    ps.setTimestamp(7, Timestamp.from(base));
                    ps.setObject(8, null);
                } else {
                    ps.setTimestamp(7, Timestamp.from(base));
                    ps.setTimestamp(8, (Timestamp) c[6]);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (var st = db.createStatement()) {
            st.execute("INSERT INTO knowledge_tag_relations (knowledge_id, tag_id)"
                    + " VALUES ('aa45bqk2', 'aa45bqt1')");
        }
    }

    // ==================== JDBC seam（对照 Go gorm Raw(securedSQL).Rows()） ====================

    /** 对照 {@code db.Raw(securedSQL).Rows()}：列名 + 行扫描（值原样交给工具侧做 []byte→string）。 */
    static final class JdbcExecutor implements SqlQueryExecutor {
        @Override
        public QueryResult query(String securedSQL) {
            try (Statement st = db.createStatement();
                    ResultSet rs = st.executeQuery(securedSQL)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    columns.add(md.getColumnName(i));
                }
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ==================== 回放框架 ====================

    private static JsonNode rec(String name) {
        try {
            String json = (String) GoRecording45B.class
                    .getField("R_" + name.toUpperCase(java.util.Locale.ROOT)).get(null);
            return GoRecording45B.rec(json);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolRequest req(JsonNode r) {
        try {
            return ToolRequest.of(RecordingSupport.PLAIN.readTree(r.get("args").asText()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertToolResult(String label, ToolResult result, JsonNode r) {
        assertThat(result.isSuccess()).as("%s success", label).isEqualTo(r.get("success").asBoolean());
        assertThat(result.getOutput()).as("%s output", label).isEqualTo(r.get("output").asText());
        String wantError = r.hasNonNull("error") ? r.get("error").asText() : "";
        if (!wantError.isEmpty()) {
            assertThat(result.getError()).as("%s error", label).isEqualTo(wantError);
        }
        JsonNode wantData = r.get("data");
        if (wantData == null || wantData.isNull()) {
            assertThat(result.getData()).as("%s data", label).isNull();
        } else {
            assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(result.getData())))
                    .as("%s data", label)
                    .isEqualTo(RecordingSupport.canonicalJson(wantData));
        }
    }

    private static DatabaseQueryTool tool(SearchTarget.SearchTargets targets) {
        return new DatabaseQueryTool(new JdbcExecutor(), targets, () -> 10002L);
    }

    static SearchTarget.SearchTargets wholeKb(String kb) {
        return new SearchTarget.SearchTargets(List.of(SearchTarget.wholeKb(kb, 10002)));
    }

    // ==================== 用例 ====================

    @Test
    void schemaContract() {
        // 探针 _schema 语料：Go utils.GenerateSchema[DatabaseQueryInput]() 的字节。
        JsonNode schema = RecordingSupport.readTree(rec("database_query_schema").get("schema").asText());
        assertThat(tool(wholeKb("aa45bq01")).getParameters()).isEqualTo(schema);
    }

    @Test
    void basicSelect() {
        assertToolResult("basic_select",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_basic_select"))),
                rec("database_query_basic_select"));
    }

    @Test
    void aliasWhere() {
        assertToolResult("alias_where",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_alias_where"))),
                rec("database_query_alias_where"));
    }

    @Test
    void kbTable() {
        assertToolResult("kb_table",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_kb_table"))),
                rec("database_query_kb_table"));
    }

    @Test
    void joinCount() {
        assertToolResult("join_count",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_join_count"))),
                rec("database_query_join_count"));
    }

    @Test
    void aggregates() {
        assertToolResult("aggregates",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_aggregates"))),
                rec("database_query_aggregates"));
    }

    @Test
    void likePattern() {
        assertToolResult("like_pattern",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_like_pattern"))),
                rec("database_query_like_pattern"));
    }

    @Test
    void functionUpper() {
        assertToolResult("function_upper",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_function_upper"))),
                rec("database_query_function_upper"));
    }

    @Test
    void tenantIsolation() {
        // 结果集必须排除 tenant=999 的 aa45bqk9（tenant 注入生效的证据）。
        assertToolResult("tenant_isolation",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_tenant_isolation"))),
                rec("database_query_tenant_isolation"));
    }

    @Test
    void chunkFilters() {
        // 结果集必须排除 is_enabled=false / deleted_at 非空 / 跨租户的三行。
        assertToolResult("chunk_filters",
                tool(wholeKb("aa45bq01")).execute(req(rec("database_query_chunk_filters"))),
                rec("database_query_chunk_filters"));
    }

    @Test
    void knowledgeScope() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE, "aa45bq01", 10002,
                        List.of("aa45bqk1", "aa45bqk2"), null, null, false)));
        assertToolResult("knowledge_scope",
                tool(targets).execute(req(rec("database_query_knowledge_scope"))),
                rec("database_query_knowledge_scope"));
    }

    @Test
    void tagScope() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE, "aa45bq01", 10002, null,
                        List.of("aa45bqt1"), null, false)));
        assertToolResult("tag_scope",
                tool(targets).execute(req(rec("database_query_tag_scope"))),
                rec("database_query_tag_scope"));
    }

    @Test
    void multiKbScope() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb("aa45bq01", 10002), SearchTarget.wholeKb("aa45bq02", 10002)));
        assertToolResult("multi_kb_scope",
                tool(targets).execute(req(rec("database_query_multi_kb_scope"))),
                rec("database_query_multi_kb_scope"));
    }

    @Test
    void emptyScope() {
        assertToolResult("empty_scope",
                tool(null).execute(req(rec("database_query_empty_scope"))),
                rec("database_query_empty_scope"));
    }

    @Test
    void validationErrors() {
        // 校验失败语料共用 whole-KB scope，逐条回放（错误文案逐字对照 Go）。
        String[] ids = {
                "not_select", "multiple_statements", "table_not_allowed",
                "injection_or_1or1", "injection_always_false", "input_too_short",
                "parse_error", "no_from", "cte", "subquery", "compound",
                "schema_denied", "system_column", "dangerous_function",
                "whitelist_reject", "cast_pg_type",
        };
        DatabaseQueryTool tool = tool(wholeKb("aa45bq01"));
        for (String id : ids) {
            JsonNode r = rec("database_query_" + id);
            assertToolResult(id, tool.execute(req(r)), r);
        }
    }
}
