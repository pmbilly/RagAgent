package com.ragagent.agent.tools.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.agent.tools.knowledge.GrepChunksTool.GrepChunkSearch;
import com.ragagent.agent.tools.knowledge.GrepChunksTool.GrepChunkView;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.agent.tools.ToolRequest;
import com.ragagent.agent.tools.GoRecording45B;
import com.ragagent.agent.tools.RecordingSupport;

/**
 * 波 4.5b 回放：grep_chunks（对照 Go 实录，探针 {@code zz_rec45b_grep_test.go}）。
 *
 * <p>与 Go 探针连同一 dev PG（localhost:15432，租户 10002）：本测试自行种同一份种子
 * （id 前缀 aa45b），经 {@link JdbcGrepSearch} 真跑与 Go gorm 相同的 SQL
 * （~* 正则、scopeClause OR、created_at DESC LIMIT 500、COUNT(*) 回填）。
 * dev PG 不可达时测试显式失败（不允许跳过）。</p>
 */
class GrepChunksRecordingTest {

    static final String KB_A = "aa45ba01";
    static final String KB_B = "aa45ba02";
    static final String KB_C = "aa45ba03";

    static Connection db;

    @BeforeAll
    static void seed() throws Exception {
        String url = System.getenv().getOrDefault("ZZ_PG_JDBC",
                "jdbc:postgresql://localhost:15432/WeKnora");
        String user = System.getenv().getOrDefault("ZZ_PG_USER", "postgres");
        String pass = System.getenv().getOrDefault("ZZ_PG_PASS", "postgres123!@#");
        db = DriverManager.getConnection(url, user, pass);
        try (var st = db.createStatement()) {
            st.execute("DELETE FROM knowledge_tag_relations WHERE knowledge_id LIKE 'aa45b%' OR tag_id LIKE 'aa45b%'");
            st.execute("DELETE FROM chunks WHERE id LIKE 'aa45b%'");
            st.execute("DELETE FROM knowledges WHERE id LIKE 'aa45b%'");
        }
        String[][] knowledges = {
                {"aa45bb01", KB_A, "Stardust Engine Guide"},
                {"aa45bb02", KB_A, "Skyvault Fuel Manual"},
                {"aa45bb03", KB_A, "Psionic FAQ"},
                {"aa45bb04", KB_A, "Duplicate Doc"},
                {"aa45bb05", KB_A, ""},
                {"aa45bb06", KB_B, "Tagged Manual"},
                {"aa45bb07", KB_B, "Untagged Doc"},
                {"aa45bb08", KB_C, "MMR Corpus"},
        };
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source) VALUES (?, 10002, ?, 'file', ?, 'upload')")) {
            for (String[] k : knowledges) {
                ps.setString(1, k[0]);
                ps.setString(2, k[1]);
                ps.setString(3, k[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        List<String[]> chunks = new ArrayList<>();
        chunks.add(new String[]{"aa45bc01", "aa45bb01", KB_A, "0",
                "The stardust engine uses a psionic core. stardust appears twice here.", "t", "", "", "", "1"});
        chunks.add(new String[]{"aa45bc02", "aa45bb01", KB_A, "1",
                "Stardust engines require skyvault fuel and regular maintenance.", "t", "", "", "", "2"});
        chunks.add(new String[]{"aa45bc13", "aa45bb01", KB_A, "2",
                "no keyword here at all", "t", "", "", "", "3"});
        chunks.add(new String[]{"aa45bc10", "aa45bb01", KB_A, "3",
                "stardust disabled chunk", "f", "", "", "", "4"});
        chunks.add(new String[]{"aa45bc03", "aa45bb02", KB_A, "0",
                "skyvault fuel is refined from psionic crystals.", "t", "", "", "", "5"});
        chunks.add(new String[]{"aa45bc04", "aa45bb02", KB_A, "1",
                "unrelated content about gardening tools", "t", "", "", "", "6"});
        chunks.add(new String[]{"aa45bc05", "aa45bb03", KB_A, "0",
                "How do I tune the psionic emitter?", "t", "faq",
                "{\"standardQuestion\":\"How do I tune the psionic emitter?\","
                        + "\"similarQuestions\":[\"psionic emitter tuning help\",\"emitter FAQ\"],"
                        + "\"answers\":[\"Turn the knob clockwise.\",\"Check the skyvault manual.\"]}",
                "", "7"});
        chunks.add(new String[]{"aa45bc06", "aa45bb04", KB_A, "0",
                "  The STARDUST Engine  ", "t", "", "", "aa45be01", "8"});
        chunks.add(new String[]{"aa45bc07", "aa45bb04", KB_A, "1",
                "the stardust engine", "t", "", "", "aa45be01", "9"});
        chunks.add(new String[]{"aa45bc08", "aa45bb04", KB_A, "2",
                "the stardust engine", "t", "", "", "", "10"});
        chunks.add(new String[]{"aa45bc09", "aa45bb05", KB_A, "0",
                "psionic crystal storage notes", "t", "", "", "", "11"});
        chunks.add(new String[]{"aa45bc11", "aa45bb06", KB_B, "0",
                "rag agent deployment notes stardust", "t", "", "", "", "12"});
        chunks.add(new String[]{"aa45bc12", "aa45bb07", KB_B, "0",
                "rag agent untagged stardust", "t", "", "", "", "13"});
        for (int i = 0; i < 35; i++) {
            chunks.add(new String[]{String.format("aa45bc%02d", 20 + i), "aa45bb08", KB_C,
                    String.valueOf(i), String.format("engine filler word%02d", i), "t", "", "", "",
                    String.valueOf(20 + i)});
        }
        Instant base = Instant.parse("2024-06-01T00:00:00Z");
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO chunks (id, tenant_id, knowledge_base_id, knowledge_id, content, chunk_index,"
                        + " is_enabled, start_at, end_at, chunk_type, metadata, parent_chunk_id, created_at)"
                        + " VALUES (?, 10002, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)")) {
            for (String[] c : chunks) {
                int idx = Integer.parseInt(c[3]);
                ps.setString(1, c[0]);
                ps.setString(2, c[2]);
                ps.setString(3, c[1]);
                ps.setString(4, c[4]);
                ps.setInt(5, idx);
                ps.setBoolean(6, "t".equals(c[5]));
                ps.setInt(7, idx * 100);
                ps.setInt(8, idx * 100 + 99);
                ps.setString(9, c[6].isEmpty() ? "text" : c[6]);
                if (c[7].isEmpty()) {
                    ps.setObject(10, null);
                } else {
                    ps.setString(10, c[7]);
                }
                if (c[8].isEmpty()) {
                    ps.setObject(11, null);
                } else {
                    ps.setString(11, c[8]);
                }
                ps.setTimestamp(12, Timestamp.from(base.plusSeconds(Long.parseLong(c[9]))));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (var st = db.createStatement()) {
            st.execute("INSERT INTO knowledge_tag_relations (knowledge_id, tag_id) VALUES ('aa45bb06', 'aa45bd01')");
        }
    }

    // ==================== JDBC seam（对照 Go gorm searchChunks 的同构 SQL） ====================

    /**
     * 对照 Go {@code searchChunks}：同样的 scopeClause OR 组合、
     * {@code (content ~* ? OR knowledges.title ~* ?)}、created_at DESC LIMIT 500、
     * COUNT(*) 回填 totalChunkCount。无有效 scope 返回空表。
     */
    static final class JdbcGrepSearch implements GrepChunkSearch {
        @Override
        public List<GrepChunkView> search(List<String> queries, List<String> fullKbIDs,
                List<String> knowledgeIDs, List<SearchTarget> tagTargets, Map<String, Long> kbTenantMap) {
            if (fullKbIDs.isEmpty() && knowledgeIDs.isEmpty() && tagTargets.isEmpty()) {
                return List.of();
            }
            List<String> clauses = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            if (!knowledgeIDs.isEmpty()) {
                clauses.add("chunks.knowledge_id IN (" + placeholders(knowledgeIDs.size()) + ")");
                args.addAll(knowledgeIDs);
            }
            for (SearchTarget t : tagTargets) {
                if (t == null || t.knowledgeBaseId() == null || t.knowledgeBaseId().isEmpty()
                        || t.tagIds() == null || t.tagIds().isEmpty()) {
                    continue;
                }
                long tenantID = t.tenantId();
                if (tenantID == 0) {
                    Long mapped = kbTenantMap.get(t.knowledgeBaseId());
                    tenantID = mapped == null ? 0 : mapped;
                }
                if (tenantID == 0) {
                    continue;
                }
                clauses.add("(chunks.knowledge_base_id = ? AND chunks.tenant_id = ? AND EXISTS ("
                        + "SELECT 1 FROM knowledge_tag_relations ktr "
                        + "WHERE ktr.knowledge_id = chunks.knowledge_id AND ktr.tag_id IN ("
                        + placeholders(t.tagIds().size()) + ")))");
                args.add(t.knowledgeBaseId());
                args.add(tenantID);
                args.addAll(t.tagIds());
            }
            for (String kbID : fullKbIDs) {
                long tenantID = kbTenantMap.getOrDefault(kbID, 0L);
                if (tenantID == 0) {
                    continue;
                }
                clauses.add("(chunks.knowledge_base_id = ? AND chunks.tenant_id = ?)");
                args.add(kbID);
                args.add(tenantID);
            }
            if (clauses.isEmpty()) {
                return List.of();
            }

            StringBuilder sql = new StringBuilder(
                    "SELECT chunks.id, chunks.content, chunks.chunk_index, chunks.knowledge_id,"
                            + " chunks.knowledge_base_id, chunks.chunk_type, chunks.metadata,"
                            + " knowledges.title AS knowledge_title"
                            + " FROM chunks JOIN knowledges ON chunks.knowledge_id = knowledges.id"
                            + " WHERE chunks.is_enabled = ? AND chunks.deleted_at IS NULL"
                            + " AND knowledges.deleted_at IS NULL AND (");
            sql.append(String.join(" OR ", clauses)).append(") AND (");
            List<String> regexConds = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                regexConds.add("(chunks.content ~* ? OR knowledges.title ~* ?)");
                args.add(queries.get(i));
                args.add(queries.get(i));
            }
            sql.append(String.join(" OR ", regexConds)).append(")");
            sql.append(" ORDER BY chunks.created_at DESC LIMIT 500");

            List<GrepChunkView> out = new ArrayList<>();
            Map<String, Integer> countMap = new LinkedHashMap<>();
            try (PreparedStatement ps = db.prepareStatement(sql.toString())) {
                int slot = 1;
                ps.setBoolean(slot++, true);
                for (Object a : args) {
                    if (a instanceof String s) {
                        ps.setString(slot++, s);
                    } else if (a instanceof Long l) {
                        ps.setLong(slot++, l);
                    } else {
                        ps.setObject(slot++, a);
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        GrepChunkView v = new GrepChunkView();
                        v.id = rs.getString(1);
                        v.content = rs.getString(2);
                        v.chunkIndex = rs.getInt(3);
                        v.knowledgeId = rs.getString(4);
                        v.knowledgeBaseId = rs.getString(5);
                        v.chunkType = rs.getString(6);
                        String meta = rs.getString(7);
                        if (meta != null) {
                            v.metadata = RecordingSupport.PLAIN.readTree(meta);
                        }
                        v.knowledgeTitle = rs.getString(8);
                        out.add(v);
                    }
                }
            } catch (java.sql.SQLException | java.io.IOException e) {
                throw new RuntimeException(e);
            }

            if (!out.isEmpty()) {
                List<String> kids = new ArrayList<>();
                for (GrepChunkView v : out) {
                    if (v.knowledgeId != null && !v.knowledgeId.isEmpty() && !kids.contains(v.knowledgeId)) {
                        kids.add(v.knowledgeId);
                    }
                }
                try (PreparedStatement ps = db.prepareStatement(
                        "SELECT knowledge_id, COUNT(*) AS cnt FROM chunks"
                                + " WHERE knowledge_id IN (" + placeholders(kids.size()) + ")"
                                + " AND is_enabled = ? AND deleted_at IS NULL GROUP BY knowledge_id")) {
                    int slot = 1;
                    for (String kid : kids) {
                        ps.setString(slot++, kid);
                    }
                    ps.setBoolean(slot, true);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            countMap.put(rs.getString(1), rs.getInt(2));
                        }
                    }
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
                for (GrepChunkView v : out) {
                    v.totalChunkCount = countMap.getOrDefault(v.knowledgeId == null ? "" : v.knowledgeId, 0);
                }
            }
            return out;
        }

        static String placeholders(int n) {
            return String.join(", ", java.util.Collections.nCopies(n, "?"));
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

    private static ToolRequest req(String argsJson) {
        try {
            return ToolRequest.of(RecordingSupport.PLAIN.readTree(argsJson));
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

    static SearchTarget.SearchTargets wholeKb(String kb) {
        return new SearchTarget.SearchTargets(List.of(SearchTarget.wholeKb(kb, 10002)));
    }

    // ==================== 用例 ====================

    @Test
    void basic() {
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), wholeKb(KB_A));
        assertToolResult("basic", tool.execute(req("{\"query\":\"stardust\"}")), rec("grep_chunks_basic"));
    }

    @Test
    void alternation() {
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), wholeKb(KB_A));
        assertToolResult("alternation", tool.execute(req("{\"query\":\"stardust|psionic\"}")),
                rec("grep_chunks_alternation"));
    }

    @Test
    void alreadySeen() {
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), wholeKb(KB_A));
        assertToolResult("already_seen_first", tool.execute(req("{\"query\":\"psionic\"}")),
                rec("grep_chunks_already_seen_first"));
        assertToolResult("already_seen_second", tool.execute(req("{\"query\":\"psionic\"}")),
                rec("grep_chunks_already_seen_second"));
    }

    @Test
    void emptyResult() {
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), wholeKb(KB_A));
        assertToolResult("empty_result", tool.execute(req("{\"query\":\"zzzznotfound\"}")),
                rec("grep_chunks_empty_result"));
    }

    @Test
    void emptyQuery() {
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), wholeKb(KB_A));
        assertToolResult("empty_query", tool.execute(req("{\"query\":\"   \"}")),
                rec("grep_chunks_empty_query"));
    }

    @Test
    void noScope() {
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), null);
        assertToolResult("no_scope", tool.execute(req("{\"query\":\"stardust\"}")),
                rec("grep_chunks_no_scope"));
    }

    @Test
    void tagScope() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE, KB_B, 10002, null,
                        List.of("aa45bd01"), null, false)));
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), targets);
        assertToolResult("tag_scope", tool.execute(req("{\"query\":\"rag\"}")),
                rec("grep_chunks_tag_scope"));
    }

    @Test
    void knowledgeScope() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE, KB_B, 10002,
                        List.of("aa45bb07"), null, null, false)));
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), targets);
        assertToolResult("knowledge_scope", tool.execute(req("{\"query\":\"stardust\"}")),
                rec("grep_chunks_knowledge_scope"));
    }

    @Test
    void mixedScope() {
        SearchTarget.SearchTargets targets = new SearchTarget.SearchTargets(List.of(
                SearchTarget.wholeKb(KB_A, 10002),
                new SearchTarget(SearchTarget.TYPE_KNOWLEDGE_BASE, KB_B, 10002, null,
                        List.of("aa45bd01"), null, false)));
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), targets);
        assertToolResult("mixed_scope", tool.execute(req("{\"query\":\"stardust\"}")),
                rec("grep_chunks_mixed_scope"));
    }

    @Test
    void mmrSelect() {
        GrepChunksTool tool = new GrepChunksTool(new JdbcGrepSearch(), wholeKb(KB_C));
        assertToolResult("mmr_select", tool.execute(req("{\"query\":\"engine\"}")),
                rec("grep_chunks_mmr_select"));
    }
}
