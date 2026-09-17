package com.ragagent.knowledge.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.Knowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * postgres 向量写库（对照 Go retriever/postgres BatchSave → embeddings 表 halfvec）。
 *
 * halfvec 写入：PG 需 `?::halfvec` 强转（pgvector 类型，PG JDBC 无内建映射）；
 * 测试库 H2 退化为 VARCHAR 存储（向量检索本身随检索模块翻译，阶段 3 只写不查）。
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

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

    /** 对照 BatchSave：upsert（source_id+source_type 唯一），source_type=0（chunk 向量） */
    public void batchSave(Knowledge k, List<Chunk> chunks, List<float[]> vectors) {
        String sql = postgres
                ? "INSERT INTO embeddings (created_at, updated_at, source_id, source_type, chunk_id, "
                  + "knowledge_id, knowledge_base_id, content, dimension, embedding) "
                  + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?::halfvec) "
                  + "ON CONFLICT (source_id, source_type) DO UPDATE SET "
                  + "updated_at = EXCLUDED.updated_at, content = EXCLUDED.content, "
                  + "dimension = EXCLUDED.dimension, embedding = EXCLUDED.embedding"
                : "MERGE INTO embeddings (created_at, updated_at, source_id, source_type, chunk_id, "
                  + "knowledge_id, knowledge_base_id, content, dimension, embedding) "
                  + "KEY(source_id, source_type) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?)";
        Timestamp now = Timestamp.from(Instant.now());
        java.util.List<Integer> indexes = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            indexes.add(i);
        }
        jdbc.batchUpdate(sql, indexes, indexes.size(), (ps, idx) -> {
            Chunk c = chunks.get(idx);
            float[] vector = vectors.get(idx);
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setString(3, "chunk:" + c.getId());
            ps.setString(4, c.getId());
            ps.setString(5, k.getId());
            ps.setString(6, k.getKnowledgeBaseId());
            ps.setString(7, c.getContent());
            ps.setInt(8, vector.length);
            // PG 侧 SQL 带 ?::halfvec 强转（pgvector 提供 text→halfvec cast），
            // String 参数经服务端 cast 入库；H2 走 MERGE 直接存字符串
            ps.setString(9, toHalfvecLiteral(vector));
        });
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
