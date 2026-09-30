package com.ragagent.retrieval.engine.tencentvectordb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbBm25.SparseVecItem;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRestClient.Json;

import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRetrieveRepository.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tencent VectorDB 引擎的写入/删除/批量更新/拷贝簇：BM25 编码后 Upsert（buildIndex=true）、
 * filter 串删除、Update API 批量改字段、offset 分页拷贝（sha256 三态 SourceID）。
 */
final class TencentVectorDbWriteOps {

    private static final Logger log = LoggerFactory.getLogger(TencentVectorDbWriteOps.class);

    private final TencentVectorDbRetrieveRepository service;

    TencentVectorDbWriteOps(TencentVectorDbRetrieveRepository service) {
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
        Map<Integer, List<Document>> byDimension = new TreeMap<>();
        for (IndexInfo info : indexInfoList) {
            Document doc = TencentVectorDbRetrieveRepository.toDocument(info, params);
            if (doc.vector.length == 0) {
                log.warn("[TencentVectorDB] skip empty embedding for chunk_id={}", info.chunkId);
                continue;
            }
            byDimension.computeIfAbsent(doc.vector.length, k -> new ArrayList<>()).add(doc);
        }
        if (byDimension.isEmpty()) {
            return;
        }
        TencentVectorDbBm25 encoder = service.bm25();
        for (Map.Entry<Integer, List<Document>> entry : byDimension.entrySet()) {
            int dimension = entry.getKey();
            List<Document> docs = entry.getValue();
            service.ensureCollection(dimension);
            ArrayNode documents = Json.array();
            for (Document doc : docs) {
                doc.sparseVector = encoder.encodeText(doc.content);
                documents.add(documentNode(doc));
            }
            try {
                service.client.upsert(service.databaseName, service.collectionName(dimension), documents, true);
            } catch (RuntimeException e) {
                throw new IllegalStateException("tencent vectordb batch save "
                        + service.collectionName(dimension) + ": " + e.getMessage(), e);
            }
        }
    }

    /** 文档体：{@code id/vector/sparse_vector} + 8 个字段（照 {@code toDocument}）。 */
    static ObjectNode documentNode(Document doc) {
        ObjectNode node = Json.object();
        node.put(TencentVectorDbRetrieveRepository.FIELD_ID, doc.id == null ? "" : doc.id);
        ArrayNode vector = node.putArray(TencentVectorDbRetrieveRepository.FIELD_VECTOR);
        for (float v : doc.vector) {
            vector.add(v);
        }
        ArrayNode sparse = node.putArray(TencentVectorDbRetrieveRepository.FIELD_SPARSE_VECTOR);
        for (SparseVecItem item : doc.sparseVector) {
            ArrayNode pair = sparse.addArray();
            pair.add(item.termId());
            pair.add(item.score());
        }
        node.put(TencentVectorDbRetrieveRepository.FIELD_CONTENT, doc.content);
        node.put(TencentVectorDbRetrieveRepository.FIELD_SOURCE_ID, doc.sourceId);
        node.put(TencentVectorDbRetrieveRepository.FIELD_SOURCE_TYPE, (long) doc.sourceType);
        node.put(TencentVectorDbRetrieveRepository.FIELD_CHUNK_ID, doc.chunkId);
        node.put(TencentVectorDbRetrieveRepository.FIELD_KNOWLEDGE_ID, doc.knowledgeId);
        node.put(TencentVectorDbRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID, doc.knowledgeBaseId);
        node.put(TencentVectorDbRetrieveRepository.FIELD_TAG_ID, doc.tagId);
        node.put(TencentVectorDbRetrieveRepository.FIELD_IS_ENABLED, doc.isEnabled ? 1L : 0L);
        return node;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByFilter(dimension, TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_CHUNK_ID, chunkIdList));
    }

    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByFilter(dimension, TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_SOURCE_ID, sourceIdList));
    }

    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByFilter(dimension, TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_KNOWLEDGE_ID, knowledgeIdList));
    }


    void deleteByFilter(int dimension, String filter) {
        if (filter == null || filter.isEmpty()) {
            return;
        }
        String collection = service.collectionName(dimension);
        try {
            ObjectNode query = Json.object();
            query.put("filter", filter);
            service.client.delete(service.databaseName, collection, query);
        } catch (RuntimeException e) {
            throw new IllegalStateException("tencent vectordb delete from " + collection + ": "
                    + e.getMessage(), e);
        }
    }

    // ── 批量更新（Update API，照 Go） ──────────────────────────────────────

    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap)
            throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            return;
        }
        Map<Boolean, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
            grouped.computeIfAbsent(Boolean.TRUE.equals(entry.getValue()), k -> new ArrayList<>())
                    .add(entry.getKey());
        }
        for (Map.Entry<Boolean, List<String>> entry : grouped.entrySet()) {
            ObjectNode fields = Json.object();
            fields.put(TencentVectorDbRetrieveRepository.FIELD_IS_ENABLED, Boolean.TRUE.equals(entry.getKey()) ? 1L : 0L);
            updateChunkFields(entry.getValue(), fields);
        }
    }

    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            return;
        }
        Map<String, List<String>> grouped = new TreeMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            grouped.computeIfAbsent(entry.getValue() == null ? "" : entry.getValue(),
                    k -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            ObjectNode fields = Json.object();
            fields.put(TencentVectorDbRetrieveRepository.FIELD_TAG_ID, entry.getKey());
            updateChunkFields(entry.getValue(), fields);
        }
    }

    /** 照 {@code updateChunkFields}：跨"匹配到的集合"逐个 Update；任一失败即抛。 */
    void updateChunkFields(List<String> chunkIds, ObjectNode fields) {
        List<String> collections;
        try {
            collections = service.listCollectionNames();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "tencent vectordb list collections: " + e.getMessage(), e);
        }
        String filter = TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_CHUNK_ID, chunkIds);
        for (String collection : collections) {
            if (!service.matchesCollection(collection)) {
                continue;
            }
            try {
                ObjectNode query = Json.object();
                query.put("filter", filter);
                service.client.update(service.databaseName, collection, query, fields);
            } catch (RuntimeException e) {
                throw new IllegalStateException("tencent vectordb update chunks in " + collection
                        + ": " + e.getMessage(), e);
            }
        }
    }

    // ── CopyIndices ────────────────────────────────────────────────────────

    void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            return;
        }
        String collection = service.collectionName(dimension);
        List<String> ids = new ArrayList<>(sourceToTargetChunkIdMap.keySet());
        List<Document> embeddings = new ArrayList<>();
        long offset = 0;
        while (true) {
            ObjectNode query = Json.object();
            String filter = TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_CHUNK_ID, ids);
            if (sourceKnowledgeBaseId != null && !sourceKnowledgeBaseId.isEmpty()) {
                filter = TencentVectorDbRetrieveRepository.in(TencentVectorDbRetrieveRepository.FIELD_KNOWLEDGE_BASE_ID, List.of(sourceKnowledgeBaseId))
                        + " and " + filter;
            }
            query.put("filter", filter);
            query.put("retrieveVector", true);
            query.set("outputFields", TencentVectorDbRetrieveRepository.outputFields());
            query.put("offset", offset);
            query.put("limit", TencentVectorDbRetrieveRepository.COPY_PAGE_SIZE);
            JsonNode res;
            try {
                res = service.client.query(service.databaseName, collection, query);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "tencent vectordb query source indices: " + e.getMessage(), e);
            }
            JsonNode documents = res.path("documents");
            int pageSize = documents.isArray() ? documents.size() : 0;
            for (JsonNode node : documents) {
                Document doc = TencentVectorDbRetrieveRepository.fromDocument(node);
                String targetChunkId = sourceToTargetChunkIdMap.get(doc.chunkId);
                if (targetChunkId == null) {
                    continue;
                }
                String originalSourceId = doc.sourceId.isEmpty() ? doc.id : doc.sourceId;
                String targetSourceId = TencentVectorDbRetrieveRepository.translateSourceId(originalSourceId, doc.chunkId,
                        targetChunkId);
                doc.id = targetSourceId;
                doc.sourceId = targetSourceId;
                doc.chunkId = targetChunkId;
                doc.knowledgeBaseId = targetKnowledgeBaseId;
                String targetKnowledgeId = sourceToTargetKbIdMap == null ? null
                        : sourceToTargetKbIdMap.get(doc.knowledgeId);
                if (targetKnowledgeId != null && !targetKnowledgeId.isEmpty()) {
                    doc.knowledgeId = targetKnowledgeId;
                }
                embeddings.add(doc);
            }
            if (pageSize < TencentVectorDbRetrieveRepository.COPY_PAGE_SIZE) {
                break;
            }
            offset += TencentVectorDbRetrieveRepository.COPY_PAGE_SIZE;
        }
        if (embeddings.isEmpty()) {
            return;
        }
        TencentVectorDbBm25 encoder = service.bm25();
        ArrayNode documents = Json.array();
        for (Document doc : embeddings) {
            doc.sparseVector = encoder.encodeText(doc.content);
            documents.add(documentNode(doc));
        }
        try {
            service.client.upsert(service.databaseName, collection, documents, true);
        } catch (RuntimeException e) {
            throw new IllegalStateException("tencent vectordb copy indices: " + e.getMessage(), e);
        }
    }
}
