package com.ragagent.retrieval.engine.milvus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.milvus.MilvusRestClient.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Milvus 引擎的写入/删除/批量更新/拷贝簇：新 UUID 行主键 Upsert、按字段 in-filter 删除、
 * "查整行→改字段→回写"批量更新（enabled 失败聚合冒泡 / tag 只 WARN）、offset 分页拷贝。
 */
final class MilvusWriteOps {

    private static final Logger log = LoggerFactory.getLogger(MilvusWriteOps.class);

    private final MilvusRetrieveRepository service;

    MilvusWriteOps(MilvusRetrieveRepository service) {
        this.service = service;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        log.debug("[Milvus] Saving index for chunk ID: {}", indexInfo.chunkId);
        MilvusVectorEmbedding row = MilvusRetrieveRepository.toEmbedding(indexInfo, params);
        if (row.embedding == null || row.embedding.length == 0) {
            IllegalStateException e = new IllegalStateException(
                    "empty embedding vector for chunk ID: " + indexInfo.chunkId);
            log.error("[Milvus] {}", e.getMessage());
            throw e;
        }
        int dimension = row.embedding.length;
        service.ensureCollection(dimension);
        row.id = UUID.randomUUID().toString();
        ArrayNode rows = Json.array();
        rows.add(MilvusRetrieveRepository.rowNode(row));
        try {
            service.client.upsert(service.collectionName(dimension), rows);
        } catch (RuntimeException e) {
            log.error("[Milvus] Failed to save index: {}", e.getMessage());
            throw new IllegalStateException(e.getMessage() == null ? e.toString()
                    : e.getMessage(), e);
        }
        log.info("[Milvus] Successfully saved index for chunk ID: {}", indexInfo.chunkId);
    }

    /** 批量保存：按维度分组（升序确定性）→ 每组一次 Upsert。 */
    void batchSave(List<IndexInfo> embeddingList, Map<String, Object> params)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[Milvus] Empty list provided to BatchSave, skipping");
            return;
        }
        log.info("[Milvus] Batch saving {} indices", embeddingList.size());
        Map<Integer, List<MilvusVectorEmbedding>> byDimension = new TreeMap<>();
        for (IndexInfo info : embeddingList) {
            MilvusVectorEmbedding row = MilvusRetrieveRepository.toEmbedding(info, params);
            if (row.embedding == null || row.embedding.length == 0) {
                log.warn("[Milvus] Skipping empty embedding for chunk ID: {}", info.chunkId);
                continue;
            }
            byDimension.computeIfAbsent(row.embedding.length, k -> new ArrayList<>()).add(row);
        }
        if (byDimension.isEmpty()) {
            log.warn("[Milvus] No valid points to save after filtering");
            return;
        }
        int totalSaved = 0;
        for (Map.Entry<Integer, List<MilvusVectorEmbedding>> entry : byDimension.entrySet()) {
            int dimension = entry.getKey();
            service.ensureCollection(dimension);
            String collection = service.collectionName(dimension);
            ArrayNode rows = Json.array();
            for (MilvusVectorEmbedding row : entry.getValue()) {
                row.id = UUID.randomUUID().toString();
                rows.add(MilvusRetrieveRepository.rowNode(row));
            }
            try {
                service.client.upsert(collection, rows);
            } catch (RuntimeException e) {
                log.error("[Milvus] Failed to execute batch operation for dimension {}: {}",
                        dimension, e.getMessage());
                throw new IllegalStateException("failed to batch save (dimension " + dimension
                        + "): " + e.getMessage(), e);
            }
            totalSaved += entry.getValue().size();
            log.info("[Milvus] Saved {} points to collection {}", entry.getValue().size(),
                    collection);
        }
        log.info("[Milvus] Successfully batch saved {} indices", totalSaved);
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(MilvusRetrieveRepository.FIELD_CHUNK_ID, chunkIdList, dimension,
                "Empty chunk ID list provided for deletion, skipping",
                "failed to delete by chunk IDs");
    }

    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByField(MilvusRetrieveRepository.FIELD_KNOWLEDGE_ID, knowledgeIdList, dimension,
                "Empty knowledge ID list provided for deletion, skipping",
                "failed to delete by knowledge IDs");
    }

    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(MilvusRetrieveRepository.FIELD_SOURCE_ID, sourceIdList, dimension,
                "Empty Source ID list provided for deletion, skipping",
                "failed to delete by source IDs");
    }


    void deleteByField(String field, List<String> ids, int dimension, String emptyWarning,
                               String errorPrefix) {
        if (ids == null || ids.isEmpty()) {
            log.warn("[Milvus] {}", emptyWarning);
            return;
        }
        String collection = service.collectionName(dimension);
        log.info("[Milvus] Deleting indices by {} from {}, count: {}", field, collection,
                ids.size());
        try {
            service.client.delete(collection, MilvusRetrieveRepository.inFilter(field, ids));
        } catch (RuntimeException e) {
            log.error("[Milvus] {}: {}", errorPrefix, e.getMessage());
            throw new IllegalStateException(errorPrefix + ": " + e.getMessage(), e);
        }
        log.info("[Milvus] Successfully deleted documents by {}", field);
    }

    // ── 批量更新（查整行 → 改字段 → Upsert 回写，照 Go） ──────────────────

    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[Milvus] Empty chunk status map provided, skipping");
            return;
        }
        log.info("[Milvus] Batch updating chunk enabled status, count: {}", chunkStatusMap.size());
        List<String> collections = service.listCollectionsOrThrow();
        List<String> enabledChunkIds = new ArrayList<>();
        List<String> disabledChunkIds = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                enabledChunkIds.add(entry.getKey());
            } else {
                disabledChunkIds.add(entry.getKey());
            }
        }
        List<String> failures = new ArrayList<>();
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            try {
                updateEnabledInCollection(collection, enabledChunkIds, true);
            } catch (RuntimeException e) {
                failures.add("update enabled chunks in " + collection + ": " + e.getMessage());
            }
            try {
                updateEnabledInCollection(collection, disabledChunkIds, false);
            } catch (RuntimeException e) {
                failures.add("update disabled chunks in " + collection + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            String joined = String.join("; ", failures);
            log.warn("[Milvus] Failed to update chunk enabled status: {}", joined);
            throw new IllegalStateException(joined);
        }
        log.info("[Milvus] Batch update chunk enabled status completed");
    }


    void updateEnabledInCollection(String collection, List<String> chunkIds,
                                           boolean enabled) {
        if (chunkIds.isEmpty()) {
            return;
        }
        List<MilvusVectorEmbedding> rows = searchByFilter(collection,
                MilvusFilter.Condition.in(MilvusRetrieveRepository.FIELD_CHUNK_ID, chunkIds));
        if (rows.isEmpty()) {
            return;
        }
        ArrayNode data = Json.array();
        for (MilvusVectorEmbedding row : rows) {
            row.isEnabled = enabled;
            data.add(MilvusRetrieveRepository.rowNode(row));
        }
        service.client.upsert(collection, data);
    }

    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[Milvus] Empty chunk tag map provided, skipping");
            return;
        }
        log.info("[Milvus] Batch updating chunk tag ID, count: {}", chunkTagMap.size());
        List<String> collections = service.listCollectionsOrThrow();
        Map<String, List<String>> tagGroups = new TreeMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            tagGroups.computeIfAbsent(entry.getValue() == null ? "" : entry.getValue(),
                    k -> new ArrayList<>()).add(entry.getKey());
        }
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            for (Map.Entry<String, List<String>> group : tagGroups.entrySet()) {
                List<MilvusVectorEmbedding> rows;
                try {
                    rows = searchByFilter(collection,
                            MilvusFilter.Condition.in(MilvusRetrieveRepository.FIELD_CHUNK_ID, group.getValue()));
                } catch (RuntimeException e) {
                    log.warn("[Milvus] Failed to search chunks in {}: {}", collection,
                            e.getMessage());
                    continue;
                }
                if (rows.isEmpty()) {
                    continue;
                }
                ArrayNode data = Json.array();
                for (MilvusVectorEmbedding row : rows) {
                    row.tagId = group.getKey();
                    data.add(MilvusRetrieveRepository.rowNode(row));
                }
                try {
                    service.client.upsert(collection, data);
                } catch (RuntimeException e) {
                    log.warn("[Milvus] Failed to update chunks in {}: {}", collection,
                            e.getMessage());
                }
            }
        }
        log.info("[Milvus] Batch update chunk tag ID completed");
    }

    /** 对照 {@code searchByFilter}：Query（无分数的整行读取，供更新/拷贝/move 用）。 */
    List<MilvusVectorEmbedding> searchByFilter(String collection,
                                               MilvusFilter.Condition condition) {
        String filter;
        try {
            filter = MilvusFilter.expr(condition);
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        JsonNode rows = service.client.query(collection, filter, List.of("*"), null, null);
        List<MilvusVectorEmbedding> out = new ArrayList<>();
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                out.add(MilvusRetrieveRepository.fromNode(row));
            }
        }
        return out;
    }

    // ── CopyIndices（照 Go：offset 分页 + 三态 SourceID + isEnabled 沿用源值） ──

    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        log.info("[Milvus] Copying indices from source knowledge base {} to target knowledge base"
                + " {}, count: {}, dimension: {}", sourceKnowledgeBaseId, targetKnowledgeBaseId,
                sourceToTargetChunkIdMap == null ? 0 : sourceToTargetChunkIdMap.size(), dimension);
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[Milvus] Empty mapping, skipping copy");
            return;
        }
        String collection = service.collectionName(dimension);
        service.ensureCollection(dimension);
        MilvusFilter.Condition filter = MilvusFilter.Condition.equal(MilvusRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID,
                sourceKnowledgeBaseId);
        int offset = 0;
        int totalCopied = 0;
        while (true) {
            JsonNode page;
            try {
                page = service.client.query(collection, MilvusFilter.expr(filter), List.of("*"),
                        MilvusRetrieveRepository.COPY_PAGE_SIZE, offset);
            } catch (RuntimeException e) {
                log.error("[Milvus] Failed to query source points: {}", e.getMessage());
                throw new IllegalStateException(e.getMessage(), e);
            }
            int pageSize = page == null || !page.isArray() ? 0 : page.size();
            if (pageSize == 0) {
                break;
            }
            ArrayNode targets = Json.array();
            for (JsonNode node : page) {
                MilvusVectorEmbedding source = MilvusRetrieveRepository.fromNode(node);
                String targetChunkId = sourceToTargetChunkIdMap.get(source.chunkId);
                if (targetChunkId == null) {
                    log.warn("[Milvus] Source chunk {} not found in target mapping, skipping",
                            source.chunkId);
                    continue;
                }
                String targetKnowledgeId = sourceToTargetKbIdMap == null ? null
                        : sourceToTargetKbIdMap.get(source.knowledgeId);
                if (targetKnowledgeId == null) {
                    log.warn("[Milvus] Source knowledge {} not found in target mapping, skipping",
                            source.knowledgeId);
                    continue;
                }
                MilvusVectorEmbedding target = new MilvusVectorEmbedding();
                target.id = UUID.randomUUID().toString();
                target.content = source.content;
                target.sourceId = MilvusRetrieveRepository.translateSourceId(source.sourceId, source.chunkId, targetChunkId);
                target.sourceType = source.sourceType;
                target.chunkId = targetChunkId;
                target.knowledgeId = targetKnowledgeId;
                target.knowledgeBaseId = targetKnowledgeBaseId;
                target.tagId = source.tagId;
                target.embedding = source.embedding;
                target.isEnabled = source.isEnabled;
                targets.add(MilvusRetrieveRepository.rowNode(target));
            }
            if (!targets.isEmpty()) {
                try {
                    service.client.upsert(collection, targets);
                } catch (RuntimeException e) {
                    log.error("[Milvus] Failed to batch upsert target points: {}", e.getMessage());
                    throw new IllegalStateException(e.getMessage(), e);
                }
                totalCopied += targets.size();
                log.info("[Milvus] Successfully copied batch, batch size: {}, total copied: {}",
                        targets.size(), totalCopied);
            }
            if (pageSize < MilvusRetrieveRepository.COPY_PAGE_SIZE) {
                break;
            }
            offset += pageSize;
        }
        log.info("[Milvus] Index copy completed, total copied: {}", totalCopied);
    }
}
