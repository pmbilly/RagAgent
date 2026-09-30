package com.ragagent.retrieval.engine;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ragagent.common.pipeline.SearchParams;

/**
 * PostgreSQL 检索引擎仓库（对照 Go internal/application/repository/retriever/
 * postgres/repository.go 的 **Retrieve 面**，检索引擎批 2026-09-22 翻译）。
 *
 * <p>表 {@code embeddings}：halfvec 多维度列 + ParadeDB pg_search BM25 索引。
 * 两侧 SQL 逐字对照（含 expandedTopK 钳位、distance 阈值、ef_search/iterative_scan
 * 的 SET LOCAL + 降级重试）。写面（Index/CopyIndices/BatchUpdate*）属入库管线，
 * 随知识处理批。</p>
 */
@Component
public class PgVectorRetrieveRepository {

    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public PgVectorRetrieveRepository(JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        // SET LOCAL 是事务级 GUC：自动提交连接上只发 WARNING 即被丢弃（不报错），
        // ef_search/iterative_scan 从未生效。包一层事务让 SET LOCAL 真正落在事务内
        // （对照 Go 在 tx 内设置 GUC）；降级重试分支同样在事务外直查。
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    /** 对照 types.IndexWithScore（检索命中一行）。 */
    public static final class IndexHit {
        public String id = "";
        public String sourceId = "";
        public int sourceType;
        public String chunkId = "";
        public String knowledgeId = "";
        public String knowledgeBaseId = "";
        public String tagId = "";
        public String content = "";
        public double score;
        public int matchType;

        public static IndexHit of(String id, String sourceId, int sourceType, String chunkId,
                String knowledgeId, String knowledgeBaseId, String tagId, String content,
                double score, int matchType) {
            IndexHit h = new IndexHit();
            h.id = id;
            h.sourceId = sourceId;
            h.sourceType = sourceType;
            h.chunkId = chunkId;
            h.knowledgeId = knowledgeId;
            h.knowledgeBaseId = knowledgeBaseId;
            h.tagId = tagId;
            h.content = content;
            h.score = score;
            h.matchType = matchType;
            return h;
        }
    }

    /** 对照 types.RetrieveResult：一个引擎一次检索的整包结果。 */
    public record RetrieveResult(List<IndexHit> results, String retrieverEngineType,
            String retrieverType) {
        public static RetrieveResult of(List<IndexHit> results, String engine, String type) {
            return new RetrieveResult(results, engine, type);
        }
    }

    public static final String ENGINE_POSTGRES = "postgres";
    public static final String RETRIEVER_VECTOR = "vector";
    public static final String RETRIEVER_KEYWORDS = "keywords";
    /** 对照 types.MatchType（iota 序）。 */
    public static final int MATCH_EMBEDDING = 0;
    public static final int MATCH_KEYWORDS = 1;

    /**
     * 对照 KeywordsRetrieve（repository.go L164-261）：ParadeDB BM25，
     * {@code content ||| ?} 匹配任意 token，paradedb.score(id) 打分，score DESC。
     * 条件序与 Go 一致：kb IN → knowledge IN → tag IN → content ||| → is_enabled。
     */
    public RetrieveResult keywordsRetrieve(SearchParams params, List<String> knowledgeBaseIds,
            List<String> knowledgeIds, List<String> tagIds, int topK, String query) {
        StringBuilder where = new StringBuilder();
        List<Object> vars = new ArrayList<>();
        boolean first = true;
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            where.append("knowledge_base_id IN (").append(placeholders(knowledgeBaseIds.size()))
                    .append(')');
            vars.addAll(knowledgeBaseIds);
            first = false;
        }
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            if (!first) {
                where.append(" AND ");
            }
            where.append("knowledge_id IN (").append(placeholders(knowledgeIds.size())).append(')');
            vars.addAll(knowledgeIds);
            first = false;
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            if (!first) {
                where.append(" AND ");
            }
            where.append("tag_id IN (").append(placeholders(tagIds.size())).append(')');
            vars.addAll(tagIds);
            first = false;
        }
        if (!first) {
            where.append(" AND ");
        }
        where.append("content ||| ?");
        vars.add(query);
        where.append(" AND (is_enabled IS NULL OR is_enabled = ?)");
        vars.add(Boolean.TRUE);

        String sql = "SELECT paradedb.score(id) as score, id, content, source_id, source_type, "
                + "chunk_id, knowledge_id, knowledge_base_id, tag_id FROM embeddings WHERE "
                + where + " ORDER BY score DESC LIMIT " + topK;

        List<IndexHit> results = jdbc.query(sql, (rs, i) -> IndexHit.of(
                String.valueOf(rs.getLong("id")),
                rs.getString("source_id"),
                rs.getInt("source_type"),
                rs.getString("chunk_id"),
                rs.getString("knowledge_id"),
                rs.getString("knowledge_base_id"),
                rs.getString("tag_id"),
                rs.getString("content"),
                rs.getDouble("score"),
                MATCH_KEYWORDS), vars.toArray());
        return RetrieveResult.of(results, ENGINE_POSTGRES, RETRIEVER_KEYWORDS);
    }

    /**
     * 对照 VectorRetrieve（repository.go L265-483）：halfvec 表达式 HNSW 检索。
     * expandedTopK = clamp(TopK*2, 100..200, ≥TopK)；distance 阈值 = 1-Threshold；
     * SET LOCAL hnsw.ef_search / hnsw.iterative_scan 在事务内设置，GUC 不可用降级重试。
     */
    public RetrieveResult vectorRetrieve(SearchParams params, float[] embedding,
            List<String> knowledgeBaseIds, List<String> knowledgeIds, List<String> tagIds,
            int topK, double threshold) {
        int dimension = embedding.length;
        String queryVector = toHalfVecLiteral(embedding);

        StringBuilder where = new StringBuilder(" dimension = ? ");
        List<Object> vars = new ArrayList<>();
        vars.add(queryVector); // $1 向量占位
        vars.add(dimension);
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            where.append("AND knowledge_base_id IN (").append(placeholders(knowledgeBaseIds.size()))
                    .append(") ");
            vars.addAll(knowledgeBaseIds);
        }
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            where.append("AND knowledge_id IN (").append(placeholders(knowledgeIds.size()))
                    .append(") ");
            vars.addAll(knowledgeIds);
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            where.append("AND tag_id IN (").append(placeholders(tagIds.size())).append(") ");
            vars.addAll(tagIds);
        }
        where.append("AND (is_enabled IS NULL OR is_enabled = ?) ");
        vars.add(Boolean.TRUE);

        int expandedTopK = topK * 2;
        if (expandedTopK < 100) {
            expandedTopK = 100;
        }
        if (expandedTopK > 200) {
            expandedTopK = 200;
        }
        if (expandedTopK < topK) {
            expandedTopK = topK;
        }
        int efSearch = Math.max(expandedTopK, 40);
        // JDBC 只认 ? 占位且同一参数不能绑定两次——Go 里 $1（向量）被 WHERE 之外
        // 的 ORDER BY 再引用一次。改为把 halfvec 文本字面量内联（pgvector 文本
        // 输入与二进制传输语义等值，见 toHalfVecLiteral），其余参数照 ? 序绑定。
        String vectorLit = "'" + queryVector + "'::halfvec(" + dimension + ")";
        String sql = "SELECT id, content, source_id, source_type, chunk_id, knowledge_id, "
                + "knowledge_base_id, tag_id, (1 - distance) as score FROM ( "
                + "SELECT id, content, source_id, source_type, chunk_id, knowledge_id, "
                + "knowledge_base_id, tag_id, "
                + "embedding::halfvec(" + dimension + ") <=> " + vectorLit + " as distance "
                + "FROM embeddings WHERE " + where
                + "ORDER BY embedding::halfvec(" + dimension + ") <=> " + vectorLit + " "
                + "LIMIT ? ) AS candidates "
                + "WHERE distance <= ? "
                + "ORDER BY distance ASC "
                + "LIMIT ?";
        vars.remove(0); // queryVector 已内联，不再作为参数
        vars.add(expandedTopK);
        vars.add(1 - threshold);
        vars.add(topK);

        List<IndexHit> results = queryVectorRows(sql, vars.toArray(), efSearch);
        if (results.size() > topK) {
            results = new ArrayList<>(results.subList(0, topK));
        }
        for (IndexHit h : results) {
            h.matchType = MATCH_EMBEDDING;
        }
        return RetrieveResult.of(results, ENGINE_POSTGRES, RETRIEVER_VECTOR);
    }

    /** 向量查询 + HNSW GUC 事务（降级重试路径对照 Go L435-443）。 */
    private List<IndexHit> queryVectorRows(String sql, Object[] vars, int efSearch) {
        try {
            return tx.execute(status -> jdbc.execute(
                    (org.springframework.jdbc.core.ConnectionCallback<List<IndexHit>>) conn -> {
                        try (var st = conn.createStatement()) {
                            st.execute("SET LOCAL hnsw.ef_search = " + efSearch);
                            st.execute("SET LOCAL hnsw.iterative_scan = strict_order");
                        } catch (Exception gucErr) {
                            // 对照 Go：GUC 失败 → 中止事务 → 外层降级重试
                            throw gucErr;
                        }
                        try (var ps = conn.prepareStatement(sql)) {
                            for (int i = 0; i < vars.length; i++) {
                                ps.setObject(i + 1, vars[i]);
                            }
                            try (var rs = ps.executeQuery()) {
                                return mapVectorRows(rs);
                            }
                        }
                    }));
        } catch (Exception gucFailure) {
            String msg = String.valueOf(gucFailure.getMessage());
            if (msg.contains("hnsw.ef_search") || msg.contains("hnsw.iterative_scan")
                    || String.valueOf(gucFailure.getCause()).contains("hnsw")) {
                return jdbc.query(sql, (rs, i) -> mapVectorRowWithRow(rs), vars);
            }
            throw new IllegalStateException("vector retrieval failed: " + msg, gucFailure);
        }
    }

    private static List<IndexHit> mapVectorRows(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<IndexHit> out = new ArrayList<>();
        while (rs.next()) {
            out.add(mapVectorRow(rs, 0));
        }
        return out;
    }

    private static IndexHit mapVectorRowWithRow(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return mapVectorRow(rs, 0);
    }

    private static IndexHit mapVectorRow(java.sql.ResultSet rs, int ignored)
            throws java.sql.SQLException {
        return IndexHit.of(
                String.valueOf(rs.getLong("id")),
                rs.getString("source_id"),
                rs.getInt("source_type"),
                rs.getString("chunk_id"),
                rs.getString("knowledge_id"),
                rs.getString("knowledge_base_id"),
                rs.getString("tag_id"),
                rs.getString("content"),
                rs.getDouble("score"),
                MATCH_EMBEDDING);
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    /**
     * halfvec 字面量：pgvector 文本输入格式 {@code '[v1,v2,…]'}——halfvec 接受
     * float32 文本并按半精度落比较（与 pgvector-go NewHalfVector 的二进制传输等值）。
     */
    private static String toHalfVecLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 10);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            float v = embedding[i];
            if (v == 0.0f) {
                sb.append('0');
            } else {
                sb.append(v);
            }
        }
        sb.append(']');
        return sb.toString();
    }
}
