package com.ragagent.knowledge.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ragagent.common.jdbc.DatabaseDialects;

/**
 * postgres 向量写/删/更新。
 * <p>halfvec 写入：PG 需 {@code ?::halfvec} 强转（pgvector 类型，PG JDBC 无内建映射）；
 * 测试库 H2 退化为 VARCHAR 存储（H2 分支的 MERGE 语义近似 ON CONFLICT DO NOTHING——
 * H2 无 DO NOTHING 形态，命中 KEY 时覆盖；两侧的调用方都是"先删后插"，无命中场景）。</p>
 * <p><b>source_id 契约</b>：chunk 行 = chunkID（无前缀）；生成问题行 =
 * {@code chunkID-qID}（超 64 字节折叠 {@code chunkID-q<sha256 前 12 字节 hex>}，
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    /**
     * source_type 恒 0（types.ChunkSourceType）；content 是调用方组装好的索引文本；
     */
    public record IndexRow(
            String sourceId,
            String chunkId,
            String knowledgeId,
            String knowledgeBaseId,
            String content,
            boolean isEnabled,
            String tagId) {
    }

    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public VectorStoreService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgres = DatabaseDialects.isPostgres(jdbc);
    }

    /**
     * {@code INSERT ... ON CONFLICT DO NOTHING}（source_id+source_type
     * 唯一）；向量按行序对应（rows[i] ↔ vectors[i]）。调用方负责先删旧行
     * （{@link #deleteByChunkId} / {@link #deleteByKnowledgeId} / {@link #deleteBySourceId}），
     */
    public void saveIndexRows(List<IndexRow> rows, List<float[]> vectors) {
        String sql = postgres
                ? "INSERT INTO embeddings (created_at, updated_at, source_id, source_type, chunk_id, "
                  + "knowledge_id, knowledge_base_id, tag_id, content, is_enabled, dimension, embedding) "
                  + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?::halfvec) "
                  + "ON CONFLICT (source_id, source_type) DO NOTHING"
                : "MERGE INTO embeddings (created_at, updated_at, source_id, source_type, chunk_id, "
                  + "knowledge_id, knowledge_base_id, tag_id, content, is_enabled, dimension, embedding) "
                  + "KEY(source_id, source_type) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)";
        Timestamp now = Timestamp.from(Instant.now());
        List<Integer> indexes = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            indexes.add(i);
        }
        jdbc.batchUpdate(sql, indexes, indexes.size(), (ps, idx) -> {
            IndexRow r = rows.get(idx);
            float[] vector = vectors.get(idx);
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setString(3, r.sourceId());
            ps.setString(4, r.chunkId());
            ps.setString(5, r.knowledgeId());
            ps.setString(6, r.knowledgeBaseId());
            ps.setString(7, r.tagId() == null ? "" : r.tagId());
            ps.setString(8, r.content());
            ps.setBoolean(9, r.isEnabled());
            ps.setInt(10, vector.length);
            // PG 侧 SQL 带 ?::halfvec 强转（pgvector 提供 text→halfvec cast），
            // String 参数经服务端 cast 入库；H2 走 MERGE 直接存字符串
            ps.setString(11, toHalfvecLiteral(vector));
        });
    }

    /** {@code DELETE WHERE chunk_id IN (...)}（含生成问题/相似问行）。 */
    public void deleteByChunkId(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        jdbc.update("DELETE FROM embeddings WHERE chunk_id IN (" + placeholders + ")",
                chunkIds.toArray());
    }

    /** {@code DELETE WHERE source_id IN (...)}（删除问题行）。 */
    public void deleteBySourceId(List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(sourceIds.size(), "?"));
        jdbc.update("DELETE FROM embeddings WHERE source_id IN (" + placeholders + ")",
                sourceIds.toArray());
    }

    /** {@code DELETE WHERE knowledge_id IN (...)}。 */
    public void deleteByKnowledgeId(List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(knowledgeIds.size(), "?"));
        jdbc.update("DELETE FROM embeddings WHERE knowledge_id IN (" + placeholders + ")",
                knowledgeIds.toArray());
    }

    /**
     * 按启用态分组批量更新
     * {@code UPDATE embeddings SET is_enabled = ? WHERE chunk_id IN (...)}。
     */
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            return;
        }
        List<String> enabledIds = new java.util.ArrayList<>();
        List<String> disabledIds = new java.util.ArrayList<>();
        for (Map.Entry<String, Boolean> e : chunkStatusMap.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                enabledIds.add(e.getKey());
            } else {
                disabledIds.add(e.getKey());
            }
        }
        if (!enabledIds.isEmpty()) {
            updateEnabledStatus(enabledIds, true);
        }
        if (!disabledIds.isEmpty()) {
            updateEnabledStatus(disabledIds, false);
        }
    }

    private void updateEnabledStatus(List<String> chunkIds, boolean enabled) {
        String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        Object[] args = new Object[chunkIds.size() + 1];
        args[0] = enabled;
        for (int i = 0; i < chunkIds.size(); i++) {
            args[i + 1] = chunkIds.get(i);
        }
        jdbc.update("UPDATE embeddings SET is_enabled = ? WHERE chunk_id IN (" + placeholders + ")", args);
    }

    /**
     * 按 tagId 分组批量更新
     * {@code UPDATE embeddings SET tag_id = ? WHERE chunk_id IN (...)}。
     */
    public void batchUpdateChunkTagId(Map<String, String> chunkTagMap) {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            return;
        }
        Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : chunkTagMap.entrySet()) {
            groups.computeIfAbsent(e.getValue() == null ? "" : e.getValue(),
                    k -> new java.util.ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            List<String> chunkIds = e.getValue();
            String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
            Object[] args = new Object[chunkIds.size() + 1];
            args[0] = e.getKey();
            for (int i = 0; i < chunkIds.size(); i++) {
                args[i + 1] = chunkIds.get(i);
            }
            jdbc.update("UPDATE embeddings SET tag_id = ? WHERE chunk_id IN (" + placeholders + ")", args);
        }
    }

    /**
     * content 字节 + dim×2（halfvec）+ 200 元数据开销 + 2×向量字节（HNSW 开销）。
     */
    public static long estimateStorageSize(List<IndexRow> rows, int dimension) {
        long total = 0;
        for (IndexRow row : rows) {
            long contentSize = row.content() == null ? 0
                    : row.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long vectorSize = dimension > 0 ? (long) dimension * 2 : 0;
            total += contentSize + vectorSize + 200 + vectorSize * 2;
        }
        return total;
    }

    /** halfvec 文本形态：[0.1,0.2,...] */
    private static String toHalfvecLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
