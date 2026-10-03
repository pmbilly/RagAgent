package com.ragagent.retrieval.engine.elasticsearch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;

import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV7RetrieveRepository.HttpResult;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elasticsearch v7 引擎的写入/删除/批量更新/拷贝簇：bulk NDJSON、terms 删除、
 * CopyIndices（分页 + SourceID 三态，复用 V8 的文档静态面）、update_by_query 批量改状态/标签。
 */
final class ElasticsearchV7WriteOps {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchV7WriteOps.class);

    private final ElasticsearchV7RetrieveRepository service;

    ElasticsearchV7WriteOps(ElasticsearchV7RetrieveRepository service) {
        this.service = service;
    }

    /** 单条写入：显式 UUID 文档 ID 走 {@code PUT /{index}/_create/{id}}。 */
    void save(IndexInfo embedding, Map<String, Object> additionalParams) throws Exception {
        ElasticsearchV8RetrieveRepository.VectorEmbedding doc =
                ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(embedding, additionalParams);
        if (doc.embedding == null || doc.embedding.length == 0) {
            throw new IllegalStateException(
                    "empty embedding vector for chunk ID: " + embedding.chunkId);
        }
        String docId = UUID.randomUUID().toString();
        HttpResult resp = service.request("PUT", "/" + service.index + "/_create/" + docId,
                ElasticsearchV8RetrieveRepository.docJson(doc));
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to index document: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
    }

    /**
     * 批量写入：动作行 {@code { "index" : { "_id" : "<uuid>" } }}（键与值之间带空格）；
     * 响应 {@code errors:true} 只计数告警、解析失败也放行（**永不因此失败**）。
     */
    void batchSave(List<IndexInfo> embeddingList, Map<String, Object> additionalParams)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[ElasticsearchV7] Empty list provided to BatchSave, skipping");
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        int processedCount = 0;
        for (IndexInfo embedding : embeddingList) {
            ElasticsearchV8RetrieveRepository.VectorEmbedding doc =
                    ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(embedding,
                            additionalParams);
            String docId = UUID.randomUUID().toString();
            ndjson.append("{ \"index\" : { \"_id\" : \"").append(docId).append("\" } }\n");
            ndjson.append(ElasticsearchV8RetrieveRepository.docJson(doc)).append('\n');
            processedCount++;
        }
        if (processedCount == 0) {
            log.warn("[ElasticsearchV7] No valid documents to index after filtering, skipping"
                    + " bulk request");
            return;
        }
        HttpResult resp = service.request("POST", "/" + service.index + "/_bulk", ndjson.toString());
        processBulkResponse(resp, embeddingList.size());
    }

    /** 批量响应处理：只告警，不抛。 */
    void processBulkResponse(HttpResult resp, int totalDocuments) {
        if (resp.status() < 200 || resp.status() >= 300) {
            // 仅当非 2xx 且构成错误响应时才抛出（对齐"错误响应"判定语义）
            if (resp.status() >= 400) {
                throw new IllegalStateException("failed to index documents: elasticsearch"
                        + " returned " + resp.status() + ": " + resp.body());
            }
        }
        JsonNode bulkResponse;
        try {
            bulkResponse = ElasticsearchV7RetrieveRepository.MAPPER.readTree(resp.body());
        } catch (Exception e) {
            log.warn("[ElasticsearchV7] Could not parse bulk response: {}", e.toString());
            return;
        }
        if (bulkResponse != null && bulkResponse.path("errors").asBoolean(false)) {
            int errorCount = countBulkErrors(bulkResponse);
            if (errorCount > 0) {
                log.warn("[ElasticsearchV7] {}/{} documents failed to index", errorCount,
                        totalDocuments);
            }
        }
    }


    int countBulkErrors(JsonNode bulkResponse) {
        log.warn("[ElasticsearchV7] Bulk operation completed with some errors");
        int errorCount = 0;
        for (JsonNode item : bulkResponse.path("items")) {
            JsonNode indexResp = item.path("index");
            if (indexResp.has("error") && !indexResp.path("error").isNull()) {
                errorCount++;
                log.error("[ElasticsearchV7] Item error: {}", indexResp.path("error"));
            }
        }
        return errorCount;
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    // ── 删除 ────────────────────────────────────────────────────────────────

    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByFieldList(service.idField("chunk_id"), chunkIdList);
    }


    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByFieldList(service.idField("source_id"), sourceIdList);
    }


    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByFieldList(service.idField("knowledge_id"), knowledgeIdList);
    }

    /** 手拼 {@code {"query": {"terms": {field: [...]}}}}。 */
    void deleteByFieldList(String field, List<String> valueList) throws Exception {
        if (valueList == null || valueList.isEmpty()) {
            log.warn("[ElasticsearchV7] Empty {} list provided for deletion, skipping", field);
            return;
        }
        ObjectNode terms = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        ArrayNode array = terms.putArray(field);
        for (String value : valueList) {
            array.add(value);
        }
        ObjectNode body = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode().set("terms", terms));

        HttpResult resp = service.request("POST", "/" + service.index + "/_delete_by_query", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to delete by query: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
        try {
            JsonNode deleteResponse = ElasticsearchV7RetrieveRepository.MAPPER.readTree(resp.body());
            if (deleteResponse != null && deleteResponse.has("deleted")) {
                log.info("[ElasticsearchV7] Successfully deleted {} documents by {}",
                        deleteResponse.path("deleted").asLong(), field);
            } else {
                log.info("[ElasticsearchV7] Successfully deleted documents by {}", field);
            }
        } catch (Exception e) {
            log.warn("[ElasticsearchV7] Could not parse delete response: {}", e.toString());
        }
    }

    // ── CopyIndices ────────────────────────────────────────────────────────

    /**
     * 分页拷贝（改名 + SourceID 三态 + 目标向量回填）。
     *
     * <p>源文档命中的向量按<b>目标 SourceID</b> 为键放入 {@code embeddingMap}，
     * 逐文档唯一 → {@code toDbVectorEmbedding} 按 SourceID 查表即命中（与 v8 语义一致）。</p>
     */
    void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[ElasticsearchV7] Empty mapping, skipping copy");
            return;
        }
        RetrieveParams retrieveParams = new RetrieveParams();
        retrieveParams.knowledgeBaseIds = List.of(sourceKnowledgeBaseId);

        int from = 0;
        int totalCopied = 0;
        while (true) {
            JsonNode hitsList = querySourceBatch(retrieveParams, from, ElasticsearchV7RetrieveRepository.COPY_BATCH_SIZE);
            if (hitsList.isEmpty()) {
                break;
            }
            List<IndexInfo> indexInfoList = new ArrayList<>();
            Map<String, float[]> embeddingMap = new LinkedHashMap<>();
            for (JsonNode hit : hitsList) {
                CopiedHit copied = processSingleHit(hit, sourceToTargetKbIdMap,
                        sourceToTargetChunkIdMap, targetKnowledgeBaseId);
                if (copied != null) {
                    indexInfoList.add(copied.info());
                    if (copied.embedding() != null && copied.embedding().length > 0) {
                        // 向量按"目标 SourceID"为键保存（逐文档唯一）
                        // → toDbVectorEmbedding 按 SourceID 查表即命中
                        embeddingMap.put(copied.info().sourceId, copied.embedding());
                    }
                }
            }
            if (!indexInfoList.isEmpty()) {
                saveCopiedIndices(indexInfoList, embeddingMap);
                totalCopied += indexInfoList.size();
            }
            from += hitsList.size();
            if (hitsList.size() < ElasticsearchV7RetrieveRepository.COPY_BATCH_SIZE) {
                break;
            }
        }
        log.info("[ElasticsearchV7] Index copy completed, total copied: {}", totalCopied);
    }


    JsonNode querySourceBatch(RetrieveParams retrieveParams, int from, int batchSize)
            throws Exception {
        JsonNode filter;
        try {
            filter = ElasticsearchV7RetrieveRepository.MAPPER.readTree(service.getBaseCondsJson(retrieveParams));
        } catch (Exception e) {
            filter = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        }
        ObjectNode body = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", filter);
        body.put("from", from);
        body.put("size", batchSize);
        HttpResult resp = service.request("POST", "/" + service.index + "/_search", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to query source index data: elasticsearch"
                    + " returned " + resp.status() + ": " + resp.body());
        }
        JsonNode searchResult = ElasticsearchV7RetrieveRepository.MAPPER.readTree(resp.body());
        JsonNode hitsObj = searchResult.get("hits");
        if (hitsObj == null || !hitsObj.isObject()) {
            throw new IllegalStateException("invalid search result format");
        }
        JsonNode hitsList = hitsObj.get("hits");
        if (hitsList == null || !hitsList.isArray() || hitsList.isEmpty()) {
            if (from == 0) {
                log.warn("[ElasticsearchV7] No source index data found");
            }
            return ElasticsearchV7RetrieveRepository.MAPPER.createArrayNode();
        }
        return hitsList;
    }

    /** 单条源命中处理：缺字段/映射缺失 → 返回 null（调用方跳过）；带上源向量。 */
    CopiedHit processSingleHit(JsonNode hit, Map<String, String> sourceToTargetKbIdMap,
                                       Map<String, String> sourceToTargetChunkIdMap,
                                       String targetKnowledgeBaseId) {
        JsonNode sourceObj = hit.get("_source");
        if (sourceObj == null || !sourceObj.isObject()) {
            log.warn("[ElasticsearchV7] Hit missing _source field");
            return null;
        }
        if (!sourceObj.hasNonNull("chunk_id")) {
            log.warn("[ElasticsearchV7] Source index data missing chunk_id field");
            return null;
        }
        String sourceChunkId = sourceObj.path("chunk_id").asText();
        String targetChunkId = sourceToTargetChunkIdMap.get(sourceChunkId);
        if (targetChunkId == null) {
            log.warn("[ElasticsearchV7] Source chunk ID {} not found in mapping", sourceChunkId);
            return null;
        }
        if (!sourceObj.hasNonNull("knowledge_id")) {
            log.warn("[ElasticsearchV7] Source index data missing knowledge_id field");
            return null;
        }
        String sourceKnowledgeId = sourceObj.path("knowledge_id").asText();
        String targetKnowledgeId = sourceToTargetKbIdMap.get(sourceKnowledgeId);
        if (targetKnowledgeId == null) {
            log.warn("[ElasticsearchV7] Source knowledge ID {} not found in mapping",
                    sourceKnowledgeId);
            return null;
        }

        String content = sourceObj.path("content").asText("");
        String originalSourceId = sourceObj.path("source_id").asText("");
        int sourceType = sourceObj.path("source_type").asInt(0);
        // is_enabled 缺省 true、is_recommended 缺省 false
        boolean isEnabled = !sourceObj.has("is_enabled") || sourceObj.path("is_enabled")
                .asBoolean(true);
        boolean isRecommended = sourceObj.path("is_recommended").asBoolean(false);
        String tagId = sourceObj.path("tag_id").asText("");

        String targetSourceId;
        if (originalSourceId.equals(sourceChunkId)) {
            targetSourceId = targetChunkId;
        } else if (originalSourceId.startsWith(sourceChunkId + "-")) {
            targetSourceId = targetChunkId + "-" + originalSourceId.substring(
                    sourceChunkId.length() + 1);
        } else {
            targetSourceId = UUID.randomUUID().toString();
        }

        IndexInfo info = new IndexInfo();
        info.chunkId = targetChunkId;
        info.sourceId = targetSourceId;
        info.knowledgeId = targetKnowledgeId;
        info.knowledgeBaseId = targetKnowledgeBaseId;
        info.content = content;
        info.sourceType = sourceType;
        info.isEnabled = isEnabled;
        info.isRecommended = isRecommended;
        info.tagId = tagId;

        float[] embedding = null;
        JsonNode vector = sourceObj.path("embedding");
        if (vector.isArray() && !vector.isEmpty()) {
            embedding = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                embedding[i] = (float) vector.get(i).asDouble();
            }
        }
        return new CopiedHit(info, embedding);
    }

    /** 一条待复制数据：目标索引信息 + 源文档向量。 */
    record CopiedHit(IndexInfo info, float[] embedding) {
    }

    /** 保存拷贝出的索引：向量映射经 additionalParams 传入批量写入。 */
    void saveCopiedIndices(List<IndexInfo> indexInfoList, Map<String, float[]> embeddingMap)
            throws Exception {
        if (indexInfoList.isEmpty()) {
            log.info("[ElasticsearchV7] No indices to save, skipping");
            return;
        }
        Map<String, Object> additionalParams = new LinkedHashMap<>();
        if (embeddingMap != null && !embeddingMap.isEmpty()) {
            additionalParams.put("embedding", embeddingMap);
            log.info("[ElasticsearchV7] Found {} embeddings to save", embeddingMap.size());
        }
        batchSave(indexInfoList, additionalParams);
        log.info("[ElasticsearchV7] Successfully saved {} indices", indexInfoList.size());
    }

    // ── 批量改状态 / 标签（不套 bool） ─────────────────────────────────────

    /** query 是直构 terms（无 bool 包裹）。 */
    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[ElasticsearchV7] Chunk status map is empty, skipping update");
            return;
        }
        List<String> enabled = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : chunkStatusMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                enabled.add(entry.getKey());
            } else {
                disabled.add(entry.getKey());
            }
        }
        if (!enabled.isEmpty()) {
            updateByQuery(enabled, "ctx._source.is_enabled = true", null);
        }
        if (!disabled.isEmpty()) {
            updateByQuery(disabled, "ctx._source.is_enabled = false", null);
        }
        log.info("[ElasticsearchV7] Successfully batch updated chunk enabled status");
    }

    /** 按 tag 分组，脚本带 params.tag_id。 */
    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[ElasticsearchV7] Chunk tag map is empty, skipping update");
            return;
        }
        Map<String, List<String>> tagGroups = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            tagGroups.computeIfAbsent(entry.getValue() == null ? "" : entry.getValue(),
                    k -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<String, List<String>> group : tagGroups.entrySet()) {
            updateByQuery(group.getValue(), "ctx._source.tag_id = params.tag_id",
                    group.getKey());
        }
        log.info("[ElasticsearchV7] Successfully batch updated chunk tag ID");
    }

    /** {@code _update_by_query}：body = {query:{terms:{...}}, script:{source,lang[,params]}}。 */
    void updateByQuery(List<String> chunkIds, String scriptSource, String tagId)
            throws Exception {
        ObjectNode body = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", ElasticsearchV7RetrieveRepository.termsOnly(service.idField("chunk_id"), chunkIds));
        ObjectNode script = ElasticsearchV7RetrieveRepository.MAPPER.createObjectNode();
        script.put("source", scriptSource);
        script.put("lang", "painless");
        if (tagId != null) {
            script.putObject("params").put("tag_id", tagId);
        }
        body.set("script", script);

        HttpResult resp = service.request("POST", "/" + service.index + "/_update_by_query", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException(
                    "elasticsearch update_by_query failed with status: " + resp.status());
        }
    }
}
