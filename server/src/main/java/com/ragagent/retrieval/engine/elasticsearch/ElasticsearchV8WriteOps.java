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

import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV8RetrieveRepository.HttpResult;
import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV8RetrieveRepository.VectorEmbedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elasticsearch v8 引擎的写入/删除/批量更新/拷贝簇：单条 _doc 与 bulk NDJSON（部分失败视为失败）、
 * _delete_by_query terms、_update_by_query painless 批量改状态/标签、
 * CopyIndices 分页（from/size 500）+ SourceID 三态 + 目标向量回填。
 */
final class ElasticsearchV8WriteOps {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchV8WriteOps.class);

    private final ElasticsearchV8RetrieveRepository service;

    ElasticsearchV8WriteOps(ElasticsearchV8RetrieveRepository service) {
        this.service = service;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    /** 对照 {@code Save}。 */
    void save(IndexInfo embedding, Map<String, Object> additionalParams) throws Exception {
        VectorEmbedding doc = ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(embedding, additionalParams);
        if (doc.embedding == null || doc.embedding.length == 0) {
            throw new IllegalStateException(
                    "empty embedding vector for chunk ID: " + embedding.chunkId);
        }
        HttpResult resp = service.request("POST", "/" + service.index + "/_doc", ElasticsearchV8RetrieveRepository.docJson(doc));
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("elasticsearch index document returned "
                    + resp.status() + ": " + resp.body());
        }
    }

    /** 对照 {@code BatchSave}：bulk NDJSON，create 语义。 */
    void batchSave(List<IndexInfo> embeddingList, Map<String, Object> additionalParams)
            throws Exception {
        if (embeddingList == null || embeddingList.isEmpty()) {
            log.warn("[Elasticsearch] Empty list provided to BatchSave, skipping");
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        for (IndexInfo embedding : embeddingList) {
            VectorEmbedding doc = ElasticsearchV8RetrieveRepository.toDbVectorEmbedding(embedding, additionalParams);
            ObjectNode action = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
            action.set("create", ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode().put("_index", service.index));
            ndjson.append(action).append('\n').append(ElasticsearchV8RetrieveRepository.docJson(doc)).append('\n');
        }
        HttpResult resp = service.requestRaw("POST", "/" + service.index + "/_bulk", ndjson.toString(),
                "application/x-ndjson");
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to do bulk: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
        inspectBulkResponse(resp.body());
        log.info("[Elasticsearch] Successfully batch saved {} indices", embeddingList.size());
    }

    /**
     * bulk 响应逐项检视（照 OpenSearch 的 inspectBulkResponse）：HTTP 200 +
     * {@code errors:true} 是"部分失败"——此前只查状态码，mapping 冲突等单文档失败
     * 静默丢数据且无日志。部分失败视为批量失败（与 OpenSearch 语义一致）。
     */
    void inspectBulkResponse(String body) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = ElasticsearchV8RetrieveRepository.MAPPER.readTree(body);
        } catch (Exception e) {
            return; // 非 JSON 响应不做逐项检视（状态码已过）
        }
        if (!root.path("errors").asBoolean(false)) {
            return;
        }
        int total = 0;
        List<String> msgs = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode item : root.path("items")) {
            var it = item.fields();
            if (!it.hasNext()) {
                continue;
            }
            String opName = it.next().getKey();
            com.fasterxml.jackson.databind.JsonNode op = item.path(opName);
            com.fasterxml.jackson.databind.JsonNode err = op.path("error");
            if (err.isMissingNode() || err.isNull()) {
                continue;
            }
            total++;
            log.debug("[Elasticsearch] bulk item err: op={} id={} type={} reason={}",
                    opName, op.path("_id").asText(""), err.path("type").asText(""),
                    err.path("reason").asText(""));
            if (msgs.size() < 5) {
                msgs.add("[" + opName + " " + op.path("_id").asText("") + "] "
                        + err.path("type").asText(""));
            }
        }
        if (total == 0) {
            return;
        }
        throw new IllegalStateException("elasticsearch bulk partial failure ("
                + total + " items failed, first 5: " + String.join("; ", msgs) + ")");
    }

    /** 对照 {@code DeleteByChunkIDList}。 */
    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByTerms("chunk_id", chunkIdList);
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    /** 对照 {@code DeleteBySourceIDList}。 */
    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByTerms("source_id", sourceIdList);
    }

    /** 对照 {@code DeleteByKnowledgeIDList}。 */
    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByTerms("knowledge_id", knowledgeIdList);
    }


    void deleteByTerms(String field, List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            log.warn("[Elasticsearch] Empty {} list provided for deletion, skipping", field);
            return;
        }
        ObjectNode query = ElasticsearchV8RetrieveRepository.termsQuery(service.idField(field), ids);
        ObjectNode body = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", query);
        HttpResult resp = service.request("POST", "/" + service.index + "/_delete_by_query", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("failed to delete by query: elasticsearch returned "
                    + resp.status() + ": " + resp.body());
        }
    }

    /** 对照 {@code CopyIndices}（分页 + 映射改名 + SourceID 三态 + 目标向量回填）。 */
    void copyIndices(String sourceKnowledgeBaseId,
                            Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap,
                            String targetKnowledgeBaseId, int dimension, String knowledgeType)
            throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[Elasticsearch] Empty mapping, skipping copy");
            return;
        }
        RetrieveParams params = new RetrieveParams();
        params.knowledgeBaseIds = List.of(sourceKnowledgeBaseId);
        List<ObjectNode> filter = service.getBaseConds(params);

        int from = 0;
        int totalCopied = 0;
        while (true) {
            ObjectNode bool = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
            ArrayNode filterArray = bool.putArray("filter");
            filter.forEach(filterArray::add);
            ObjectNode query = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
            query.set("bool", bool);

            ObjectNode body = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
            body.set("query", query);
            body.put("from", from);
            body.put("size", ElasticsearchV8RetrieveRepository.COPY_BATCH_SIZE);

            HttpResult resp = service.request("POST", "/" + service.index + "/_search", body.toString());
            if (resp.status() < 200 || resp.status() >= 300) {
                throw new IllegalStateException("elasticsearch search returned " + resp.status()
                        + ": " + resp.body());
            }
            JsonNode hits = ElasticsearchV8RetrieveRepository.MAPPER.readTree(resp.body()).path("hits").path("hits");
            int hitsCount = hits.size();
            if (hitsCount == 0) {
                break;
            }

            List<IndexInfo> indexInfoList = new ArrayList<>();
            Map<String, float[]> embeddingMap = new LinkedHashMap<>();
            for (JsonNode hit : hits) {
                VectorEmbedding sourceDoc = ElasticsearchV8RetrieveRepository.parseSource(hit.path("_source"));
                String targetChunkId = sourceToTargetChunkIdMap.get(sourceDoc.chunkId);
                if (targetChunkId == null) {
                    log.warn("[Elasticsearch] Source chunk {} not found in target mapping,"
                            + " skipping", sourceDoc.chunkId);
                    continue;
                }
                String targetKnowledgeId = sourceToTargetKbIdMap.get(sourceDoc.knowledgeId);
                if (targetKnowledgeId == null) {
                    log.warn("[Elasticsearch] Source knowledge {} not found in target mapping,"
                            + " skipping", sourceDoc.knowledgeId);
                    continue;
                }
                String targetSourceId;
                if (sourceDoc.sourceId.equals(sourceDoc.chunkId)) {
                    targetSourceId = targetChunkId;
                } else if (sourceDoc.sourceId.startsWith(sourceDoc.chunkId + "-")) {
                    String questionId = sourceDoc.sourceId.substring(sourceDoc.chunkId.length() + 1);
                    targetSourceId = targetChunkId + "-" + questionId;
                } else {
                    targetSourceId = UUID.randomUUID().toString();
                }
                if (sourceDoc.embedding != null && sourceDoc.embedding.length > 0) {
                    // 修复（有意偏离 Go v8）：Go 以"目标 chunkID"为键、而查表用的是 SourceID →
                    // 生成问题（<chunk>-<qid> 形态）取不到向量、同 chunk 多文档互相覆盖；
                    // 这里改键为目标 SourceID（逐文档唯一），toDbVectorEmbedding 按 SourceID 查表即命中
                    embeddingMap.put(targetSourceId, sourceDoc.embedding);
                }

                IndexInfo info = new IndexInfo();
                info.content = sourceDoc.content;
                info.sourceId = targetSourceId;
                info.sourceType = sourceDoc.sourceType;
                info.chunkId = targetChunkId;
                info.knowledgeId = targetKnowledgeId;
                info.knowledgeBaseId = targetKnowledgeBaseId;
                indexInfoList.add(info);
                totalCopied++;
            }

            if (!indexInfoList.isEmpty()) {
                Map<String, Object> additionalParams = new LinkedHashMap<>();
                additionalParams.put("embedding", embeddingMap);
                batchSave(indexInfoList, additionalParams);
            }

            from += hitsCount;
            if (hitsCount < ElasticsearchV8RetrieveRepository.COPY_BATCH_SIZE) {
                break;
            }
        }
        log.info("[Elasticsearch] Index copy completed, total copied: {}", totalCopied);
    }

    // ── CopyIndices / 批量改状态 / 标签 ────────────────────────────────────

    /** 对照 {@code BatchUpdateChunkEnabledStatus}：按值分两组 update_by_query。 */
    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            log.warn("[Elasticsearch] Chunk status map is empty, skipping update");
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
    }

    /** 对照 {@code BatchUpdateChunkTagID}：按 tagID 分组逐组 update_by_query。 */
    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            log.warn("[Elasticsearch] Chunk tag map is empty, skipping update");
            return;
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : chunkTagMap.entrySet()) {
            groups.computeIfAbsent(entry.getValue() == null ? "" : entry.getValue(),
                    k -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            updateByQuery(group.getValue(), "ctx._source.tag_id = params.tag_id",
                    group.getKey());
        }
    }

    /** {@code _update_by_query}：query = bool.must[terms chunk_id]；脚本 painless。 */
    void updateByQuery(List<String> chunkIds, String scriptSource, String tagId)
            throws Exception {
        ObjectNode boolBody = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        ArrayNode mustArray = boolBody.putArray("must");
        mustArray.add(ElasticsearchV8RetrieveRepository.termsQuery(service.idField("chunk_id"), chunkIds));
        ObjectNode bool = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        bool.set("bool", boolBody);

        ObjectNode script = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        script.put("source", scriptSource);
        script.put("lang", "painless");
        if (tagId != null) {
            script.putObject("params").put("tag_id", tagId);
        }

        ObjectNode body = ElasticsearchV8RetrieveRepository.MAPPER.createObjectNode();
        body.set("query", bool);
        body.set("script", script);

        HttpResult resp = service.request("POST", "/" + service.index + "/_update_by_query", body.toString());
        if (resp.status() < 200 || resp.status() >= 300) {
            throw new IllegalStateException("elasticsearch update_by_query returned "
                    + resp.status() + ": " + resp.body());
        }
    }
}
