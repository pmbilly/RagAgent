package com.ragagent.retrieval.engine.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite 引擎的检索簇：keywords/vector 双路分派（空类型两路都跑并合并——本店特例）、
 * FTS5 关键词查询、vec0 形状的 KNN（先取 k 近邻再收窄）、行读取与过滤构造。
 */
final class SqliteSearchOps {

    private static final Logger log = LoggerFactory.getLogger(SqliteSearchOps.class);

    private final SqliteRetrieveRepository service;

    SqliteSearchOps(SqliteRetrieveRepository service) {
        this.service = service;
    }

    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        List<RetrieveResult> results = new ArrayList<>();
        String type = params == null || params.retrieverType == null ? "" : params.retrieverType;
        if (EngineTypes.RETRIEVER_KEYWORDS.equals(type) || type.isEmpty()) {
            results.addAll(keywordsRetrieve(params));
        }
        if (EngineTypes.RETRIEVER_VECTOR.equals(type) || type.isEmpty()) {
            results.addAll(vectorRetrieve(params));
        }
        // 未知类型不报错，返回空列表（其他店的"invalid retriever type"是特例）
        return results;
    }


    List<RetrieveResult> keywordsRetrieve(RetrieveParams params) throws SQLException {
        String query = params.query == null ? "" : params.query;
        if (query.isEmpty()) {
            return List.of();
        }
        String ftsQuery = SqliteCjkBigram.sanitizeQuery(query);
        StringBuilder sql = new StringBuilder("SELECT e.id, e.source_id, e.source_type, e.chunk_id,"
                + " e.knowledge_id, e.knowledge_base_id, e.tag_id, e.content,"
                + " (bm25(" + SqliteRetrieveRepository.TABLE_FTS + ") * -1000000.0) AS score"
                + " FROM " + SqliteRetrieveRepository.TABLE_FTS + " JOIN " + SqliteRetrieveRepository.TABLE_EMBEDDINGS
                + " e ON e.id = " + SqliteRetrieveRepository.TABLE_FTS + ".rowid"
                + " WHERE " + SqliteRetrieveRepository.TABLE_FTS + " MATCH ?"
                + " AND (e.is_enabled IS NULL OR e.is_enabled = 1)");
        List<Object> args = new ArrayList<>();
        args.add(ftsQuery);
        for (FilterWhere wp : buildFilterWhere(params, "e")) {
            sql.append(" AND ").append(wp.clause());
            args.addAll(wp.args());
        }
        sql.append(" ORDER BY score DESC LIMIT ?");
        args.add(Math.max(params.topK, 0));

        List<IndexWithScore> items = new ArrayList<>();
        try (Connection conn = service.open(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            SqliteRetrieveRepository.bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    IndexWithScore item = readIndex(rs, false);
                    item.matchType = EngineTypes.MATCH_KEYWORDS;
                    items.add(item);
                }
            }
        } catch (SQLException e) {
            throw new SQLException("FTS5 query failed: " + e.getMessage(), e);
        }
        log.info("[SQLite] keywordsRetrieve: query={}, ftsQuery={}, matched={} rows", query,
                ftsQuery, items.size());
        return List.of(new RetrieveResult(items, EngineTypes.ENGINE_SQLITE,
                EngineTypes.RETRIEVER_KEYWORDS));
    }

    /**
     * 向量查询形状：<b>先取 k 近邻，再用 {@code rowid IN (过滤子查询)} 收窄</b>
     * （因此结果可能少于 TopK——既有语义，别"顺手"改成先过滤）；阈值在取回后于
     * 内存里衰减（{@code score < threshold → 跳过}）。
     */
    List<RetrieveResult> vectorRetrieve(RetrieveParams params) throws SQLException {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        if (embedding.length == 0) {
            return List.of();
        }
        int dim = embedding.length;
        service.ensureVecTable(dim);
        String table = SqliteRetrieveRepository.vecTableName(dim);
        String filterSql = "SELECT filtered.id FROM " + SqliteRetrieveRepository.TABLE_EMBEDDINGS + " filtered"
                + " WHERE (filtered.is_enabled IS NULL OR filtered.is_enabled = 1)";
        List<Object> args = new ArrayList<>();
        args.add(SqliteCjkBigram.serializeFloat32(embedding));
        args.add(Math.max(params.topK, 0));
        List<String> clauses = new ArrayList<>();
        List<Object> filterArgs = new ArrayList<>();
        for (FilterWhere wp : buildFilterWhere(params, "filtered")) {
            clauses.add(wp.clause());
            filterArgs.addAll(wp.args());
        }
        if (!clauses.isEmpty()) {
            filterSql += " AND " + String.join(" AND ", clauses);
        }
        String sql = "SELECT v.rowid, v.distance, e.source_id, e.source_type, e.chunk_id,"
                + " e.knowledge_id, e.knowledge_base_id, e.tag_id, e.content"
                + " FROM (SELECT rowid, vec_distance_cosine(embedding, ?) AS distance FROM "
                + table + " ORDER BY distance ASC LIMIT ?) v"
                + " JOIN " + SqliteRetrieveRepository.TABLE_EMBEDDINGS + " e ON e.id = v.rowid"
                + " WHERE v.rowid IN (" + filterSql + ")"
                + " ORDER BY v.distance ASC";
        args.addAll(filterArgs);

        List<IndexWithScore> items = new ArrayList<>();
        try (Connection conn = service.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            SqliteRetrieveRepository.bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double distance = rs.getDouble("distance");
                    double score = 1 - distance;
                    if (params.threshold > 0 && score < params.threshold) {
                        continue;
                    }
                    IndexWithScore item = readIndex(rs, true);
                    item.score = score;
                    item.matchType = EngineTypes.MATCH_EMBEDDING;
                    items.add(item);
                }
            }
        } catch (SQLException e) {
            throw new SQLException("sqlite-vec query failed: " + e.getMessage(), e);
        }
        log.info("[SQLite] vectorRetrieve: query_dim={}, threshold={}, matched={} rows", dim,
                params.threshold, items.size());
        return List.of(new RetrieveResult(items, EngineTypes.ENGINE_SQLITE,
                EngineTypes.RETRIEVER_VECTOR));
    }

    /** 行读取：{@code id} 用 rowid 的十进制串。 */
    static IndexWithScore readIndex(ResultSet rs, boolean withRowid) throws SQLException {
        IndexWithScore item = new IndexWithScore();
        item.id = String.valueOf(withRowid ? rs.getLong("rowid") : rs.getLong("id"));
        item.sourceId = SqliteRetrieveRepository.nullToEmpty(rs.getString("source_id"));
        item.sourceType = rs.getInt("source_type");
        item.chunkId = SqliteRetrieveRepository.nullToEmpty(rs.getString("chunk_id"));
        item.knowledgeId = SqliteRetrieveRepository.nullToEmpty(rs.getString("knowledge_id"));
        item.knowledgeBaseId = SqliteRetrieveRepository.nullToEmpty(rs.getString("knowledge_base_id"));
        item.tagId = SqliteRetrieveRepository.nullToEmpty(rs.getString("tag_id"));
        item.content = SqliteRetrieveRepository.nullToEmpty(rs.getString("content"));
        if (!withRowid) {
            item.score = rs.getDouble("score");
        }
        return item;
    }

    // ── 过滤（只有 KB/知识/标签三个 IN，无排除项） ─────────────────────────

    record FilterWhere(String clause, List<Object> args) {
    }


    static List<FilterWhere> buildFilterWhere(RetrieveParams params, String alias) {
        List<FilterWhere> parts = new ArrayList<>();
        if (params == null) {
            return parts;
        }
        addFilter(parts, alias + ".knowledge_base_id", params.knowledgeBaseIds);
        addFilter(parts, alias + ".knowledge_id", params.knowledgeIds);
        addFilter(parts, alias + ".tag_id", params.tagIds);
        return parts;
    }


    static void addFilter(List<FilterWhere> parts, String column, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        parts.add(new FilterWhere(column + " IN (" + SqliteRetrieveRepository.placeholders(values.size()) + ")",
                new ArrayList<>(values)));
    }
}
