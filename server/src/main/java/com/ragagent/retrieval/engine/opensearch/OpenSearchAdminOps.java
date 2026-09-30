package com.ragagent.retrieval.engine.opensearch;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.retrieval.engine.opensearch.OpenSearchRetrieveRepository.DimInit;
import com.ragagent.retrieval.engine.opensearch.OpenSearchRetrieveRepository.InternalCfg;

/**
 * OpenSearch 引擎的惰性初始化/建索引/探针/别名簇：逐维 once（transient 可重置）、
 * alias 短路与漂移指纹校验（CONFIG_INVALID "manual reindex required"）、
 * keyword 专用索引 mutex+flag、构造期版本/k-NN 插件探针、别名 CRUD（自持 HttpRequest）。
 */
final class OpenSearchAdminOps {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchAdminOps.class);

    private final OpenSearchRetrieveRepository service;

    OpenSearchAdminOps(OpenSearchRetrieveRepository service) {
        this.service = service;
    }

    /** 对照 ensureReady；dim 界 (0, 16000]（knn_vector 硬上限）。 */
    void ensureReady(int dim) {
        if (dim <= 0 || dim > 16000) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.DIMENSION_MISMATCH,
                    "opensearch: dim " + dim + " out of range (1..16000)"
                            + ": opensearch: embedding dimension mismatch");
        }
        DimInit state = service.dimInits.computeIfAbsent(dim, k -> new DimInit());
        synchronized (state) {
            if (!state.done) {
                try {
                    createIndexAndAlias(dim);
                    state.done = true;
                    service.initErrs.remove(dim);
                } catch (OpenSearchDriverException e) {
                    if (OpenSearchDriverException.isTransient(e)) {
                        // 瞬时：不持久化，once 重置——下次调用重试（照 Go）。
                        // ⚠️ 当次调用也不报错（Go 代码 initErr 未写即返回 nil）；
                        // 后续操作以 INDEX_NOT_FOUND 显形。
                        service.dimInits.remove(dim, state);
                    } else {
                        // 永久：持久化，后续调用直接复现同一失败
                        state.done = true;
                        service.initErrs.put(dim, e);
                    }
                }
            }
        }
        OpenSearchDriverException persisted = service.initErrs.get(dim);
        if (persisted != null) {
            throw persisted;
        }
    }

    /**
     * 对照 createIndexAndAlias：alias 存在短路；already-exists → 指纹比对（漂移 →
     * CONFIG_INVALID）；aliasPut 失败尽力删孤儿 _v1；实际建索引才发审计。
     */
    void createIndexAndAlias(int dim) {
        String alias = service.indexAlias(dim);
        String realIndex = alias + "_v1";
        try {
            if (aliasExists(alias)) {
                return;
            }
        } catch (OpenSearchDriverException e) {
            throw new OpenSearchDriverException(e.kind(),
                    "alias check " + alias + ": " + e.getMessage(), e.httpStatus(), e.errorType());
        }
        byte[] body = buildIndexMapping(service.cfg, dim);
        boolean indexCreated = false;
        try {
            service.send("PUT", "/" + realIndex, body, "application/json", OpenSearchRetrieveRepository.SEARCH_BODY_CAP);
            indexCreated = true;
        } catch (OpenSearchDriverException e) {
            if (OpenSearchDriverException.isAlreadyExists(e)) {
                // 跨进程竞争/残留孤儿：校验既有 mapping 的结构指纹
                verifyMappingMatches(realIndex, body);
            } else {
                throw new OpenSearchDriverException(e.kind(),
                        "create index " + realIndex + ": " + e.getMessage(),
                        e.httpStatus(), e.errorType());
            }
        }
        try {
            aliasPut(realIndex, alias);
        } catch (OpenSearchDriverException e) {
            if (indexCreated) {
                try {
                    service.send("DELETE", "/" + realIndex, null, "application/json", OpenSearchRetrieveRepository.SEARCH_BODY_CAP);
                    log.info("[OpenSearch] cleaned up orphan {} after aliasPut failure", realIndex);
                } catch (Exception delErr) {
                    log.warn("[OpenSearch] orphan cleanup failed for {}: {}"
                            + " (operator must DELETE manually)", realIndex, delErr.getMessage());
                }
            }
            throw new OpenSearchDriverException(e.kind(),
                    "put alias " + alias + " → " + realIndex + ": " + e.getMessage(),
                    e.httpStatus(), e.errorType());
        }
        if (indexCreated) {
            service.auditSink().emitIndexCreated(alias, dim);
        }
    }

    /** 对照 ensureKeywordsIndex：mutex+flag，transient 可重试。 */
    void ensureKeywordsIndex() {
        synchronized (service.keywordsLock) {
            if (service.keywordsReady) {
                return;
            }
            if (service.keywordsErr != null
                    && !OpenSearchDriverException.isTransient(service.keywordsErr)) {
                throw service.keywordsErr;
            }
            String name = service.keywordsIndex();
            try {
                if (aliasExists(name)) {
                    service.keywordsReady = true;
                    service.keywordsErr = null;
                    return;
                }
                boolean created = false;
                try {
                    service.send("PUT", "/" + name, buildKeywordsMapping(service.cfg), "application/json");
                    created = true;
                } catch (OpenSearchDriverException e) {
                    if (!OpenSearchDriverException.isAlreadyExists(e)) {
                        service.keywordsErr = e;
                        throw e;
                    }
                    // resource_already_exists_exception——跨进程竞争，按成功处理
                }
                service.keywordsReady = true;
                service.keywordsErr = null;
                if (created) {
                    service.auditSink().emitIndexCreated(name, 0);
                }
            } catch (OpenSearchDriverException e) {
                if (service.keywordsErr == null) {
                    service.keywordsErr = e;
                }
                throw e;
            }
        }
    }

    /** 对照 buildIndexMapping（字段/键序照 Go json.Marshal：map 字母序、struct 声明序）。 */
    static byte[] buildIndexMapping(InternalCfg cfg, int dim) {
        Map<String, Object> index = new TreeMap<>();
        index.put("knn", true);
        index.put("number_of_shards", cfg.shards);
        index.put("number_of_replicas", cfg.replicas);
        index.put("refresh_interval", "1s");
        index.put("knn.algo_param.ef_search", cfg.efSearch);
        Map<String, Object> settings = new TreeMap<>();
        settings.put("index", index);
        Map<String, Object> mappings = new TreeMap<>();
        mappings.put("properties", properties(dim, cfg));
        Map<String, Object> body = new TreeMap<>();
        body.put("settings", settings);
        body.put("mappings", mappings);
        return json(body);
    }

    /** 对照 buildKeywordsMapping：同上但无 embedding 字段、settings 无 knn/ef_search。 */
    static byte[] buildKeywordsMapping(InternalCfg cfg) {
        Map<String, Object> index = new TreeMap<>();
        index.put("number_of_shards", cfg.shards);
        index.put("number_of_replicas", cfg.replicas);
        index.put("refresh_interval", "1s");
        Map<String, Object> settings = new TreeMap<>();
        settings.put("index", index);
        Map<String, Object> mappings = new TreeMap<>();
        mappings.put("properties", properties(0, cfg));
        Map<String, Object> body = new TreeMap<>();
        body.put("settings", settings);
        body.put("mappings", mappings);
        return json(body);
    }

    /**
     * properties 共享体（字母序：chunk_id/content/embedding/is_enabled/…/tag_id）。
     * {@code dim > 0} 时含 embedding（knn_vector + method 三键照 cfg）；否则省略。
     */
    static Map<String, Object> properties(int dim, InternalCfg cfg) {
        Map<String, Object> p = new TreeMap<>();
        if (dim > 0) {
            Map<String, Object> method = new TreeMap<>();
            method.put("engine", cfg.knnEngine);
            method.put("name", "hnsw");
            Map<String, Object> params = new TreeMap<>();
            params.put("ef_construction", cfg.hnswEfConstruction);
            params.put("m", cfg.hnswM);
            method.put("parameters", params);
            method.put("space_type", "cosinesimil");
            Map<String, Object> embedding = new TreeMap<>();
            embedding.put("type", "knn_vector");
            embedding.put("dimension", dim);
            embedding.put("method", method);
            p.put("embedding", embedding);
        }
        Map<String, Object> text = new TreeMap<>();
        text.put("analyzer", "standard");
        text.put("type", "text");
        p.put("content", text);
        p.put("chunk_id", keywordType());
        p.put("knowledge_id", keywordType());
        p.put("knowledge_base_id", keywordType());
        p.put("tag_id", keywordType());
        p.put("source_id", keywordType());
        Map<String, Object> integer = new TreeMap<>();
        integer.put("type", "integer");
        p.put("source_type", integer);
        Map<String, Object> boolType = new TreeMap<>();
        boolType.put("type", "boolean");
        p.put("is_enabled", boolType);
        p.put("is_recommended", boolType);
        return p;
    }


    static Map<String, Object> keywordType() {
        Map<String, Object> t = new TreeMap<>();
        t.put("type", "keyword");
        return t;
    }


    static byte[] json(Map<String, Object> body) {
        try {
            return OpenSearchRetrieveRepository.MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("opensearch: marshal mapping", e);
        }
    }

    /** 对照 verifyMappingMatches：embedding 字段结构指纹（漂移 → CONFIG_INVALID）。 */
    void verifyMappingMatches(String index, byte[] expectedBody) {
        String expected = extractFingerprint(expectedBody);
        String response = service.send("GET", "/" + index + "/_mapping", null, "application/json",
                OpenSearchRetrieveRepository.SEARCH_BODY_CAP);
        String actual = extractFingerprintFromResponse(response, index);
        if (!expected.equals(actual)) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "mapping drift on " + index + ": existing index has incompatible mapping;"
                            + " manual reindex required: mapping fingerprint mismatch: expected "
                            + expected + ", got " + actual
                            + ": opensearch: invalid index config");
        }
    }


    static String fingerprint(JsonNode props) {
        JsonNode emb = props.path("embedding");
        if (emb.isMissingNode()) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "embedding field missing or wrong type");
        }
        return "Dimension=" + emb.path("dimension").asInt()
                + ", M=" + emb.path("method").path("parameters").path("m").asInt()
                + ", EFConstruction="
                + emb.path("method").path("parameters").path("ef_construction").asInt()
                + ", Engine=" + emb.path("method").path("engine").asText()
                + ", SpaceType=" + emb.path("method").path("space_type").asText();
    }


    static String extractFingerprint(byte[] mappingBody) {
        try {
            JsonNode props = OpenSearchRetrieveRepository.MAPPER.readTree(mappingBody).path("mappings").path("properties");
            return fingerprint(props);
        } catch (Exception e) {
            throw new IllegalStateException("parse expected fingerprint", e);
        }
    }


    static String extractFingerprintFromResponse(String response, String index) {
        JsonNode root;
        try {
            root = OpenSearchRetrieveRepository.MAPPER.readTree(response);
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: parse mapping response: opensearch: transport error");
        }
        JsonNode props = root.path(index).path("mappings").path("properties");
        return fingerprint(props);
    }

    /** 对照 probeVersion。 */
    void probeVersion() {
        String response = service.send("GET", "/", null, "application/json", 1L << 20);
        JsonNode version;
        try {
            version = OpenSearchRetrieveRepository.MAPPER.readTree(response).path("version");
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: cluster info: transport error: opensearch: transport error");
        }
        String distribution = version.path("distribution").asText("");
        String number = version.path("number").asText("");
        if (!"opensearch".equals(distribution)) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.VERSION_UNSUPPORTED,
                    "opensearch: unsupported distribution \"" + distribution
                            + "\": opensearch: cluster version unsupported");
        }
        int[] mm = OpenSearchRetrieveRepository.parseMajorMinor(number);
        int maj = mm[0];
        int min = mm[1];
        if (maj == 1) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.VERSION_UNSUPPORTED,
                    "opensearch: 1.x EOL: opensearch: cluster version unsupported");
        }
        if (maj == 2 && min >= 0 && min <= 3) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.VERSION_UNSUPPORTED,
                    "opensearch: " + number + " lacks Lucene HNSW GA (need 2.4+)"
                            + ": opensearch: cluster version unsupported");
        }
        if (maj == 2 && min >= 4 && min <= 10) {
            log.warn("[OpenSearch] using pre-2.11 cluster {}; recommend 2.11+ LTS", number);
            return;
        }
        if (maj == 2 || maj == 3) {
            return;
        }
        throw new OpenSearchDriverException(
                OpenSearchDriverException.Kind.VERSION_UNSUPPORTED,
                "opensearch: unsupported version " + number
                        + ": opensearch: cluster version unsupported");
    }

    /** 对照 probeKNNPlugin：每节点都要有 opensearch-knn（缺节点列表 Go %v 形态）。 */
    void probeKnnPlugin() {
        String response = service.send("GET", "/_cat/plugins", null, "application/json", 1L << 20);
        JsonNode rows;
        try {
            rows = OpenSearchRetrieveRepository.MAPPER.readTree(response);
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "opensearch: cat plugins: transport error: opensearch: transport error");
        }
        Map<String, Boolean> nodes = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String name = row.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            nodes.putIfAbsent(name, false);
            if ("opensearch-knn".equals(row.path("component").asText(""))) {
                nodes.put(name, true);
            }
        }
        if (nodes.isEmpty()) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "opensearch: _cat/plugins returned no rows: opensearch: invalid index config");
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : nodes.entrySet()) {
            if (!e.getValue()) {
                missing.add(e.getKey());
            }
        }
        if (!missing.isEmpty()) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.CONFIG_INVALID,
                    "opensearch: opensearch-knn plugin missing on " + missing.size() + "/"
                            + nodes.size() + " nodes ([" + String.join(" ", missing)
                            + "]): opensearch: invalid index config");
        }
    }

    /** HEAD /_alias/&lt;name&gt;：200=true、404=false、其余按 wrapTransport 分类。 */
    boolean aliasExists(String alias) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(service.addr + "/_alias/" + alias))
                    .timeout(Duration.ofSeconds(120))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            if (service.basicAuth != null) {
                builder.header("Authorization", service.basicAuth);
            }
            HttpResponse<Void> resp =
                    service.http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            if (status == 200) {
                return true;
            }
            if (status == 404) {
                return false;
            }
            throw OpenSearchRetrieveRepository.classifyFailure(status, null);
        } catch (IOException e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "transport error: opensearch: transport error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "transport error: opensearch: transport error");
        }
    }

    /** 对照 aliasPut：PUT /_aliases，body {"actions":[{"add":{...}}]}。 */
    void aliasPut(String index, String alias) {
        Map<String, Object> add = new TreeMap<>();
        add.put("index", index);
        add.put("alias", alias);
        Map<String, Object> action = new TreeMap<>();
        action.put("add", add);
        Map<String, Object> body = new TreeMap<>();
        body.put("actions", List.of(action));
        try {
            service.send("PUT", "/_aliases", OpenSearchRetrieveRepository.MAPPER.writeValueAsBytes(body), "application/json");
        } catch (OpenSearchDriverException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenSearchDriverException(
                    OpenSearchDriverException.Kind.TRANSPORT,
                    "transport error: opensearch: transport error");
        }
    }
}
