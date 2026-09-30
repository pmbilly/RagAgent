package com.ragagent.retrieval.engine.doris;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Doris 引擎的写入/删除/批量更新/拷贝/迁移簇：按维度分组（DUPLICATE KEY 表 delete+insert
 * 保持"按 id 替换"）、terms 删除、rewrite 批量改写（legacy 路径随兼容模式）、
 * CopyIndices 分页（批 64）+ 三态 SourceID、move 的完整性校验。
 */
final class DorisWriteOps {

    private static final Logger log = LoggerFactory.getLogger(DorisWriteOps.class);

    private final DorisRetrieveRepository service;

    DorisWriteOps(DorisRetrieveRepository service) {
        this.service = service;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        batchSave(List.of(indexInfo), params);
    }

    void batchSave(List<IndexInfo> indexInfoList, Map<String, Object> params)
            throws Exception {
        if (indexInfoList == null || indexInfoList.isEmpty()) {
            return;
        }
        DorisCompatMode compatMode = service.resolveCompatModeOrThrow();
        Map<Integer, List<DorisVectorEmbedding>> groups = new TreeMap<>();
        for (IndexInfo info : indexInfoList) {
            DorisVectorEmbedding emb = DorisRetrieveRepository.toEmbedding(info, params, compatMode);
            if (emb.embedding == null || emb.embedding.length == 0) {
                log.warn("[Doris] Skipping empty embedding for chunk {}", info.chunkId);
                continue;
            }
            try {
                DorisSql.validateEmbedding(emb.embedding);
            } catch (DorisSql.InvalidEmbeddingException e) {
                throw new IllegalStateException("invalid embedding for chunk " + info.chunkId
                        + ": " + e.getMessage(), e);
            }
            if (emb.id.isEmpty()) {
                emb.id = emb.sourceId;
            }
            if (emb.id.isEmpty()) {
                emb.id = UUID.randomUUID().toString();
            }
            groups.computeIfAbsent(emb.embedding.length, k -> new ArrayList<>()).add(emb);
        }
        for (Map.Entry<Integer, List<DorisVectorEmbedding>> entry : groups.entrySet()) {
            int dim = entry.getKey();
            String table = service.getTableName(dim);
            service.ensureTable(dim);
            try {
                if (compatMode.usesReplaceWrite()) {
                    replaceRows(table, entry.getValue());
                } else {
                    insertRows(table, entry.getValue());
                }
            } catch (SQLException e) {
                throw new IllegalStateException("batch save dim=" + dim + ": " + DorisRetrieveRepository.message(e), e);
            }
            log.info("[Doris] Saved {} rows to {}", entry.getValue().size(), table);
        }
    }

    /**
     * 对照 {@code insertRows}：按列序拼一条多 VALUES 的 INSERT；embedding 列以字面量
     * 形式内联（MySQL 驱动不支持 ARRAY 占位符）。
     */
    void insertRows(String table, List<DorisVectorEmbedding> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>(rows.size());
        List<Object> args = new ArrayList<>(rows.size() * 9);
        for (DorisVectorEmbedding e : rows) {
            parts.add("(?, ?, ?, ?, ?, ?, ?, ?, ?, "
                    + DorisSql.embeddingLiteral(e.embedding) + ")");
            args.add(e.id);
            args.add(e.content);
            args.add(e.sourceId);
            args.add(e.sourceType);
            args.add(e.chunkId);
            args.add(e.knowledgeId);
            args.add(e.knowledgeBaseId);
            args.add(e.tagId);
            args.add(e.isEnabled);
        }
        String stmt = "INSERT INTO `" + table + "` (" + String.join(", ", DorisSql.COLUMNS)
                + ") VALUES " + String.join(", ", parts);
        service.sql.execute(stmt, args);
    }

    /** 对照 {@code replaceRows}：按 id 去重（后者胜）后 delete + insert。 */
    void replaceRows(String table, List<DorisVectorEmbedding> rows) throws SQLException {
        List<DorisVectorEmbedding> deduped = dedupeRowsById(rows);
        if (deduped.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(deduped.size());
        for (DorisVectorEmbedding row : deduped) {
            ids.add(row.id);
        }
        deleteRowsById(table, ids);
        insertRows(table, deduped);
    }


    void deleteRowsById(String table, List<String> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        String stmt = "DELETE FROM `" + table + "` WHERE " + DorisSql.FIELD_ID
                + " IN (" + placeholders + ")";
        service.sql.execute(stmt, new ArrayList<>(ids));
    }

    /** 对照 {@code dedupeRowsByID}：同 id 保留最后一条，且保留首次出现的位次。 */
    static List<DorisVectorEmbedding> dedupeRowsById(List<DorisVectorEmbedding> rows) {
        if (rows.size() < 2) {
            return rows;
        }
        Map<String, Integer> positions = new LinkedHashMap<>();
        List<DorisVectorEmbedding> out = new ArrayList<>(rows.size());
        for (DorisVectorEmbedding row : rows) {
            Integer idx = positions.get(row.id);
            if (idx != null) {
                out.set(idx, row);
                continue;
            }
            positions.put(row.id, out.size());
            out.add(row);
        }
        return out;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(DorisSql.FIELD_CHUNK_ID, chunkIdList, dimension);
    }

    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByField(DorisSql.FIELD_KNOWLEDGE_ID, knowledgeIdList, dimension);
    }

    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(DorisSql.FIELD_SOURCE_ID, sourceIdList, dimension);
    }

    /** 对照 {@code deleteByField}：DELETE FROM <table> WHERE <field> IN (?, ?, ...)。 */
    void deleteByField(String field, List<String> ids, int dimension) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String table = service.getTableName(dimension);
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        String stmt = "DELETE FROM `" + table + "` WHERE " + field
                + " IN (" + placeholders + ")";
        try {
            service.sql.execute(stmt, new ArrayList<>(ids));
        } catch (SQLException e) {
            log.error("[Doris] Delete by {} failed: {}", field, e.getMessage());
            throw new IllegalStateException("delete by " + field + ": " + DorisRetrieveRepository.message(e), e);
        }
        log.info("[Doris] Deleted {} rows from {} by {}", ids.size(), table, field);
    }

    // ── 复制 / 批量更新 ─────────────────────────────────────────────────────

    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            return;
        }
        service.ensureTable(dimension);
        String table = service.getTableName(dimension);
        int offset = 0;
        int totalCopied = 0;
        while (true) {
            String stmt = "SELECT " + String.join(", ", DorisSql.COLUMNS_FOR_COPY)
                    + " FROM `" + table + "` WHERE " + DorisSql.FIELD_KNOWLEDGE_BASE_ID
                    + " = ? ORDER BY " + DorisSql.FIELD_ID
                    + " LIMIT " + DorisRetrieveRepository.COPY_PAGE_SIZE + " OFFSET " + offset;
            List<DorisVectorEmbedding> batch;
            try {
                batch = service.sql.query(stmt, List.of(sourceKnowledgeBaseId),
                        DorisRetrieveRepository::scanCopyRow);
            } catch (SQLException e) {
                throw new IllegalStateException("copy indices scan: " + DorisRetrieveRepository.message(e), e);
            }
            if (batch.isEmpty()) {
                break;
            }
            List<DorisVectorEmbedding> targets = new ArrayList<>();
            for (DorisVectorEmbedding src : batch) {
                if (!sourceToTargetChunkIdMap.containsKey(src.chunkId)) {
                    log.warn("[Doris] Source chunk {} not in target mapping", src.chunkId);
                    continue;
                }
                String targetChunkId = sourceToTargetChunkIdMap.get(src.chunkId);
                if (sourceToTargetKbIdMap == null
                        || !sourceToTargetKbIdMap.containsKey(src.knowledgeId)) {
                    log.warn("[Doris] Source knowledge {} not in target mapping", src.knowledgeId);
                    continue;
                }
                String targetKnowledgeId = sourceToTargetKbIdMap.get(src.knowledgeId);
                DorisVectorEmbedding target = new DorisVectorEmbedding();
                target.id = UUID.randomUUID().toString();
                target.content = src.content;
                target.sourceId = DorisSql.translateSourceId(src.sourceId, src.chunkId,
                        targetChunkId);
                target.sourceType = src.sourceType;
                target.chunkId = targetChunkId;
                target.knowledgeId = targetKnowledgeId;
                target.knowledgeBaseId = targetKnowledgeBaseId;
                target.tagId = src.tagId;
                target.isEnabled = src.isEnabled;
                target.embedding = src.embedding;
                targets.add(target);
            }
            if (!targets.isEmpty()) {
                try {
                    insertRows(table, targets);
                } catch (SQLException e) {
                    throw new IllegalStateException("copy indices insert: " + DorisRetrieveRepository.message(e), e);
                }
                totalCopied += targets.size();
            }
            if (batch.size() < DorisRetrieveRepository.COPY_PAGE_SIZE) {
                break;
            }
            offset += DorisRetrieveRepository.COPY_PAGE_SIZE;
        }
        log.info("[Doris] CopyIndices done, dim={}, copied={}", dimension, totalCopied);
    }

    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            return;
        }
        DorisCompatMode compatMode = service.resolveCompatModeOrThrow();
        if (!compatMode.usesRewriteChunkUpdates()) {
            batchUpdateChunkEnabledStatusLegacy(chunkStatusMap);
            return;
        }
        rewriteChunkRows(new ArrayList<>(chunkStatusMap.keySet()), row -> {
            Boolean enabled = chunkStatusMap.get(row.chunkId);
            if (enabled == null || row.isEnabled == enabled) {
                return false;
            }
            row.isEnabled = enabled;
            return true;
        }, "rewrite is_enabled");
    }

    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            return;
        }
        DorisCompatMode compatMode = service.resolveCompatModeOrThrow();
        if (!compatMode.usesRewriteChunkUpdates()) {
            batchUpdateChunkTagIDLegacy(chunkTagMap);
            return;
        }
        rewriteChunkRows(new ArrayList<>(chunkTagMap.keySet()), row -> {
            String tagId = chunkTagMap.get(row.chunkId);
            if (tagId == null || tagId.equals(row.tagId)) {
                return false;
            }
            row.tagId = tagId;
            return true;
        }, "rewrite tag_id");
    }

    /** 对照 {@code rewriteChunkRows}：跨表读整行 → 变更 → replaceRows 写回。 */
    void rewriteChunkRows(List<String> chunkIds, Predicate<DorisVectorEmbedding> mutate,
                                  String action) {
        if (chunkIds.isEmpty()) {
            return;
        }
        List<String> tables;
        try {
            tables = service.listEmbeddingTables();
        } catch (RuntimeException e) {
            throw new IllegalStateException("list tables: " + e.getMessage(), e);
        }
        for (String table : tables) {
            List<DorisVectorEmbedding> rows;
            try {
                rows = loadRowsByChunkIds(table, chunkIds);
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "load chunk rows from " + table + ": " + DorisRetrieveRepository.message(e), e);
            }
            List<DorisVectorEmbedding> updated = new ArrayList<>();
            for (DorisVectorEmbedding row : rows) {
                if (mutate.test(row)) {
                    updated.add(row);
                }
            }
            if (updated.isEmpty()) {
                continue;
            }
            try {
                replaceRows(table, updated);
            } catch (SQLException e) {
                throw new IllegalStateException(action + " in " + table + ": " + DorisRetrieveRepository.message(e), e);
            }
        }
    }

    // ── legacy：Stream Load partial update 路径 ────────────────────────────

    void batchUpdateChunkEnabledStatusLegacy(Map<String, Boolean> chunkStatusMap) {
        Map<String, List<RowLocation>> mapping = lookupChunkRowKeys(
                new ArrayList<>(chunkStatusMap.keySet()));
        Map<String, List<Map<String, Object>>> byTable = new LinkedHashMap<>();
        for (Map.Entry<String, List<RowLocation>> entry : mapping.entrySet()) {
            Boolean enabled = chunkStatusMap.get(entry.getKey());
            if (enabled == null) {
                continue;
            }
            for (RowLocation loc : entry.getValue()) {
                Map<String, Object> row = new TreeMap<>();
                row.put(DorisSql.FIELD_ID, loc.id());
                row.put(DorisSql.FIELD_IS_ENABLED, enabled);
                byTable.computeIfAbsent(loc.table(), k -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : byTable.entrySet()) {
            try {
                service.streamLoad.partialUpdateRows(entry.getKey(),
                        List.of(DorisSql.FIELD_ID, DorisSql.FIELD_IS_ENABLED), entry.getValue());
            } catch (RuntimeException e) {
                throw new IllegalStateException("partial update is_enabled in "
                        + entry.getKey() + ": " + e.getMessage(), e);
            }
        }
    }


    void batchUpdateChunkTagIDLegacy(Map<String, String> chunkTagMap) {
        Map<String, List<RowLocation>> mapping = lookupChunkRowKeys(
                new ArrayList<>(chunkTagMap.keySet()));
        Map<String, List<Map<String, Object>>> byTable = new LinkedHashMap<>();
        for (Map.Entry<String, List<RowLocation>> entry : mapping.entrySet()) {
            String tagId = chunkTagMap.get(entry.getKey());
            if (tagId == null) {
                continue;
            }
            for (RowLocation loc : entry.getValue()) {
                Map<String, Object> row = new TreeMap<>();
                row.put(DorisSql.FIELD_ID, loc.id());
                row.put(DorisSql.FIELD_TAG_ID, tagId);
                byTable.computeIfAbsent(loc.table(), k -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : byTable.entrySet()) {
            try {
                service.streamLoad.partialUpdateRows(entry.getKey(),
                        List.of(DorisSql.FIELD_ID, DorisSql.FIELD_TAG_ID), entry.getValue());
            } catch (RuntimeException e) {
                throw new IllegalStateException("partial update tag_id in "
                        + entry.getKey() + ": " + e.getMessage(), e);
            }
        }
    }

    /** 对照 {@code loadRowsByChunkIDs}：按 chunk_id 读整行（含 embedding）。 */
    List<DorisVectorEmbedding> loadRowsByChunkIds(String table, List<String> chunkIds)
            throws SQLException {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(chunkIds.size(), "?"));
        String stmt = "SELECT " + String.join(", ", DorisSql.COLUMNS_FOR_COPY)
                + " FROM `" + table + "` WHERE " + DorisSql.FIELD_CHUNK_ID
                + " IN (" + placeholders + ")";
        return service.sql.query(stmt, new ArrayList<>(chunkIds),
                DorisRetrieveRepository::scanCopyRow);
    }

    /** 对照 {@code rowLocation}。 */
    record RowLocation(String table, String id) {
    }

    /**
     * 对照 {@code lookupChunkRowKeys}：查给定 chunkIDs 在所有 {@code <base>_<dim>} 表中的
     * 物理位置（同一 chunk 可能在多维度表里都有副本）。
     */
    Map<String, List<RowLocation>> lookupChunkRowKeys(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        List<String> tables;
        try {
            tables = service.listEmbeddingTables();
        } catch (RuntimeException e) {
            throw new IllegalStateException("list tables: " + e.getMessage(), e);
        }
        if (tables.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(chunkIds.size(), "?"));
        Map<String, List<RowLocation>> out = new LinkedHashMap<>();
        for (String table : tables) {
            String stmt = "SELECT " + DorisSql.FIELD_ID + ", " + DorisSql.FIELD_CHUNK_ID
                    + " FROM `" + table + "` WHERE " + DorisSql.FIELD_CHUNK_ID
                    + " IN (" + placeholders + ")";
            try {
                List<Map.Entry<String, String>> pairs = service.sql.query(stmt,
                        new ArrayList<>(chunkIds),
                        row -> Map.entry(row.string(0), row.string(1)));
                for (Map.Entry<String, String> pair : pairs) {
                    out.computeIfAbsent(pair.getValue(), k -> new ArrayList<>())
                            .add(new RowLocation(table, pair.getKey()));
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "lookup chunk row keys in " + table + ": " + DorisRetrieveRepository.message(e), e);
            }
        }
        return out;
    }

    // ── 迁移 ────────────────────────────────────────────────────────────────

    /**
     * 对照 {@code ValidateKnowledgeIndexMove}：ANN DUPLICATE KEY 表的"替换"是
     * delete + insert，失败的 insert 会丢掉唯一的向量副本、且改物理 id 会破坏
     * 稳定的 source-ID 身份 → 内积副本模式不支持 reuse_vectors 搬移。
     */
    void validateKnowledgeIndexMove() {
        DorisCompatMode mode = service.resolveCompatModeOrThrow();
        if (mode.usesRewriteChunkUpdates()) {
            throw new IllegalStateException(
                    "reuse_vectors move is not supported by Doris ANN tables; use reparse mode");
        }
    }

    void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        validateKnowledgeIndexMove();
        if (dimension <= 0) {
            throw new IllegalStateException("invalid embedding dimension");
        }
        String stmt = "UPDATE `" + service.getTableName(dimension) + "` SET "
                + DorisSql.FIELD_KNOWLEDGE_BASE_ID + " = ?, " + DorisSql.FIELD_TAG_ID
                + " = '' WHERE " + DorisSql.FIELD_KNOWLEDGE_BASE_ID + " = ? AND "
                + DorisSql.FIELD_KNOWLEDGE_ID + " = ?";
        service.sql.execute(stmt, List.of(targetKb, sourceKb, knowledgeId));
    }
}
