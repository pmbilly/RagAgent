package com.ragagent.retrieval.engine.qdrant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qdrant 引擎的写入/删除/批量更新/拷贝簇：点构造（新 UUID 点 ID + payload 清洗）、
 * 按维度分组 100 分片 upsert、按字段过滤删除、跨集合 SetPayload 批量更新、
 * CopyIndices 分页拷贝（64/页，含向量回搬与 SourceID 三态翻译）。
 */
final class QdrantWriteOps {

    private static final Logger log = LoggerFactory.getLogger(QdrantWriteOps.class);

    private final QdrantRetrieveRepository service;

    QdrantWriteOps(QdrantRetrieveRepository service) {
        this.service = service;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        log.debug("[Qdrant] Saving index for chunk ID: {}", indexInfo.chunkId);
        QdrantVectorEmbedding row = QdrantRetrieveRepository.toEmbedding(indexInfo, params);
        if (row.embedding == null || row.embedding.length == 0) {
            IllegalStateException e = new IllegalStateException(
                    "empty embedding vector for chunk ID: " + indexInfo.chunkId);
            log.error("[Qdrant] {}", e.getMessage());
            throw e;
        }
        int dimension = row.embedding.length;
        service.ensureCollection(dimension);
        String collection = service.collectionName(dimension);
        String pointId = UUID.randomUUID().toString();
        try {
            service.client.request("PUT", "/collections/" + collection + "/points",
                    upsertBody(List.of(pointBody(pointId, row))));
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to save index: {}", e.getMessage());
            throw new IllegalStateException("failed to save index for chunk ID "
                    + indexInfo.chunkId + ": " + e.getMessage(), e);
        }
        log.info("[Qdrant] Successfully saved index for chunk ID: {}, point ID: {}",
                indexInfo.chunkId, pointId);
    }

    void batchSave(List<IndexInfo> embeddingList, Map<String, Object> params)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[Qdrant] Empty list provided to BatchSave, skipping");
            return;
        }
        log.info("[Qdrant] Batch saving {} indices", embeddingList.size());
        Map<Integer, List<ObjectNode>> pointsByDimension = new TreeMap<>();
        for (IndexInfo info : embeddingList) {
            QdrantVectorEmbedding row = QdrantRetrieveRepository.toEmbedding(info, params);
            if (row.embedding == null || row.embedding.length == 0) {
                log.warn("[Qdrant] Skipping empty embedding for chunk ID: {}", info.chunkId);
                continue;
            }
            int dimension = row.embedding.length;
            pointsByDimension.computeIfAbsent(dimension, k -> new ArrayList<>())
                    .add(pointBody(UUID.randomUUID().toString(), row));
        }
        if (pointsByDimension.isEmpty()) {
            log.warn("[Qdrant] No valid points to save after filtering");
            return;
        }
        int totalSaved = 0;
        for (Map.Entry<Integer, List<ObjectNode>> entry : pointsByDimension.entrySet()) {
            int dimension = entry.getKey();
            service.ensureCollection(dimension);
            String collection = service.collectionName(dimension);
            List<ObjectNode> points = entry.getValue();
            for (int i = 0; i < points.size(); i += QdrantRetrieveRepository.UPSERT_BATCH_SIZE) {
                List<ObjectNode> batch = points.subList(i,
                        Math.min(i + QdrantRetrieveRepository.UPSERT_BATCH_SIZE, points.size()));
                try {
                    service.client.request("PUT", "/collections/" + collection + "/points",
                            upsertBody(batch));
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "failed to upsert batch: " + e.getMessage(), e);
                }
            }
            totalSaved += points.size();
            log.info("[Qdrant] Saved {} points to collection {}", points.size(), collection);
        }
        log.info("[Qdrant] Successfully batch saved {} indices", totalSaved);
    }


    static ObjectNode upsertBody(List<ObjectNode> points) {
        ObjectNode body = QdrantRestClient.object();
        ArrayNode array = body.putArray("points");
        points.forEach(array::add);
        return body;
    }

    /** 对照 {@code PointStruct}：id + vector + payload。 */
    static ObjectNode pointBody(String pointId, QdrantVectorEmbedding row) {
        ObjectNode point = QdrantRestClient.object();
        point.put("id", pointId);
        ArrayNode vector = point.putArray("vector");
        for (float v : row.embedding) {
            vector.add(v);
        }
        point.set("payload", createPayload(row));
        return point;
    }

    /** 对照 {@code createPayload}：payload 键序按写入序（Go map → 库端无键序约束）。 */
    static ObjectNode createPayload(QdrantVectorEmbedding row) {
        ObjectNode payload = QdrantRestClient.object();
        payload.put(QdrantRetrieveRepository.FIELD_CONTENT, QdrantRetrieveRepository.sanitize(row.content));
        payload.put(QdrantRetrieveRepository.FIELD_SOURCE_ID, QdrantRetrieveRepository.sanitize(row.sourceId));
        payload.put(QdrantRetrieveRepository.FIELD_SOURCE_TYPE, row.sourceType);
        payload.put(QdrantRetrieveRepository.FIELD_CHUNK_ID, QdrantRetrieveRepository.sanitize(row.chunkId));
        payload.put(QdrantRetrieveRepository.FIELD_KNOWLEDGE_ID, QdrantRetrieveRepository.sanitize(row.knowledgeId));
        payload.put(QdrantRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID, QdrantRetrieveRepository.sanitize(row.knowledgeBaseId));
        payload.put(QdrantRetrieveRepository.FIELD_TAG_ID, QdrantRetrieveRepository.sanitize(row.tagId));
        payload.put(QdrantRetrieveRepository.FIELD_IS_ENABLED, row.isEnabled);
        return payload;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(QdrantRetrieveRepository.FIELD_CHUNK_ID, chunkIdList, dimension, "chunk IDs");
    }

    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByField(QdrantRetrieveRepository.FIELD_KNOWLEDGE_ID, knowledgeIdList, dimension, "knowledge IDs");
    }

    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByField(QdrantRetrieveRepository.FIELD_SOURCE_ID, sourceIdList, dimension, "source IDs");
    }


    void deleteByField(String field, List<String> ids, int dimension, String subject) {
        if (ids == null || ids.isEmpty()) {
            log.warn("[Qdrant] Empty {} list provided for deletion, skipping", subject);
            return;
        }
        String collection = service.collectionName(dimension);
        ObjectNode body = QdrantRestClient.object();
        body.set("filter", QdrantRetrieveRepository.mustOnly(QdrantRetrieveRepository.matchAny(field, ids)));
        try {
            // 照 Go：Delete 不带 wait（异步默认）。
            service.client.request("POST", "/collections/" + collection + "/points/delete", body);
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to delete by {}: {}", subject, e.getMessage());
            throw new IllegalStateException(
                    "failed to delete by " + subject + ": " + e.getMessage(), e);
        }
    }

    // ── 批量更新（跨集合 SetPayload） ──────────────────────────────────────

    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[Qdrant] Empty chunk status map provided, skipping");
            return;
        }
        log.info("[Qdrant] Batch updating chunk enabled status, count: {}", chunkStatusMap.size());
        List<String> collections = listCollectionsOrThrow();
        List<String> enabledChunkIds = new ArrayList<>();
        List<String> disabledChunkIds = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                enabledChunkIds.add(entry.getKey());
            } else {
                disabledChunkIds.add(entry.getKey());
            }
        }
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            if (!enabledChunkIds.isEmpty()) {
                try {
                    setPayload(collection, QdrantRetrieveRepository.FIELD_IS_ENABLED, true,
                            QdrantRetrieveRepository.matchAny(QdrantRetrieveRepository.FIELD_CHUNK_ID, enabledChunkIds));
                } catch (RuntimeException e) {
                    log.warn("[Qdrant] Failed to update enabled chunks in {}: {}",
                            collection, e.getMessage());
                }
            }
            if (!disabledChunkIds.isEmpty()) {
                try {
                    setPayload(collection, QdrantRetrieveRepository.FIELD_IS_ENABLED, false,
                            QdrantRetrieveRepository.matchAny(QdrantRetrieveRepository.FIELD_CHUNK_ID, disabledChunkIds));
                } catch (RuntimeException e) {
                    log.warn("[Qdrant] Failed to update disabled chunks in {}: {}",
                            collection, e.getMessage());
                }
            }
        }
        log.info("[Qdrant] Batch update chunk enabled status completed");
    }

    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[Qdrant] Empty chunk tag map provided, skipping");
            return;
        }
        log.info("[Qdrant] Batch updating chunk tag ID, count: {}", chunkTagMap.size());
        List<String> collections = listCollectionsOrThrow();
        Map<String, List<String>> tagGroups = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            tagGroups.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                    .add(entry.getKey());
        }
        for (String collection : collections) {
            if (!service.isPrefixed(collection)) {
                continue;
            }
            for (Map.Entry<String, List<String>> group : tagGroups.entrySet()) {
                try {
                    setPayload(collection, QdrantRetrieveRepository.FIELD_TAG_ID, group.getKey(),
                            QdrantRetrieveRepository.matchAny(QdrantRetrieveRepository.FIELD_CHUNK_ID, group.getValue()));
                } catch (RuntimeException e) {
                    log.warn("[Qdrant] Failed to update chunks with tag_id {} in {}: {}",
                            group.getKey(), collection, e.getMessage());
                }
            }
        }
        log.info("[Qdrant] Batch update chunk tag ID completed");
    }


    List<String> listCollectionsOrThrow() {
        try {
            return service.listCollections();
        } catch (RuntimeException e) {
            log.error("[Qdrant] Failed to list collections: {}", e.getMessage());
            throw new IllegalStateException("failed to list collections: " + e.getMessage(), e);
        }
    }

    /** 对照 {@code SetPayloadPoints}：单字段 payload + 选择器条件（照 Go 的批量更新调用点）。 */
    void setPayload(String collection, String field, Object value, ObjectNode selector) {
        ObjectNode payload = QdrantRestClient.object();
        if (value instanceof Boolean b) {
            payload.put(field, b.booleanValue());
        } else if (value instanceof Number n) {
            payload.put(field, n.longValue());
        } else {
            payload.put(field, String.valueOf(value));
        }
        ObjectNode body = QdrantRestClient.object();
        body.set("payload", payload);
        body.set("filter", QdrantRetrieveRepository.mustOnly(selector));
        service.client.request("POST", "/collections/" + collection + "/points/payload?wait=true", body);
    }

    // ── CopyIndices（照全文，含向量回搬） ─────────────────────────────────

    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        log.info("[Qdrant] Copying indices from source knowledge base {} to target knowledge base"
                        + " {}, count: {}, dimension: {}", sourceKnowledgeBaseId,
                targetKnowledgeBaseId,
                sourceToTargetChunkIdMap == null ? 0 : sourceToTargetChunkIdMap.size(), dimension);
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[Qdrant] Empty mapping, skipping copy");
            return;
        }
        String collection = service.collectionName(dimension);
        service.ensureCollection(dimension);
        String offset = null;
        int totalCopied = 0;
        while (true) {
            ObjectNode body = QdrantRestClient.object();
            body.set("filter", QdrantRetrieveRepository.mustOnly(QdrantRetrieveRepository.matchValue(QdrantRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID,
                    sourceKnowledgeBaseId)));
            body.put("limit", QdrantRetrieveRepository.COPY_PAGE_SIZE);
            if (offset != null) {
                body.put("offset", offset);
            }
            body.put("with_payload", true);
            body.put("with_vector", true);
            JsonNode scroll;
            try {
                scroll = service.client.request("POST", "/collections/" + collection + "/points/scroll",
                        body);
            } catch (RuntimeException e) {
                log.error("[Qdrant] Failed to query source points: {}", e.getMessage());
                throw new IllegalStateException(e.getMessage(), e);
            }
            JsonNode points = scroll == null ? null : scroll.get("points");
            int pointsCount = points == null ? 0 : points.size();
            if (pointsCount == 0) {
                break;
            }
            log.info("[Qdrant] Found {} source points in batch", pointsCount);
            List<ObjectNode> targetPoints = new ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                String sourceChunkId = payload.path(QdrantRetrieveRepository.FIELD_CHUNK_ID).asText("");
                String sourceKnowledgeId = payload.path(QdrantRetrieveRepository.FIELD_KNOWLEDGE_ID).asText("");
                String originalSourceId = payload.path(QdrantRetrieveRepository.FIELD_SOURCE_ID).asText("");
                if (!sourceToTargetChunkIdMap.containsKey(sourceChunkId)) {
                    log.warn("[Qdrant] Source chunk {} not found in target mapping, skipping",
                            sourceChunkId);
                    continue;
                }
                String targetChunkId = sourceToTargetChunkIdMap.get(sourceChunkId);
                if (sourceToTargetKbIdMap == null
                        || !sourceToTargetKbIdMap.containsKey(sourceKnowledgeId)) {
                    log.warn("[Qdrant] Source knowledge {} not found in target mapping, skipping",
                            sourceKnowledgeId);
                    continue;
                }
                String targetKnowledgeId = sourceToTargetKbIdMap.get(sourceKnowledgeId);
                String targetSourceId = QdrantRetrieveRepository.translateSourceId(originalSourceId, sourceChunkId,
                        targetChunkId);
                boolean isEnabled = !payload.has(QdrantRetrieveRepository.FIELD_IS_ENABLED)
                        || payload.path(QdrantRetrieveRepository.FIELD_IS_ENABLED).asBoolean(true);
                JsonNode vectorNode = point.get("vector");
                if (vectorNode == null || !vectorNode.isArray() || vectorNode.isEmpty()) {
                    log.warn("[Qdrant] No vectors found for source point with chunk {}, skipping",
                            sourceChunkId);
                    continue;
                }
                QdrantVectorEmbedding target = new QdrantVectorEmbedding();
                target.content = payload.path(QdrantRetrieveRepository.FIELD_CONTENT).asText("");
                target.sourceId = targetSourceId;
                target.sourceType = payload.path(QdrantRetrieveRepository.FIELD_SOURCE_TYPE).asInt(0);
                target.chunkId = targetChunkId;
                target.knowledgeId = targetKnowledgeId;
                target.knowledgeBaseId = targetKnowledgeBaseId;
                target.tagId = payload.path(QdrantRetrieveRepository.FIELD_TAG_ID).asText("");
                target.isEnabled = isEnabled;
                target.embedding = new float[vectorNode.size()];
                for (int i = 0; i < vectorNode.size(); i++) {
                    target.embedding[i] = (float) vectorNode.get(i).asDouble();
                }
                targetPoints.add(pointBody(UUID.randomUUID().toString(), target));
            }
            if (!targetPoints.isEmpty()) {
                try {
                    service.client.request("PUT", "/collections/" + collection + "/points",
                            upsertBody(targetPoints));
                } catch (RuntimeException e) {
                    log.error("[Qdrant] Failed to batch upsert target points: {}", e.getMessage());
                    throw new IllegalStateException(
                            "failed to batch upsert target points during copy: "
                                    + e.getMessage(), e);
                }
                totalCopied += targetPoints.size();
                log.info("[Qdrant] Successfully copied batch, batch size: {}, total copied: {}",
                        targetPoints.size(), totalCopied);
            }
            offset = points.get(pointsCount - 1).path("id").asText();
            if (pointsCount < QdrantRetrieveRepository.COPY_PAGE_SIZE) {
                break;
            }
        }
        log.info("[Qdrant] Index copy completed, total copied: {}", totalCopied);
    }
}
