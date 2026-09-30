package com.ragagent.retrieval.engine.doris;

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
 * Doris 引擎的检索簇：向量（单位化 + inner_product/cosine 双模式 + HAVING score 过滤）与
 * keywords（match 全文）两路、行映射与结果包装、取回错误包装。
 */
final class DorisSearchOps {

    private static final Logger log = LoggerFactory.getLogger(DorisSearchOps.class);

    private final DorisRetrieveRepository service;

    DorisSearchOps(DorisRetrieveRepository service) {
        this.service = service;
    }

    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        String retrieverType = params == null || params.retrieverType == null
                ? "" : params.retrieverType;
        return switch (retrieverType) {
            case EngineTypes.RETRIEVER_VECTOR -> vectorRetrieve(params);
            case EngineTypes.RETRIEVER_KEYWORDS -> keywordsRetrieve(params);
            default -> throw new IllegalStateException(
                    "invalid retriever type: " + retrieverType);
        };
    }

    /**
     * 对照 {@code VectorRetrieve}：查询向量先单位化（非 legacy），再走
     * {@code inner_product_approximate}（legacy 用 {@code 1 - cosine_distance_approximate}）；
     * score 越大越相似，{@code HAVING score >= ?} 过滤（score 是列别名，WHERE 阶段不可见）。
     */
    List<RetrieveResult> vectorRetrieve(RetrieveParams params) {
        float[] embedding = params.embedding == null ? new float[0] : params.embedding;
        try {
            DorisSql.validateEmbedding(embedding);
        } catch (DorisSql.InvalidEmbeddingException e) {
            throw new IllegalStateException("invalid query embedding: " + e.getMessage(), e);
        }
        DorisCompatMode compatMode = service.resolveCompatModeOrThrow();
        float[] queryEmbedding = embedding.clone();
        if (compatMode.normalizeEmbeddings()) {
            queryEmbedding = DorisSql.normalizeEmbedding(queryEmbedding);
        }
        int dim = embedding.length;
        String table = service.getTableName(dim);
        boolean exists;
        try {
            exists = service.tableExists(table);
        } catch (RuntimeException e) {
            throw new IllegalStateException("check table " + table + ": " + e.getMessage(), e);
        }
        if (!exists) {
            log.warn("[Doris] Table {} does not exist, returning empty results", table);
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_VECTOR);
        }
        DorisSql.Clause where = DorisSql.buildBaseFilter(params).build();
        String literal = DorisSql.embeddingLiteral(queryEmbedding);
        String scoreExpr = "inner_product_approximate(`" + DorisSql.FIELD_EMBEDDING
                + "`, " + literal + ")";
        if (compatMode == DorisCompatMode.LEGACY) {
            scoreExpr = "(1 - cosine_distance_approximate(`" + DorisSql.FIELD_EMBEDDING
                    + "`, " + literal + "))";
        }
        String stmt = "SELECT " + String.join(", ", DorisSql.COLUMNS_FOR_RETRIEVE)
                + ", " + scoreExpr + " AS score "
                + "FROM `" + table + "` WHERE " + where.sql() + " "
                + "HAVING score >= ? "
                + "ORDER BY score DESC LIMIT " + params.topK;
        List<Object> args = new ArrayList<>(where.args());
        args.add(params.threshold);
        List<IndexWithScore> results;
        try {
            results = service.sql.query(stmt, args,
                    row -> scanRetrieveRow(row, EngineTypes.MATCH_EMBEDDING, true));
        } catch (SQLException e) {
            throw wrapVectorRetrieveError(table, compatMode, e);
        }
        log.info("[Doris] Vector retrieval found {} results in {}", results.size(), table);
        return buildRetrieveResult(results, EngineTypes.RETRIEVER_VECTOR);
    }

    /**
     * 对照 {@code KeywordsRetrieve}：倒排索引 + MATCH_ANY（中文分词由建表 DDL 的
     * chinese parser 承担，不需要客户端分词）；跨维度表合并取 topK，score 恒 1.0。
     */
    List<RetrieveResult> keywordsRetrieve(RetrieveParams params) {
        String query = params.query == null ? "" : params.query.trim();
        if (query.isEmpty()) {
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_KEYWORDS);
        }
        List<String> tables;
        try {
            tables = service.listEmbeddingTables();
        } catch (RuntimeException e) {
            throw new IllegalStateException("list tables: " + e.getMessage(), e);
        }
        if (tables.isEmpty()) {
            return buildRetrieveResult(List.of(), EngineTypes.RETRIEVER_KEYWORDS);
        }
        DorisSql.Clause where = DorisSql.buildBaseFilter(params).build();
        List<IndexWithScore> all = new ArrayList<>();
        for (String table : tables) {
            String stmt = "SELECT " + String.join(", ", DorisSql.COLUMNS_FOR_RETRIEVE)
                    + " FROM `" + table + "` WHERE " + where.sql() + " AND "
                    + DorisSql.FIELD_CONTENT + " MATCH_ANY ? LIMIT " + params.topK;
            List<Object> args = new ArrayList<>(where.args());
            args.add(query);
            try {
                all.addAll(service.sql.query(stmt, args,
                        row -> scanRetrieveRow(row, EngineTypes.MATCH_KEYWORDS, false)));
            } catch (SQLException e) {
                log.warn("[Doris] Keyword retrieve in {} failed: {}", table, e.getMessage());
            }
        }
        int topK = Math.max(0, params.topK);
        if (all.size() > topK) {
            all = new ArrayList<>(all.subList(0, topK));
        }
        log.info("[Doris] Keywords retrieval found {} results across {} tables",
                all.size(), tables.size());
        return buildRetrieveResult(all, EngineTypes.RETRIEVER_KEYWORDS);
    }

    /**
     * 对照 {@code scanRetrieveRows}：列数 == {@code columnsForRetrieve}（9）时 score 恒 1.0
     * （关键词路径）；带 score 的第 10 列（向量路径）。IsEnabled 不回填（Go 同）。
     */
    static IndexWithScore scanRetrieveRow(DorisSqlExecutor.Row row, int matchType,
                                          boolean withScore) throws SQLException {
        IndexWithScore out = new IndexWithScore();
        out.id = row.string(0);
        out.content = row.string(1);
        out.sourceId = row.string(2);
        out.sourceType = row.intValue(3);
        out.chunkId = row.string(4);
        out.knowledgeId = row.string(5);
        out.knowledgeBaseId = row.string(6);
        out.tagId = row.string(7);
        out.score = withScore ? row.doubleValue(9) : 1.0;
        out.matchType = matchType;
        return out;
    }

    /** 对照 {@code buildRetrieveResult}：单元素结果壳（Error 恒 null）。 */
    static List<RetrieveResult> buildRetrieveResult(List<IndexWithScore> results,
                                                    String retrieverType) {
        return List.of(new RetrieveResult(results, EngineTypes.ENGINE_DORIS, retrieverType));
    }

    /** 对照 {@code wrapVectorRetrieveError}：legacy 失败时附带模式切换指引。 */
    IllegalStateException wrapVectorRetrieveError(String table, DorisCompatMode compatMode,
                                                          Exception err) {
        if (compatMode == DorisCompatMode.LEGACY) {
            return new IllegalStateException("vector retrieve " + table + " in Doris compat mode "
                    + compatMode.wire() + ": " + DorisRetrieveRepository.message(err) + ". If your Doris build does not "
                    + "support cosine_distance_approximate or ANN on UNIQUE KEY tables, set "
                    + DorisCompatMode.ENV_KEY + "="
                    + DorisCompatMode.INNER_PRODUCT_DUPLICATE.wire()
                    + " before creating embedding tables. " + DorisCompatMode.ENV_KEY
                    + " is not interchangeable after " + service.tableBaseName
                    + "_* tables are created", err);
        }
        return new IllegalStateException("vector retrieve " + table + " in Doris compat mode "
                + compatMode.wire() + ": " + DorisRetrieveRepository.message(err), err);
    }
}
