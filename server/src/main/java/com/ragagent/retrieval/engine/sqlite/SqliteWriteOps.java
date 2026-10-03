package com.ragagent.retrieval.engine.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite 引擎的写入/删除/批量更新/拷贝簇：INSERT OR IGNORE + FTS5 同步 + 向量行、
 * 三种按列删除（先查行再级联删向量/FTS）、逐 chunk 元数据批量更新、跨 KB 拷贝（新 UUID SourceID）。
 */
final class SqliteWriteOps {

    private static final Logger log = LoggerFactory.getLogger(SqliteWriteOps.class);

    private final SqliteRetrieveRepository service;

    SqliteWriteOps(SqliteRetrieveRepository service) {
        this.service = service;
    }

    // ── 写入（INSERT OR IGNORE + FTS + 向量） ──────────────────────────────

    void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        batchSave(List.of(indexInfo), params);
    }

    void batchSave(List<IndexInfo> indexInfoList, Map<String, Object> params)
            throws Exception {
        if (indexInfoList == null || indexInfoList.isEmpty()) {
            return;
        }
        try (Connection conn = service.open()) {
            conn.setAutoCommit(false);
            try {
                List<long[]> newIds = new ArrayList<>();
                List<float[]> vectors = new ArrayList<>();
                for (IndexInfo info : indexInfoList) {
                    float[] emb = extractEmbedding(params, info.sourceId);
                    Row row = toRow(info, emb.length);
                    long id = insertIgnore(conn, row);
                    if (id > 0) {
                        syncFtsInsert(conn, id, row);
                        newIds.add(new long[] {id, emb.length});
                        vectors.add(emb);
                    }
                }
                for (int i = 0; i < newIds.size(); i++) {
                    long id = newIds.get(i)[0];
                    int dim = (int) newIds.get(i)[1];
                    float[] emb = vectors.get(i);
                    if (dim > 0 && id > 0) {
                        insertVec(conn, id, dim, emb);
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** 行模型。 */
    static final class Row {

        long id;
        String sourceId = "";
        int sourceType;
        String chunkId = "";
        String knowledgeId = "";
        String knowledgeBaseId = "";
        String tagId = "";
        String content = "";
        int dimension;
        boolean isEnabled = true;
    }

    /** content 过 CleanInvalidUTF8；is_enabled 恒有值。 */
    static Row toRow(IndexInfo info, int dimension) {
        Row row = new Row();
        row.sourceId = info.sourceId == null ? "" : info.sourceId;
        row.sourceType = info.sourceType;
        row.chunkId = info.chunkId == null ? "" : info.chunkId;
        row.knowledgeId = info.knowledgeId == null ? "" : info.knowledgeId;
        row.knowledgeBaseId = info.knowledgeBaseId == null ? "" : info.knowledgeBaseId;
        row.tagId = info.tagId == null ? "" : info.tagId;
        row.content = SqliteRetrieveRepository.cleanInvalidUtf8(info.content);
        row.dimension = dimension;
        row.isEnabled = info.isEnabled;
        return row;
    }

    /** params["embedding"] 是 sourceID→向量的表。 */
    static float[] extractEmbedding(Map<String, Object> params, String sourceId) {
        if (params == null) {
            return new float[0];
        }
        Object raw = params.get("embedding");
        if (!(raw instanceof Map<?, ?> map)) {
            return new float[0];
        }
        Object value = map.get(sourceId);
        if (value instanceof float[] f) {
            return f;
        }
        if (value instanceof List<?> list) {
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = list.get(i) instanceof Number n ? n.floatValue() : 0f;
            }
            return out;
        }
        return new float[0];
    }

    /** INSERT OR IGNORE 语义：唯一索引冲突 → 忽略并返回 0。 */
    long insertIgnore(Connection conn, Row row) throws SQLException {
        String sql = "INSERT OR IGNORE INTO " + SqliteRetrieveRepository.TABLE_EMBEDDINGS + "(created_at, updated_at,"
                + " source_id, source_type, chunk_id, knowledge_id, knowledge_base_id, tag_id,"
                + " content, dimension, is_enabled) VALUES(datetime('now'), datetime('now'),"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setRowParams(ps, row);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                return 0;
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return 0;
    }


    static void setRowParams(PreparedStatement ps, Row row) throws SQLException {
        ps.setString(1, row.sourceId);
        ps.setInt(2, row.sourceType);
        ps.setString(3, row.chunkId);
        ps.setString(4, row.knowledgeId);
        ps.setString(5, row.knowledgeBaseId);
        ps.setString(6, row.tagId);
        ps.setString(7, row.content);
        ps.setInt(8, row.dimension);
        ps.setInt(9, row.isEnabled ? 1 : 0);
    }

    /** 内容先过二元切分再进 FTS5。 */
    void syncFtsInsert(Connection conn, long id, Row row) throws SQLException {
        if (id == 0) {
            return;
        }
        String tokenized = SqliteCjkBigram.tokenize(row.content);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + SqliteRetrieveRepository.TABLE_FTS
                + "(rowid, content, source_id, chunk_id, knowledge_id, knowledge_base_id)"
                + " VALUES(?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, id);
            ps.setString(2, tokenized);
            ps.setString(3, row.sourceId);
            ps.setString(4, row.chunkId);
            ps.setString(5, row.knowledgeId);
            ps.setString(6, row.knowledgeBaseId);
            ps.executeUpdate();
        }
    }


    void insertVec(Connection conn, long rowId, int dim, float[] emb) throws SQLException {
        service.ensureVecTable(conn, dim);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + SqliteRetrieveRepository.vecTableName(dim)
                + "(rowid, embedding) VALUES (?, ?)")) {
            ps.setLong(1, rowId);
            ps.setBytes(2, SqliteCjkBigram.serializeFloat32(emb));
            ps.executeUpdate();
        }
    }

    // ── 删除（先查行 → 删向量/FTS → 删元数据） ─────────────────────────────

    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteBy("chunk_id", chunkIdList);
    }

    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteBy("source_id", sourceIdList);
    }

    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteBy("knowledge_id", knowledgeIdList);
    }


    void deleteBy(String column, List<String> values) throws SQLException {
        if (values == null || values.isEmpty()) {
            return;
        }
        try (Connection conn = service.open()) {
            List<Row> rows = findRows(conn, column, values);
            deleteRowsAndVecs(conn, rows);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + SqliteRetrieveRepository.TABLE_EMBEDDINGS
                    + " WHERE " + column + " IN (" + SqliteRetrieveRepository.placeholders(values.size()) + ")")) {
                SqliteRetrieveRepository.bindStrings(ps, 1, values);
                ps.executeUpdate();
            }
        }
    }


    List<Row> findRows(Connection conn, String column, List<String> values)
            throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, dimension FROM "
                + SqliteRetrieveRepository.TABLE_EMBEDDINGS + " WHERE " + column + " IN ("
                + SqliteRetrieveRepository.placeholders(values.size()) + ")")) {
            SqliteRetrieveRepository.bindStrings(ps, 1, values);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Row row = new Row();
                    row.id = rs.getLong(1);
                    row.dimension = rs.getInt(2);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** 只动"已建向量表"的维度，再删 FTS 行。 */
    void deleteRowsAndVecs(Connection conn, List<Row> rows) throws SQLException {
        for (Row row : rows) {
            if (row.dimension > 0 && service.vecTables.containsKey(row.dimension)) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM "
                        + SqliteRetrieveRepository.vecTableName(row.dimension) + " WHERE rowid = ?")) {
                    ps.setLong(1, row.id);
                    ps.executeUpdate();
                }
            }
        }
        for (Row row : rows) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + SqliteRetrieveRepository.TABLE_FTS + " WHERE rowid = ?")) {
                ps.setLong(1, row.id);
                ps.executeUpdate();
            }
        }
    }

    // ── 批量更新（逐 chunk UPDATE 元数据） ─────────────────────────────────

    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            return;
        }
        try (Connection conn = service.open(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + SqliteRetrieveRepository.TABLE_EMBEDDINGS + " SET is_enabled = ?, updated_at = datetime('now')"
                        + " WHERE chunk_id = ?")) {
            for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
                ps.setInt(1, Boolean.TRUE.equals(entry.getValue()) ? 1 : 0);
                ps.setString(2, entry.getKey());
                ps.executeUpdate();
            }
        }
    }

    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            return;
        }
        try (Connection conn = service.open(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + SqliteRetrieveRepository.TABLE_EMBEDDINGS + " SET tag_id = ?, updated_at = datetime('now')"
                        + " WHERE chunk_id = ?")) {
            for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
                ps.setString(1, entry.getValue() == null ? "" : entry.getValue());
                ps.setString(2, entry.getKey());
                ps.executeUpdate();
            }
        }
    }

    // ── 拷贝（逐 chunk 读源行 → 新 UUID SourceID → 复制 FTS/向量） ─────────

    void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            return;
        }
        try (Connection conn = service.open()) {
            conn.setAutoCommit(false);
            try {
                for (Map.Entry<String, String> entry : sourceToTargetChunkIdMap.entrySet()) {
                    String sourceChunkId = entry.getKey();
                    String targetChunkId = entry.getValue();
                    Row src = findByChunkId(conn, sourceChunkId);
                    if (src == null) {
                        continue;
                    }
                    Row target = new Row();
                    target.sourceId = UUID.randomUUID().toString();
                    target.sourceType = src.sourceType;
                    target.chunkId = targetChunkId;
                    target.knowledgeId = sourceToTargetKbIdMap == null ? ""
                            : SqliteRetrieveRepository.nullToEmpty(sourceToTargetKbIdMap.get(src.knowledgeId));
                    target.knowledgeBaseId = targetKnowledgeBaseId;
                    target.tagId = src.tagId;
                    target.content = src.content;
                    target.dimension = src.dimension;
                    target.isEnabled = src.isEnabled;
                    long newId = insertIgnore(conn, target);
                    if (newId <= 0) {
                        log.warn("[SQLite] CopyIndices: failed to copy chunk {}", sourceChunkId);
                        continue;
                    }
                    syncFtsInsert(conn, newId, target);
                    if (src.dimension > 0 && newId > 0) {
                        copyVec(conn, src.id, newId, src.dimension);
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }


    Row findByChunkId(Connection conn, String chunkId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, source_type, chunk_id,"
                + " knowledge_id, knowledge_base_id, tag_id, content, dimension, is_enabled"
                + " FROM " + SqliteRetrieveRepository.TABLE_EMBEDDINGS + " WHERE chunk_id = ? LIMIT 1")) {
            ps.setString(1, chunkId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Row row = new Row();
                row.id = rs.getLong(1);
                row.sourceType = rs.getInt(2);
                row.chunkId = SqliteRetrieveRepository.nullToEmpty(rs.getString(3));
                row.knowledgeId = SqliteRetrieveRepository.nullToEmpty(rs.getString(4));
                row.knowledgeBaseId = SqliteRetrieveRepository.nullToEmpty(rs.getString(5));
                row.tagId = SqliteRetrieveRepository.nullToEmpty(rs.getString(6));
                row.content = SqliteRetrieveRepository.nullToEmpty(rs.getString(7));
                row.dimension = rs.getInt(8);
                row.isEnabled = rs.getInt(9) != 0;
                return row;
            }
        }
    }

    /** 向量行整行复制（同一维度表内）。 */
    void copyVec(Connection conn, long srcId, long dstId, int dim) throws SQLException {
        if (!service.vecTables.containsKey(dim)) {
            return;
        }
        service.ensureVecTable(conn, dim);
        String table = SqliteRetrieveRepository.vecTableName(dim);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + table
                + "(rowid, embedding) SELECT ?, embedding FROM " + table + " WHERE rowid = ?")) {
            ps.setLong(1, dstId);
            ps.setLong(2, srcId);
            ps.executeUpdate();
        }
    }
}
