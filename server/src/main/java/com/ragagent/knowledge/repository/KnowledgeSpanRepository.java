package com.ragagent.knowledge.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.CleanInvalidUtf8;
import com.ragagent.knowledge.domain.KnowledgeProcessingSpan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.stereotype.Repository;
import com.ragagent.common.jdbc.DatabaseDialects;

/**
 * * 每次尝试一棵 span 树的持久化。
 * <ul>
 *   <li>{@code upsert} 覆盖 Begin/End/Fail/Skip 的全部状态迁移（单写路径保证行内一致）；
 *       input/output/metadata 是**内容列**——仅当入参非 null 时写入（EndSpan 只写
 *       output，若总写 input 会把 Begin 写的 input 冲成 NULL）；</li>
 *   <li>{@code nextAttempt} 为重新解析分配新 attempt，历史行保持可查；</li>
 *   <li>{@code listByAttempt} 是唯一读路径（handler 在内存里建树，不递归查库）；</li>
 *   <li>cancel 族（descendants/openSpansByName/openSpans）为级联取消与被取消路径服务。</li>
 * </ul>
 * <p><b>方言</b>：PG 用 {@code ON CONFLICT ... DO UPDATE SET <动态列>}；H2（契约测试）
 * 退化为「先查后写」——H2 的 MERGE 是全列覆盖，无法表达「不写 NULL 列」的语义
 * （同 {@code VectorStoreService} 的 H2 分支取舍）。jsonb 列在 PG 用
 * {@code setObject(Types.OTHER)}、H2 用 {@code setString}（本仓约定的 PG/H2 方言分支）。</p>
 */
@Repository
public class KnowledgeSpanRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private static final List<String> BASE_UPDATE_COLS = List.of(
            "status", "error_code", "error_message", "error_detail",
            "started_at", "finished_at", "duration_ms", "updated_at");

    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public KnowledgeSpanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgres = DatabaseDialects.isPostgres(jdbc);
    }

    public void upsert(KnowledgeProcessingSpan row) {
        if (row == null || row.getKnowledgeId().isEmpty() || row.getSpanId().isEmpty()) {
            throw new IllegalArgumentException(
                    "knowledgeSpanRepository.Upsert: knowledge_id and span_id required");
        }
        if (row.getAttempt() == 0) {
            row.setAttempt(1);
        }
        row.setErrorCode(CleanInvalidUtf8.clean(row.getErrorCode()));
        row.setErrorMessage(CleanInvalidUtf8.clean(row.getErrorMessage()));
        row.setErrorDetail(CleanInvalidUtf8.clean(row.getErrorDetail()));

        List<String> cols = new ArrayList<>(BASE_UPDATE_COLS);
        if (row.getInput() != null) {
            cols.add("input");
        }
        if (row.getOutput() != null) {
            cols.add("output");
        }
        if (row.getMetadata() != null) {
            cols.add("metadata");
        }

        if (postgres) {
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO knowledge_processing_spans (knowledge_id, attempt, span_id,"
                            + " parent_span_id, name, kind, status, input, output, metadata,"
                            + " error_code, error_message, error_detail, started_at, finished_at,"
                            + " duration_ms, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
                            + " ON CONFLICT (knowledge_id, attempt, span_id) DO UPDATE SET ");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(cols.get(i)).append(" = EXCLUDED.").append(cols.get(i));
            }
            jdbc.update(new PreparedStatementCreator() {
                @Override
                public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
                    PreparedStatement ps = con.prepareStatement(sql.toString());
                    bindRow(ps, row, true);
                    return ps;
                }
            });
            return;
        }

        // H2：先查存在性 → 有则动态列 UPDATE，无则 INSERT（MERGE 全列覆盖不可用）
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_processing_spans"
                        + " WHERE knowledge_id = ? AND attempt = ? AND span_id = ?",
                Integer.class, row.getKnowledgeId(), row.getAttempt(), row.getSpanId());
        if (existing != null && existing > 0) {
            StringBuilder sql = new StringBuilder("UPDATE knowledge_processing_spans SET ");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(cols.get(i)).append(" = ?");
            }
            sql.append(" WHERE knowledge_id = ? AND attempt = ? AND span_id = ?");
            final String updateSql = sql.toString();
            jdbc.update(new PreparedStatementCreator() {
                @Override
                public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
                    PreparedStatement ps = con.prepareStatement(updateSql);
                    int i = 1;
                    for (String col : cols) {
                        bindContent(ps, i++, col, row, false);
                    }
                    ps.setString(i++, row.getKnowledgeId());
                    ps.setInt(i++, row.getAttempt());
                    ps.setString(i, row.getSpanId());
                    return ps;
                }
            });
            return;
        }
        jdbc.update(new PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO knowledge_processing_spans (knowledge_id, attempt, span_id,"
                                + " parent_span_id, name, kind, status, input, output, metadata,"
                                + " error_code, error_message, error_detail, started_at, finished_at,"
                                + " duration_ms, created_at, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                bindRow(ps, row, false);
                return ps;
            }
        });
    }

    /** INSERT 的完整绑定（postgres=true 时 jsonb 用 Types.OTHER）。 */
    private void bindRow(PreparedStatement ps, KnowledgeProcessingSpan row, boolean postgresJson)
            throws SQLException {
        int i = 1;
        ps.setString(i++, row.getKnowledgeId());
        ps.setInt(i++, row.getAttempt());
        ps.setString(i++, row.getSpanId());
        ps.setString(i++, row.getParentSpanId().isEmpty() ? null : row.getParentSpanId());
        ps.setString(i++, row.getName());
        ps.setString(i++, row.getKind());
        ps.setString(i++, row.getStatus());
        bindJson(ps, i++, row.getInput(), postgresJson);
        bindJson(ps, i++, row.getOutput(), postgresJson);
        bindJson(ps, i++, row.getMetadata(), postgresJson);
        ps.setString(i++, nullIfEmpty(row.getErrorCode()));
        ps.setString(i++, nullIfEmpty(row.getErrorMessage()));
        ps.setString(i++, nullIfEmpty(row.getErrorDetail()));
        bindTimestamp(ps, i++, row.getStartedAt());
        bindTimestamp(ps, i++, row.getFinishedAt());
        ps.setLong(i, row.getDurationMs());
    }

    /** UPDATE 路径的单列绑定。 */
    private void bindContent(PreparedStatement ps, int idx, String col,
                             KnowledgeProcessingSpan row, boolean postgresJson) throws SQLException {
        switch (col) {
            case "input" -> bindJson(ps, idx, row.getInput(), postgresJson);
            case "output" -> bindJson(ps, idx, row.getOutput(), postgresJson);
            case "metadata" -> bindJson(ps, idx, row.getMetadata(), postgresJson);
            case "started_at" -> bindTimestamp(ps, idx, row.getStartedAt());
            case "finished_at" -> bindTimestamp(ps, idx, row.getFinishedAt());
            case "duration_ms" -> ps.setLong(idx, row.getDurationMs());
            case "updated_at" -> ps.setTimestamp(idx,
                    Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant()));
            case "error_code" -> ps.setString(idx, nullIfEmpty(row.getErrorCode()));
            case "error_message" -> ps.setString(idx, nullIfEmpty(row.getErrorMessage()));
            case "error_detail" -> ps.setString(idx, nullIfEmpty(row.getErrorDetail()));
            case "status" -> ps.setString(idx, row.getStatus());
            default -> ps.setObject(idx, null);
        }
    }

    private void bindJson(PreparedStatement ps, int idx, Map<String, Object> value,
                          boolean postgresJson) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.NULL);
            return;
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new SQLException("serialize span json: " + e.getMessage(), e);
        }
        if (postgresJson) {
            ps.setObject(idx, json, Types.OTHER);
        } else {
            ps.setString(idx, json);
        }
    }

    private static void bindTimestamp(PreparedStatement ps, int idx, OffsetDateTime value)
            throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.TIMESTAMP_WITH_TIMEZONE);
            return;
        }
        ps.setTimestamp(idx, Timestamp.from(value.toInstant()));
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** MAX(attempt)+1。 */
    public int nextAttempt(String knowledgeId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt), 0) FROM knowledge_processing_spans"
                        + " WHERE knowledge_id = ?",
                Integer.class, knowledgeId);
        return (max == null ? 0 : max) + 1;
    }
    public int latestAttempt(String knowledgeId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt), 0) FROM knowledge_processing_spans"
                        + " WHERE knowledge_id = ?",
                Integer.class, knowledgeId);
        return max == null ? 0 : max;
    }

    /** id ASC 保插入序（fan-out 子 span 的稳定渲染序）。 */
    public List<KnowledgeProcessingSpan> listByAttempt(String knowledgeId, int attempt) {
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM knowledge_processing_spans WHERE knowledge_id = ?"
                + (attempt > 0 ? " AND attempt = ?" : "") + " ORDER BY id ASC";
        List<Object> args = new ArrayList<>();
        args.add(knowledgeId);
        if (attempt > 0) {
            args.add(attempt);
        }
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs), args.toArray());
    }

    /** 不存在返回 null。 */
    public KnowledgeProcessingSpan getSpan(String knowledgeId, int attempt, String spanId) {
        List<KnowledgeProcessingSpan> rows = jdbc.query(
                "SELECT * FROM knowledge_processing_spans"
                        + " WHERE knowledge_id = ? AND attempt = ? AND span_id = ?",
                (rs, rowNum) -> mapRow(rs), knowledgeId, attempt, spanId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 逐层 BFS（每层把 frontier 的 pending/running
     * 子行翻 cancelled），固定点或 16 层深度上限退出；终态行保持原样。
     */
    public long cancelDescendants(String knowledgeId, int attempt, String parentSpanId,
                                  String reason) {
        String cleanReason = CleanInvalidUtf8.clean(reason);
        List<String> frontier = new ArrayList<>(List.of(parentSpanId));
        long totalAffected = 0;
        for (int depth = 0; depth < 16 && !frontier.isEmpty(); depth++) {
            List<String> placeholders = new ArrayList<>(
                    Collections.nCopies(frontier.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(knowledgeId);
            args.add(attempt);
            args.addAll(frontier);
            args.add(KnowledgeProcessingSpan.STATUS_PENDING);
            args.add(KnowledgeProcessingSpan.STATUS_RUNNING);
            List<KnowledgeProcessingSpan> children = jdbc.query(
                    "SELECT * FROM knowledge_processing_spans WHERE knowledge_id = ?"
                            + " AND attempt = ? AND parent_span_id IN ("
                            + String.join(",", placeholders) + ")"
                            + " AND status IN (?, ?)",
                    (rs, rowNum) -> mapRow(rs), args.toArray());
            if (children.isEmpty()) {
                break;
            }
            List<String> ids = new ArrayList<>(children.size());
            List<String> nextFrontier = new ArrayList<>(children.size());
            for (KnowledgeProcessingSpan c : children) {
                ids.add(c.getSpanId());
                nextFrontier.add(c.getSpanId());
            }
            List<String> idPlaceholders = new ArrayList<>(Collections.nCopies(ids.size(), "?"));
            List<Object> updateArgs = new ArrayList<>();
            updateArgs.add(KnowledgeProcessingSpan.STATUS_CANCELLED);
            updateArgs.add("UPSTREAM_FAILED");
            updateArgs.add(cleanReason);
            updateArgs.add(knowledgeId);
            updateArgs.add(attempt);
            updateArgs.addAll(ids);
            int affected = jdbc.update(
                    "UPDATE knowledge_processing_spans SET status = ?, error_code = ?,"
                            + " error_message = ? WHERE knowledge_id = ? AND attempt = ?"
                            + " AND span_id IN (" + String.join(",", idPlaceholders) + ")",
                    updateArgs.toArray());
            totalAffected += affected;
            frontier = nextFrontier;
        }
        return totalAffected;
    }

    /** 不设 finished_at/duration_ms（保持可观察）。 */
    public long cancelAllOpenSpans(String knowledgeId, int attempt, String errorCode,
                                   String reason) {
        String cleanCode = CleanInvalidUtf8.clean(errorCode);
        String cleanReason = CleanInvalidUtf8.clean(reason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return jdbc.update(
                "UPDATE knowledge_processing_spans SET status = ?, error_code = ?,"
                        + " error_message = ?, finished_at = ?, updated_at = ?"
                        + " WHERE knowledge_id = ? AND attempt = ? AND status IN (?, ?)",
                KnowledgeProcessingSpan.STATUS_CANCELLED, cleanCode, cleanReason,
                Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()),
                knowledgeId, attempt,
                KnowledgeProcessingSpan.STATUS_PENDING,
                KnowledgeProcessingSpan.STATUS_RUNNING);
    }

    /** 重开同名子 span 前清掉残留的 pending/running 行。 */
    public long cancelOpenSpansByName(String knowledgeId, int attempt, String name,
                                      String errorCode, String reason) {
        if (knowledgeId == null || knowledgeId.isEmpty() || attempt <= 0
                || name == null || name.isEmpty()) {
            return 0;
        }
        String cleanCode = CleanInvalidUtf8.clean(errorCode);
        String cleanReason = CleanInvalidUtf8.clean(reason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return jdbc.update(
                "UPDATE knowledge_processing_spans SET status = ?, error_code = ?,"
                        + " error_message = ?, finished_at = ?, updated_at = ?"
                        + " WHERE knowledge_id = ? AND attempt = ? AND name = ?"
                        + " AND status IN (?, ?)",
                KnowledgeProcessingSpan.STATUS_CANCELLED, cleanCode, cleanReason,
                Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()),
                knowledgeId, attempt, name,
                KnowledgeProcessingSpan.STATUS_PENDING,
                KnowledgeProcessingSpan.STATUS_RUNNING);
    }

    private KnowledgeProcessingSpan mapRow(ResultSet rs) throws SQLException {
        KnowledgeProcessingSpan row = new KnowledgeProcessingSpan();
        row.setId(rs.getLong("id"));
        row.setKnowledgeId(rs.getString("knowledge_id"));
        row.setAttempt(rs.getInt("attempt"));
        row.setSpanId(rs.getString("span_id"));
        row.setParentSpanId(rs.getString("parent_span_id"));
        row.setName(rs.getString("name"));
        row.setKind(rs.getString("kind"));
        row.setStatus(rs.getString("status"));
        row.setInput(readJson(rs.getString("input")));
        row.setOutput(readJson(rs.getString("output")));
        row.setMetadata(readJson(rs.getString("metadata")));
        row.setErrorCode(rs.getString("error_code"));
        row.setErrorMessage(rs.getString("error_message"));
        row.setErrorDetail(rs.getString("error_detail"));
        row.setStartedAt(readTimestamp(rs.getTimestamp("started_at")));
        row.setFinishedAt(readTimestamp(rs.getTimestamp("finished_at")));
        row.setDurationMs(rs.getLong("duration_ms"));
        row.setCreatedAt(readTimestamp(rs.getTimestamp("created_at")));
        row.setUpdatedAt(readTimestamp(rs.getTimestamp("updated_at")));
        return row;
    }

    private static Map<String, Object> readJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, MAP_TYPE);
            return parsed == null || parsed.isEmpty() ? parsed : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    private static OffsetDateTime readTimestamp(Timestamp ts) {
        return ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
    }
}
