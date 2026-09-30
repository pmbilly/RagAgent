package com.ragagent.retrieval.engine.opensearch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenSearch 引擎的写入/删除/批量更新/拷贝/迁移簇与文档投影、批量响应检视：
 * bulk 体积与文档数上限、逐项错误检视（部分失败视为失败）、_delete_by_query terms、
 * update_by_query painless 批量改写、CopyIndices 分页（批 500）+ 三态 SourceID、
 * move 的完整性校验（requireComplete）。
 */
final class OpenSearchWriteOps {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchWriteOps.class);

    private final OpenSearchRetrieveRepository service;

    OpenSearchWriteOps(OpenSearchRetrieveRepository service) {
        this.service = service;
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    void save(IndexInfo info, Map<String, Object> params) throws Exception {
        float[] emb = lookupEmbedding(params, info.sourceId);
        boolean enabled = lookupChunkEnabled(params, info.chunkId, info.isEnabled);
        String targetIndex;
        if (emb.length > 0) {
            service.ensureReady(emb.length);
            targetIndex = service.indexAlias(emb.length);
        } else {
            service.ensureKeywordsIndex();
            targetIndex = service.keywordsIndex();
        }
        byte[] doc = OpenSearchRetrieveRepository.MAPPER.writeValueAsBytes(toDoc(info, emb, enabled));
        service.send("PUT", "/" + targetIndex + "/_doc/" + info.chunkId, doc, "application/json");
    }

    void batchSave(List<IndexInfo> infos, Map<String, Object> params) throws Exception {
        if (infos == null || infos.isEmpty()) {
            return;
        }
        float[][] embs = extractBatchEmbeddings(params, infos);
        int dim = 0;
        for (float[] emb : embs) {
            if (emb.length > 0) {
                dim = emb.length;
                break;
            }
        }
        long estimated = (long) infos.size() * (100 + dim * 5 + 1024);
        if (estimated > OpenSearchRetrieveRepository.BULK_BODY_CAP_BYTES) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.BATCH_TOO_LARGE,
                    "opensearch: estimated bulk body " + estimated + "B exceeds 10MB cap (n="
                            + infos.size() + ", dim=" + dim + "): opensearch: batch size exceeds"
                            + " driver cap");
        }
        if (infos.size() > OpenSearchRetrieveRepository.BULK_DOC_CAP) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.BATCH_TOO_LARGE,
                    "opensearch: bulk n=" + infos.size() + " exceeds 1000-doc cap: opensearch:"
                            + " batch size exceeds driver cap");
        }
        String alias;
        if (dim == 0) {
            service.ensureKeywordsIndex();
            alias = service.keywordsIndex();
        } else {
            service.ensureReady(dim);
            alias = service.indexAlias(dim);
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < infos.size(); i++) {
            IndexInfo info = infos.get(i);
            // 照 Go 的 map 序列化：键字母序 {"index":{"_id":..,"_index":..}}
            Map<String, Object> action = new TreeMap<>();
            Map<String, Object> desc = new TreeMap<>();
            desc.put("_id", info.chunkId);
            desc.put("_index", alias);
            action.put("index", desc);
            buf.append(OpenSearchRetrieveRepository.MAPPER.writeValueAsString(action)).append('\n');
            boolean enabled = lookupChunkEnabled(params, info.chunkId, info.isEnabled);
            buf.append(OpenSearchRetrieveRepository.MAPPER.writeValueAsString(toDoc(info, embs[i], enabled))).append('\n');
        }
        String response = service.send("POST", "/_bulk",
                buf.toString().getBytes(StandardCharsets.UTF_8), "application/x-ndjson",
                OpenSearchRetrieveRepository.BULK_RESPONSE_CAP);
        inspectBulkResponse(response);
    }

    // ── 删除 ────────────────────────────────────────────────────────────────

    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByList(chunkIdList, dimension, "chunk_id");
    }

    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        deleteByList(sourceIdList, dimension, "source_id");
    }

    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        deleteByList(knowledgeIdList, dimension, "knowledge_id");
    }

    /** 对照 deleteByList：cap 1000；dim==0 → keywords 索引。 */
    void deleteByList(List<String> ids, int dim, String field) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (ids.size() > OpenSearchRetrieveRepository.BULK_DOC_CAP) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.BATCH_TOO_LARGE,
                    "opensearch: " + field + "-delete batch " + ids.size()
                            + " > 1000 cap: opensearch: batch size exceeds driver cap");
        }
        String index;
        if (dim == 0) {
            service.ensureKeywordsIndex();
            index = service.keywordsIndex();
        } else {
            service.ensureReady(dim);
            index = service.indexAlias(dim);
        }
        Map<String, Object> terms = new TreeMap<>();
        terms.put(field, ids);
        Map<String, Object> query = new TreeMap<>();
        query.put("terms", terms);
        Map<String, Object> body = new TreeMap<>();
        body.put("query", query);
        String response = service.send("POST", "/" + index + "/_delete_by_query?refresh=true",
                OpenSearchRetrieveRepository.MAPPER.writeValueAsBytes(body), "application/json", OpenSearchRetrieveRepository.SEARCH_BODY_CAP);
        inspectByQueryResponse(response, false);
    }

    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                            int dimension, String knowledgeType) throws Exception {
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[OpenSearch] CopyIndices: empty chunk mapping, skipping");
            return;
        }
        if (dimension <= 0) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.DIMENSION_MISMATCH,
                    "opensearch: CopyIndices requires dim > 0, got " + dimension
                            + ": opensearch: embedding dimension mismatch");
        }
        service.ensureReady(dimension);
        String alias = service.indexAlias(dimension);
        long total = 0;
        for (int from = 0; ; from += OpenSearchRetrieveRepository.COPY_BATCH_SIZE) {
            List<Map<String, Object>> docs = copyScanBatch(alias, sourceKnowledgeBaseId, from);
            if (docs.isEmpty()) {
                break;
            }
            List<IndexInfo> infos = new ArrayList<>();
            Map<String, float[]> embMap = new HashMap<>();
            Map<String, Boolean> enabledMap = new HashMap<>();
            for (Map<String, Object> d : docs) {
                String chunkId = str(d.get("chunk_id"));
                String targetChunkId = sourceToTargetChunkIdMap.get(chunkId);
                if (targetChunkId == null) {
                    log.warn("[OpenSearch] CopyIndices: source chunk {} not mapped, skipping",
                            chunkId);
                    continue;
                }
                String knowledgeId = str(d.get("knowledge_id"));
                String targetKnowledgeId = sourceToTargetKbIdMap.get(knowledgeId);
                if (targetKnowledgeId == null) {
                    log.warn("[OpenSearch] CopyIndices: source knowledge {} not mapped, skipping",
                            knowledgeId);
                    continue;
                }
                String sourceId = str(d.get("source_id"));
                String targetSourceId = OpenSearchRetrieveRepository.transformSourceId(sourceId, chunkId, targetChunkId);
                @SuppressWarnings("unchecked")
                List<Number> embedding = (List<Number>) d.get("embedding");
                if (embedding != null && !embedding.isEmpty()) {
                    // BatchSave 按 SourceID 查 embedding——键用目标 source id
                    //（不是 ES 驱动的 chunk id 约定）
                    float[] vector = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        vector[i] = embedding.get(i).floatValue();
                    }
                    embMap.put(targetSourceId, vector);
                }
                boolean enabled = Boolean.TRUE.equals(d.get("is_enabled"));
                enabledMap.put(targetChunkId, enabled);
                IndexInfo info = new IndexInfo();
                info.content = str(d.get("content"));
                info.sourceId = targetSourceId;
                info.sourceType = d.get("source_type") instanceof Number n ? n.intValue() : 0;
                info.chunkId = targetChunkId;
                info.knowledgeId = targetKnowledgeId;
                info.knowledgeBaseId = targetKnowledgeBaseId;
                info.knowledgeType = knowledgeType;
                info.tagId = str(d.get("tag_id"));
                info.isEnabled = enabled;
                info.isRecommended = Boolean.TRUE.equals(d.get("is_recommended"));
                infos.add(info);
            }
            if (!infos.isEmpty()) {
                Map<String, Object> params = new HashMap<>();
                params.put("embedding", embMap);
                params.put("chunk_enabled", enabledMap);
                batchSave(infos, params);
                total += infos.size();
            }
            if (docs.size() < OpenSearchRetrieveRepository.COPY_BATCH_SIZE) {
                break;
            }
        }
        log.info("[OpenSearch] CopyIndices: copied {} docs (KB {} → {}, dim={})",
                total, sourceKnowledgeBaseId, targetKnowledgeBaseId, dimension);
        service.auditSink().emitReindexExecuted(alias, alias, total);
    }

    // ── 复制 / 批量更新 ─────────────────────────────────────────────────────

    /** 对照 copyScanBatch：全 _source（含 embedding/is_recommended）。 */
    List<Map<String, Object>> copyScanBatch(String index, String sourceKb, int from)
            throws Exception {
        Map<String, Object> term = new TreeMap<>();
        term.put("knowledge_base_id", sourceKb);
        Map<String, Object> filterClause = new TreeMap<>();
        filterClause.put("term", term);
        Map<String, Object> bool = new TreeMap<>();
        bool.put("filter", List.of(filterClause));
        Map<String, Object> query = new TreeMap<>();
        query.put("bool", bool);
        Map<String, Object> body = new TreeMap<>();
        body.put("from", from);
        body.put("query", query);
        body.put("size", OpenSearchRetrieveRepository.COPY_BATCH_SIZE);
        String response = service.send("POST", "/" + index + "/_search",
                OpenSearchRetrieveRepository.MAPPER.writeValueAsBytes(body), "application/json", OpenSearchRetrieveRepository.BULK_RESPONSE_CAP);
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode hits = OpenSearchRetrieveRepository.MAPPER.readTree(response).path("hits").path("hits");
        for (JsonNode h : hits) {
            JsonNode source = h.path("_source");
            Map<String, Object> row = new HashMap<>();
            row.put("content", source.path("content").asText(""));
            row.put("source_id", source.path("source_id").asText(""));
            row.put("source_type", source.path("source_type").asInt(0));
            row.put("chunk_id", source.path("chunk_id").asText(""));
            row.put("knowledge_id", source.path("knowledge_id").asText(""));
            row.put("knowledge_base_id", source.path("knowledge_base_id").asText(""));
            row.put("tag_id", source.path("tag_id").asText(""));
            row.put("is_enabled", source.path("is_enabled").asBoolean(false));
            row.put("is_recommended", source.path("is_recommended").asBoolean(false));
            row.put("embedding", OpenSearchRetrieveRepository.MAPPER.convertValue(source.path("embedding"), List.class));
            out.add(row);
        }
        return out;
    }

    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        if (chunkStatusMap == null || chunkStatusMap.isEmpty()) {
            return;
        }
        Map<Boolean, List<String>> groups = new HashMap<>();
        for (Map.Entry<String, Boolean> e : chunkStatusMap.entrySet()) {
            groups.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (boolean value : new boolean[] {false, true}) {
            List<String> ids = groups.get(value);
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            List<String> sorted = new ArrayList<>(ids);
            java.util.Collections.sort(sorted);
            Map<String, Object> params = new TreeMap<>();
            params.put("v", value);
            updateByQueryScript(sorted, "ctx._source.is_enabled = params.v", params);
        }
    }

    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        if (chunkTagMap == null || chunkTagMap.isEmpty()) {
            return;
        }
        Map<String, List<String>> groups = new TreeMap<>();
        for (Map.Entry<String, String> e : chunkTagMap.entrySet()) {
            groups.computeIfAbsent(e.getValue() == null ? "" : e.getValue(),
                    k -> new ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            List<String> ids = new ArrayList<>(e.getValue());
            java.util.Collections.sort(ids);
            Map<String, Object> params = new TreeMap<>();
            params.put("v", e.getKey());
            updateByQueryScript(ids, "ctx._source.tag_id = params.v", params);
        }
    }

    /** 对照 updateByQueryScript：跨维 {@code <base>_*} + painless 常量源 + params 绑定。 */
    void updateByQueryScript(List<String> chunkIds, String source,
                                     Map<String, Object> scriptParams) throws Exception {
        Map<String, Object> terms = new TreeMap<>();
        terms.put("chunk_id", chunkIds);
        Map<String, Object> query = new TreeMap<>();
        query.put("terms", terms);
        Map<String, Object> script = new TreeMap<>();
        script.put("lang", "painless");
        script.put("params", scriptParams);
        script.put("source", source);
        Map<String, Object> body = new TreeMap<>();
        body.put("query", query);
        body.put("script", script);
        String response = service.send("POST", "/" + service.baseIndex + "_*/_update_by_query?refresh=true",
                OpenSearchRetrieveRepository.MAPPER.writeValueAsBytes(body), "application/json", OpenSearchRetrieveRepository.SEARCH_BODY_CAP);
        inspectByQueryResponse(response, false);
    }

    // ── 迁移 ────────────────────────────────────────────────────────────────

    void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        Map<String, Object> termKb = new TreeMap<>();
        termKb.put("knowledge_base_id", sourceKb);
        Map<String, Object> termKbWrap = new TreeMap<>();
        termKbWrap.put("term", termKb);
        Map<String, Object> termKid = new TreeMap<>();
        termKid.put("knowledge_id", knowledgeId);
        Map<String, Object> termKidWrap = new TreeMap<>();
        termKidWrap.put("term", termKid);
        Map<String, Object> bool = new TreeMap<>();
        bool.put("filter", List.of(termKbWrap, termKidWrap));
        Map<String, Object> query = new TreeMap<>();
        query.put("bool", bool);
        Map<String, Object> scriptParams = new TreeMap<>();
        scriptParams.put("target", targetKb);
        Map<String, Object> script = new TreeMap<>();
        script.put("lang", "painless");
        script.put("params", scriptParams);
        script.put("source",
                "ctx._source.knowledge_base_id = params.target; ctx._source.tag_id = '';");
        Map<String, Object> body = new TreeMap<>();
        body.put("query", query);
        body.put("script", script);
        String response = service.send("POST", "/" + service.baseIndex + "_*/_update_by_query?refresh=true",
                OpenSearchRetrieveRepository.MAPPER.writeValueAsBytes(body), "application/json", OpenSearchRetrieveRepository.SEARCH_BODY_CAP);
        inspectByQueryResponse(response, true);
    }

    // ── 文档投影与参数查表 ──────────────────────────────────────────────────

    /** 对照 toDoc：字母序键；缺 embedding 时整体省略该字段（keyword-only 文档）。 */
    static Map<String, Object> toDoc(IndexInfo info, float[] emb, boolean enabled) {
        Map<String, Object> doc = new TreeMap<>();
        doc.put("chunk_id", info.chunkId);
        doc.put("knowledge_id", info.knowledgeId);
        doc.put("knowledge_base_id", info.knowledgeBaseId);
        doc.put("source_id", info.sourceId);
        doc.put("source_type", info.sourceType);
        doc.put("tag_id", info.tagId);
        doc.put("content", info.content);
        doc.put("is_enabled", enabled);
        doc.put("is_recommended", info.isRecommended);
        if (emb != null && emb.length > 0) {
            doc.put("embedding", emb);
        }
        return doc;
    }

    @SuppressWarnings("unchecked")
    static float[] lookupEmbedding(Map<String, Object> params, String sourceId) {
        if (params == null) {
            return new float[0];
        }
        Object raw = params.get("embedding");
        if (!(raw instanceof Map)) {
            if (raw != null) {
                log.warn("[OpenSearch] additionalParams[\"embedding\"] is {}, want map",
                        raw.getClass().getSimpleName());
            }
            return new float[0];
        }
        Object vector = ((Map<String, Object>) raw).get(sourceId);
        if (vector instanceof float[] v) {
            return v;
        }
        if (vector instanceof List<?> list) {
            float[] out = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = list.get(i) instanceof Number n ? n.floatValue() : 0f;
            }
            return out;
        }
        return new float[0];
    }

    @SuppressWarnings("unchecked")
    static boolean lookupChunkEnabled(Map<String, Object> params, String chunkId, boolean def) {
        if (params == null) {
            return def;
        }
        Object raw = params.get("chunk_enabled");
        if (!(raw instanceof Map)) {
            return def;
        }
        Object v = ((Map<String, Object>) raw).get(chunkId);
        if (v instanceof Boolean b) {
            return b;
        }
        return def;
    }

    /** 对照 extractBatchEmbeddings：混合维度 → DIMENSION_MISMATCH。 */
    static float[][] extractBatchEmbeddings(Map<String, Object> params, List<IndexInfo> infos) {
        float[][] out = new float[infos.size()][];
        int dim = 0;
        for (int i = 0; i < infos.size(); i++) {
            float[] emb = lookupEmbedding(params, infos.get(i).sourceId);
            out[i] = emb;
            if (emb.length > 0) {
                if (dim == 0) {
                    dim = emb.length;
                } else if (emb.length != dim) {
                    throw new OpenSearchDriverException(
                            OpenSearchDriverException.Kind.DIMENSION_MISMATCH,
                            "opensearch: embedding[" + infos.get(i).sourceId + "] dim="
                                    + emb.length + " != first non-empty dim=" + dim
                                    + ": opensearch: embedding dimension mismatch");
                }
            }
        }
        return out;
    }


    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 对照 inspectBulkResponse：逐项错误（≤5 条 "[op id] type"；reason 只进 DEBUG）。 */
    static void inspectBulkResponse(String response) throws Exception {
        JsonNode root;
        try {
            root = OpenSearchRetrieveRepository.MAPPER.readTree(response);
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: parse bulk response: opensearch: transport error");
        }
        if (!root.path("errors").asBoolean(false)) {
            return;
        }
        int total = 0;
        List<String> msgs = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            // bulk item 形如 {"index": {...}}（单键）
            var it = item.fields();
            if (!it.hasNext()) {
                continue;
            }
            String opName = it.next().getKey();
            JsonNode op = item.path(opName);
            JsonNode err = op.path("error");
            if (err.isMissingNode() || err.isNull()) {
                continue;
            }
            total++;
            log.debug("[OpenSearch] bulk item err: op={} id={} type={} reason={}",
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
        throw new OpenSearchDriverException(
                OpenSearchDriverException.Kind.TRANSPORT,
                "opensearch: bulk partial failure (" + total + " items failed, first 5: "
                        + String.join("; ", msgs) + "): opensearch: transport error");
    }

    /** 对照 inspectByQueryResult（requireComplete=move 的完整性校验）。 */
    static void inspectByQueryResponse(String response, boolean requireComplete)
            throws Exception {
        JsonNode root;
        try {
            root = OpenSearchRetrieveRepository.MAPPER.readTree(response);
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: parse by-query response: opensearch: transport error");
        }
        boolean timedOut = root.path("timed_out").asBoolean(false);
        int versionConflicts = root.path("version_conflicts").asInt(0);
        if (requireComplete && (timedOut || versionConflicts != 0)) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: incomplete move (timed_out=" + timedOut
                            + ", version_conflicts=" + versionConflicts
                            + "): opensearch: transport error");
        }
        JsonNode total = root.path("total");
        JsonNode updated = root.path("updated");
        if (requireComplete && (total.isMissingNode() || updated.isMissingNode()
                || total.asLong(-1) < 0 || updated.asLong(-1) != total.asLong(-1))) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: incomplete move document counts: opensearch: transport error");
        }
        JsonNode failures = root.path("failures");
        if (failures.isEmpty()) {
            if (versionConflicts > 0) {
                log.warn("[OpenSearch] by-query had {} version conflicts (proceeded)",
                        versionConflicts);
            }
            return;
        }
        List<String> msgs = new ArrayList<>();
        for (JsonNode f : failures) {
            log.debug("[OpenSearch] by-query failure: id={} type={} reason={}",
                    f.path("id").asText(""), f.path("cause").path("type").asText(""),
                    f.path("cause").path("reason").asText(""));
            if (msgs.size() < 5) {
                msgs.add("[" + f.path("id").asText("") + "] "
                        + f.path("cause").path("type").asText(""));
            }
        }
        throw new OpenSearchDriverException(
                OpenSearchDriverException.Kind.TRANSPORT,
                "opensearch: by-query partial failure (" + failures.size() + " failed, first 5: "
                        + String.join("; ", msgs) + "): opensearch: transport error");
    }
}
