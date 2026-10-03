package com.ragagent.retrieval.engine.weaviate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.weaviate.WeaviateRestClient.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Weaviate 引擎的写入/删除/批量更新/拷贝簇：对象体构造（id=chunkID）与 REST 批量创建、
 * ContainsAny 批量删除、PATCH merge 批量更新（有意修正：不用整对象替换）、
 * where+offset 分页拷贝（命名向量回搬）。
 */
final class WeaviateWriteOps {

    private static final Logger log = LoggerFactory.getLogger(WeaviateWriteOps.class);

    private final WeaviateRetrieveRepository service;

    WeaviateWriteOps(WeaviateRetrieveRepository service) {
        this.service = service;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        log.debug("[Weaviate] Saving index for chunk ID: {}", indexInfo.chunkId);
        WeaviateVectorEmbedding row = WeaviateRetrieveRepository.toEmbedding(indexInfo, params);
        if (row.embedding == null || row.embedding.length == 0) {
            IllegalStateException e = new IllegalStateException(
                    "empty embedding vector for chunk ID: " + indexInfo.chunkId);
            log.error("[Weaviate] {}", e.getMessage());
            throw e;
        }
        int dimension = row.embedding.length;
        service.ensureCollection(dimension);
        ObjectNode object = objectBody(service.collectionName(dimension), row.chunkId, row);
        try {
            service.client.createObject(object);
        } catch (RuntimeException e) {
            log.error("[Weaviate] Failed to save index: {}", e.getMessage());
            throw new IllegalStateException(e.getMessage() == null
                    ? e.toString() : e.getMessage(), e);
        }
        log.info("[Weaviate] Successfully saved index for chunk ID: {}", indexInfo.chunkId);
    }

    void batchSave(List<IndexInfo> embeddingList, Map<String, Object> params)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[Weaviate] Empty list provided to BatchSave, skipping");
            return;
        }
        log.info("[Weaviate] Batch saving {} indices", embeddingList.size());
        Map<Integer, List<WeaviateVectorEmbedding>> byDimension = new TreeMap<>();
        for (IndexInfo info : embeddingList) {
            WeaviateVectorEmbedding row = WeaviateRetrieveRepository.toEmbedding(info, params);
            if (row.embedding == null || row.embedding.length == 0) {
                log.warn("[Weaviate] Skipping empty embedding for chunk ID: {}", info.chunkId);
                continue;
            }
            byDimension.computeIfAbsent(row.embedding.length, k -> new ArrayList<>()).add(row);
        }
        if (byDimension.isEmpty()) {
            log.warn("[Weaviate] No valid points to save after filtering");
            return;
        }
        int totalSaved = 0;
        for (Map.Entry<Integer, List<WeaviateVectorEmbedding>> entry : byDimension.entrySet()) {
            int dimension = entry.getKey();
            service.ensureCollection(dimension);
            String collection = service.collectionName(dimension);
            ArrayNode objects = Json.array();
            for (WeaviateVectorEmbedding row : entry.getValue()) {
                objects.add(objectBody(collection, row.chunkId, row));
            }
            try {
                JsonNode response = service.client.batchCreate(objects);
                logObjectErrors(response, "BatchSave");
            } catch (RuntimeException e) {
                log.error("[Weaviate] Failed to execute batch operation for dimension {}: {}",
                        dimension, e.getMessage());
                throw new IllegalStateException(
                        "failed to batch save (dimension " + dimension + "): " + e.getMessage(), e);
            }
            totalSaved += entry.getValue().size();
            log.info("[Weaviate] Saved {} points to collection {}", entry.getValue().size(),
                    collection);
        }
        log.info("[Weaviate] Successfully batch saved {} indices", totalSaved);
    }

    /**
     * 对象体：{@code {class, id, properties, vector}}——id = chunkID（另见类注释）。
     * 单对象路径用 {@code vector} 字段；命名向量类下服务端把
     * 单向量映射到唯一命名向量 {@code embedding}（1.28.4 实测接受）。
     */
    static ObjectNode objectBody(String className, String chunkId,
                                         WeaviateVectorEmbedding row) {
        ObjectNode object = Json.object();
        object.put("class", className);
        object.put("id", row.chunkId == null || row.chunkId.isEmpty() ? chunkId : row.chunkId);
        object.set("properties", createPayload(row));
        ArrayNode vector = object.putArray("vector");
        for (float v : row.embedding == null ? new float[0] : row.embedding) {
            vector.add(v);
        }
        return object;
    }

    /** properties 构造：键序按写入序（服务端无键序约束）。 */
    static ObjectNode createPayload(WeaviateVectorEmbedding row) {
        ObjectNode payload = Json.object();
        payload.put(WeaviateRetrieveRepository.FIELD_CONTENT, row.content == null ? "" : row.content);
        payload.put(WeaviateRetrieveRepository.FIELD_SOURCE_ID, row.sourceId == null ? "" : row.sourceId);
        payload.put(WeaviateRetrieveRepository.FIELD_SOURCE_TYPE, row.sourceType);
        payload.put(WeaviateRetrieveRepository.FIELD_CHUNK_ID, row.chunkId == null ? "" : row.chunkId);
        payload.put(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_ID, row.knowledgeId == null ? "" : row.knowledgeId);
        payload.put(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID,
                row.knowledgeBaseId == null ? "" : row.knowledgeBaseId);
        payload.put(WeaviateRetrieveRepository.FIELD_TAG_ID, row.tagId == null ? "" : row.tagId);
        payload.put(WeaviateRetrieveRepository.FIELD_IS_ENABLED, row.isEnabled);
        return payload;
    }

    /** 批量响应里的逐对象错误只记日志（对象级错误不冒泡）。 */
    static void logObjectErrors(JsonNode response, String op) {
        if (response == null || !response.isArray()) {
            return;
        }
        for (JsonNode item : response) {
            JsonNode errors = item.path("result").path("errors");
            if (errors.isObject() && errors.hasNonNull("error")) {
                JsonNode first = errors.path("error").isArray()
                        ? errors.path("error").path(0) : errors.path("error");
                log.warn("[Weaviate] {} object error: {}", op, first.path("message").asText(""));
            }
        }
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(WeaviateRetrieveRepository.FIELD_CHUNK_ID, chunkIdList, dimension, "chunk IDs",
                "Empty chunk ID list provided for deletion, skipping",
                "failed to delete by chunk IDs");
    }

    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByField(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_ID, knowledgeIdList, dimension, "knowledge IDs",
                "Empty knowledge ID list provided for deletion, skipping",
                "failed to delete by knowledge IDs");
    }

    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(WeaviateRetrieveRepository.FIELD_SOURCE_ID, sourceIdList, dimension, "Source IDs",
                "Empty Source ID list provided for deletion, skipping",
                "failed to delete by source IDs");
    }


    void deleteByField(String field, List<String> ids, int dimension, String subject,
                               String emptyWarning, String errorPrefix) {
        if (ids == null || ids.isEmpty()) {
            log.warn("[Weaviate] {}", emptyWarning);
            return;
        }
        String collection = service.collectionName(dimension);
        log.info("[Weaviate] Deleting indices by {} from {}, count: {}", subject, collection,
                ids.size());
        WeaviateGql.Where where = WeaviateGql.Where.containsAny(field)
                .valueText(ids.toArray(new String[0]));
        try {
            service.client.batchDelete(collection, where.json(), "minimal");
        } catch (RuntimeException e) {
            log.error("[Weaviate] {}: {}", errorPrefix, e.getMessage());
            throw new IllegalStateException(errorPrefix + ": " + e.getMessage(), e);
        }
        log.info("[Weaviate] Successfully deleted documents by {}", subject);
    }

    // ── 批量更新（有意修正：merge 而非整对象替换） ─────────────────────────

    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[Weaviate] Empty chunk status map provided, skipping");
            return;
        }
        log.info("[Weaviate] Batch updating chunk enabled status, count: {}",
                chunkStatusMap.size());
        List<String> collections = service.listCollectionsOrThrow();
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
                ObjectNode properties = Json.object();
                properties.put(WeaviateRetrieveRepository.FIELD_IS_ENABLED, Boolean.TRUE.equals(entry.getValue()));
                try {
                    service.client.mergeUpdate(collection, entry.getKey(), properties);
                } catch (RuntimeException e) {
                    log.error("[Weaviate] Failed to update chunk {} status in {}: {}",
                            Boolean.TRUE.equals(entry.getValue()) ? "enabled" : "disabled",
                            collection, e.getMessage());
                }
            }
        }
        log.info("[Weaviate] Batch update chunk enabled status completed");
    }

    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[Weaviate] Empty chunk tag map provided, skipping");
            return;
        }
        log.info("[Weaviate] Batch updating chunk tag ID, count: {}", chunkTagMap.size());
        List<String> collections = service.listCollectionsOrThrow();
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
                ObjectNode properties = Json.object();
                properties.put(WeaviateRetrieveRepository.FIELD_TAG_ID, entry.getValue() == null ? "" : entry.getValue());
                try {
                    service.client.mergeUpdate(collection, entry.getKey(), properties);
                } catch (RuntimeException e) {
                    log.warn("[Weaviate] Failed to update chunk {} tag ID in {}: {}",
                            entry.getKey(), collection, e.getMessage());
                }
            }
        }
        log.info("[Weaviate] Batch update chunk tag ID completed");
    }

    // ── CopyIndices（有意修正：where+offset 与命名向量） ────────────────────

    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        log.info("[Weaviate] Copying indices from {} to {}, count: {}",
                sourceKnowledgeBaseId, targetKnowledgeBaseId,
                sourceToTargetChunkIdMap == null ? 0 : sourceToTargetChunkIdMap.size());
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            return;
        }
        String collection = service.collectionName(dimension);
        WeaviateGql.Where where = WeaviateGql.Where.equal(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID)
                .valueString(sourceKnowledgeBaseId);
        int offset = 0;
        int totalCopied = 0;
        while (true) {
            String query = WeaviateGql.copyPageQuery(collection, where, WeaviateRetrieveRepository.COPY_PAGE_SIZE, offset);
            JsonNode response = service.client.graphql(query);
            JsonNode items = service.extractItems(response, collection);
            if (items == null || items.isEmpty()) {
                break;
            }
            log.info("[Weaviate] Found {} source points in batch", items.size());
            ArrayNode targets = Json.array();
            for (JsonNode item : items) {
                JsonNode additional = item.path("_additional");
                JsonNode vectors = additional.path("vectors").path(WeaviateRetrieveRepository.FIELD_EMBEDDING);
                if (!vectors.isArray() || vectors.isEmpty()) {
                    log.warn("[Weaviate] No vectors found for source point with chunk {}, skipping",
                            item.path(WeaviateRetrieveRepository.FIELD_CHUNK_ID).asText(""));
                    continue;
                }
                String sourceChunkId = item.path(WeaviateRetrieveRepository.FIELD_CHUNK_ID).asText("");
                String sourceKnowledgeId = item.path(WeaviateRetrieveRepository.FIELD_KNOWLEDGE_ID).asText("");
                String targetChunkId = sourceToTargetChunkIdMap.get(sourceChunkId);
                String targetKnowledgeId = sourceToTargetKbIdMap == null ? null
                        : sourceToTargetKbIdMap.get(sourceKnowledgeId);
                if (targetChunkId == null || targetKnowledgeId == null) {
                    continue;
                }
                String originalSourceId = item.path(WeaviateRetrieveRepository.FIELD_SOURCE_ID).asText("");
                WeaviateVectorEmbedding row = new WeaviateVectorEmbedding();
                row.content = item.path(WeaviateRetrieveRepository.FIELD_CONTENT).asText("");
                row.sourceId = WeaviateRetrieveRepository.translateSourceId(originalSourceId, sourceChunkId,
                        targetChunkId);
                row.sourceType = item.path(WeaviateRetrieveRepository.FIELD_SOURCE_TYPE).asInt(0);
                row.chunkId = targetChunkId;
                row.knowledgeId = targetKnowledgeId;
                row.knowledgeBaseId = targetKnowledgeBaseId;
                row.tagId = item.path(WeaviateRetrieveRepository.FIELD_TAG_ID).asText("");
                row.isEnabled = true; // 拷贝一律置 true（不沿用源值）
                row.embedding = new float[vectors.size()];
                for (int i = 0; i < vectors.size(); i++) {
                    row.embedding[i] = (float) vectors.get(i).asDouble();
                }
                ObjectNode object = Json.object();
                object.put("class", collection);
                object.put("id", UUID.randomUUID().toString());
                object.set("properties", createPayload(row));
                ArrayNode vector = object.putArray("vector");
                for (float v : row.embedding) {
                    vector.add(v);
                }
                targets.add(object);
            }
            if (!targets.isEmpty()) {
                try {
                    JsonNode resp = service.client.batchCreate(targets);
                    logObjectErrors(resp, "CopyIndices");
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "batch upsert failed: " + e.getMessage(), e);
                }
                totalCopied += targets.size();
                log.info("[Weaviate] Successfully copied batch, total: {}", totalCopied);
            }
            if (items.size() < WeaviateRetrieveRepository.COPY_PAGE_SIZE) {
                break;
            }
            offset += WeaviateRetrieveRepository.COPY_PAGE_SIZE;
        }
        log.info("[Weaviate] Index copy completed, total copied: {}", totalCopied);
    }
}
