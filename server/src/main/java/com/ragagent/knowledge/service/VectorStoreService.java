package com.ragagent.knowledge.service;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * postgres 向量写/删库（对照 Go retriever/postgres repository.go 的
 * BatchSave / DeleteByChunkIDList / DeleteByKnowledgeIDList → embeddings 表）。
 *
 * <p>halfvec 写入：PG 需 {@code ?::halfvec} 强转（pgvector 类型，PG JDBC 无内建映射）；
 * 测试库 H2 退化为 VARCHAR 存储（H2 分支的 MERGE 语义近似 ON CONFLICT DO NOTHING——
 * H2 无 DO NOTHING 形态，命中 KEY 时覆盖；两侧的调用方都是"先删后插"，无命中场景）。</p>
 *
 * <p><b>source_id 契约（2026-09-22 走查修正）</b>：对照 Go 全部 IndexInfo 构造点，
 * chunk 行的 source_id = chunkID（<b>无</b> "chunk:" 前缀——此前 Java 侧写
 * "chunk:"+id 是移植偏差，会让双端共库时同一 chunk 出两份向量行）；生成问题行
 * source_id = GeneratedQuestionSourceID(chunkID, questionID)（超 64 字节时
 * {@code chunkID-q<sha256 前 12 字节 hex>}，见 Go types/faq.go）。</p>
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    /**
     * 对照 Go types.IndexInfo 落库面（toDBVectorEmbedding 的列投影）：
     * source_type 恒 0（types.ChunkSourceType）；content 是调用方组装好的
     * 索引文本（title 前缀 + EmbeddingContent，见 buildKnowledgeIndexContent）。
     */
    public record IndexRow(
            String sourceId,
            String chunkId,
            String knowledgeId,
            String knowledgeBaseId,
            String content,
            boolean isEnabled) {
    }

    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public VectorStoreService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        boolean pg = false;
        try (Connection conn = dataSource.getConnection()) {
            pg = conn.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) {
            log.warn("detect database product failed, assume non-postgres: {}", e.toString());
        }
        this.postgres = pg;
    }

    /**
     * 对照 Go BatchSave：{@code INSERT ... ON CONFLICT DO NOTHING}（source_id+source_type
     * 唯一）；向量按行序对应（rows[i] ↔ vectors[i]）。调用方负责先删旧行
     * （{@link #deleteByChunkId} / {@link #deleteByKnowledgeId}），Go 的 updateChunkVector
     * 与处理管道都是"先删后插"。
     */
    public void saveIndexRows(List<IndexRow> rows, List<float[]> vectors) {
        String sql = postgres
                ? "INSERT INTO embeddings (created_at, updated_at, source_id, source_type, chunk_id, "
                  + "knowledge_id, knowledge_base_id, content, is_enabled, dimension, embedding) "
                  + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?::halfvec) "
                  + "ON CONFLICT (source_id, source_type) DO NOTHING"
                : "MERGE INTO embeddings (created_at, updated_at, source_id, source_type, chunk_id, "
                  + "knowledge_id, knowledge_base_id, content, is_enabled, dimension, embedding) "
                  + "KEY(source_id, source_type) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?)";
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
            ps.setString(7, r.content());
            ps.setBoolean(8, r.isEnabled());
            ps.setInt(9, vector.length);
            // PG 侧 SQL 带 ?::halfvec 强转（pgvector 提供 text→halfvec cast），
            // String 参数经服务端 cast 入库；H2 走 MERGE 直接存字符串
            ps.setString(10, toHalfvecLiteral(vector));
        });
    }

    /** 对照 Go DeleteByChunkIDList：{@code DELETE WHERE chunk_id IN (...)}（含生成问题行）。 */
    public void deleteByChunkId(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        jdbc.update("DELETE FROM embeddings WHERE chunk_id IN (" + placeholders + ")",
                chunkIds.toArray());
    }

    /** 对照 Go DeleteBySourceIDList：{@code DELETE WHERE source_id IN (...)}（删除问题行）。 */
    public void deleteBySourceId(List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(sourceIds.size(), "?"));
        jdbc.update("DELETE FROM embeddings WHERE source_id IN (" + placeholders + ")",
                sourceIds.toArray());
    }

    /** 对照 Go DeleteByKnowledgeIDList：{@code DELETE WHERE knowledge_id IN (...)}。 */
    public void deleteByKnowledgeId(List<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(knowledgeIds.size(), "?"));
        jdbc.update("DELETE FROM embeddings WHERE knowledge_id IN (" + placeholders + ")",
                knowledgeIds.toArray());
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
