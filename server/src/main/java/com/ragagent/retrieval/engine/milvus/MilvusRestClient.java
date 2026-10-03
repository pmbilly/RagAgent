package com.ragagent.retrieval.engine.milvus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;

/**
 * Milvus REST v2 传输层（{@code /v2/vectordb/…}）——本仓自持 REST v2（零新依赖）。
 * 覆盖度已对<b>真服务端</b>（compose 钉的
 * {@code milvusdb/milvus:v2.6.11}）逐端点实测：建集合（BM25 函数 + SparseFloatVector +
 * indexParams 内联）、load、list、upsert、query（含向量输出）、search（向量与
 * <b>BM25 文本检索</b>）、delete（过滤表达式）全部可用（见 known-issues）。
 *
 * <p>要点：① 响应信封 {@code {"code":0,"data":…,"message":…}}——code≠0 折成
 * {@link MilvusApiException}；② 认证是 {@code Authorization: Bearer <user>:<password>}
 * （明文，Milvus REST 口径）；③ 库名走<b>请求体</b>的 {@code dbName}（实测头形式无效）；
 * ④ REST v2 <b>没有模板参数</b>，过滤表达式由 {@link MilvusFilter} 内联字面量。</p>
 */
public final class MilvusRestClient {

    /** JSON 小工具（本包共用一套 ObjectMapper）。 */
    static final class Json {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private Json() {
        }

        static ObjectMapper mapper() {
            return MAPPER;
        }

        static ObjectNode object() {
            return MAPPER.createObjectNode();
        }

        static ArrayNode array() {
            return MAPPER.createArrayNode();
        }
    }

    /** Milvus API 级失败（{@code code != 0}）。 */
    public static final class MilvusApiException extends RuntimeException {

        private final int code;

        MilvusApiException(int code, String message, String path) {
            super("milvus " + path + " failed: code=" + code + ": " + message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    private final HttpClient http;
    private final String baseUrl;
    private final String authHeader;
    private final String dbName;

    public MilvusRestClient(String addr, String username, String password, String dbName,
                            SsrfGuard guard) {
        String host = addr == null || addr.trim().isEmpty() ? "localhost:19530" : addr.trim();
        String scheme = "http://";
        if (host.startsWith("http://") || host.startsWith("https://")) {
            scheme = "";
        }
        this.baseUrl = scheme + host + "/v2/vectordb";
        this.dbName = dbName == null ? "" : dbName.trim();
        this.authHeader = username == null || username.isEmpty()
                ? "" : "Bearer " + username + ":" + (password == null ? "" : password);
        if (guard != null) {
            guard.validateURLForSSRF(this.baseUrl);
        }
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                // 连接/请求超时：对端半开/挂起时调用线程会被无限阻塞（ES/OpenSearch
                // 的驱动均有 15s+60s 预算，此前四家自持客户端为 0）
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
    }

    // ── 低层请求 ───────────────────────────────────────────────────────────

    /** POST 一个端點并解信封；返回 {@code data} 节点（可为 null）。 */
    JsonNode post(String path, ObjectNode body) {
        if (!dbName.isEmpty() && !body.has("dbName")) {
            body.put("dbName", dbName);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(java.time.Duration.ofSeconds(60))
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        if (!authHeader.isEmpty()) {
            builder.header("Authorization", authHeader);
        }
        try {
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    Json.mapper().writeValueAsString(body), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("marshal milvus request body: " + e.getMessage(), e);
        }
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("milvus " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("milvus " + path + " interrupted", e);
        }
        String raw = new String(resp.body(), StandardCharsets.UTF_8);
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "milvus " + path + " failed: HTTP " + resp.statusCode() + ": " + raw);
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(raw);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "decode milvus response for " + path + ": " + raw, e);
        }
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw new MilvusApiException(code, root.path("message").asText(""), path);
        }
        return root.get("data");
    }

    // ── 集合面 ─────────────────────────────────────────────────────────────

    /** {@code collections/has}。 */
    boolean hasCollection(String collectionName) {
        ObjectNode body = Json.object();
        body.put("collectionName", collectionName);
        JsonNode data = post("/collections/has", body);
        return data != null && data.path("has").asBoolean(false);
    }

    /** {@code collections/create}（schema + indexParams 一次带齐）。 */
    void createCollection(ObjectNode body) {
        post("/collections/create", body);
    }

    /** {@code collections/load}（replicaNumber > 0 才带）。 */
    void loadCollection(String collectionName, int replicaNumber) {
        ObjectNode body = Json.object();
        body.put("collectionName", collectionName);
        if (replicaNumber > 0) {
            body.put("replicaNumber", replicaNumber);
        }
        post("/collections/load", body);
    }

    /** {@code collections/list}。 */
    List<String> listCollections() {
        JsonNode data = post("/collections/list", Json.object());
        List<String> names = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode node : data) {
                names.add(node.asText(""));
            }
        }
        return names;
    }

    // ── 数据面 ─────────────────────────────────────────────────────────────

    /** {@code entities/upsert}（逐行 JSON 形态）。 */
    JsonNode upsert(String collectionName, ArrayNode rows) {
        ObjectNode body = Json.object();
        body.put("collectionName", collectionName);
        body.set("data", rows);
        return post("/entities/upsert", body);
    }

    /** {@code entities/query}（filter + outputFields + limit/offset）。 */
    JsonNode query(String collectionName, String filter, List<String> outputFields,
                   Integer limit, Integer offset) {
        ObjectNode body = Json.object();
        body.put("collectionName", collectionName);
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", filter);
        }
        if (outputFields != null && !outputFields.isEmpty()) {
            ArrayNode fields = body.putArray("outputFields");
            outputFields.forEach(fields::add);
        }
        if (limit != null) {
            body.put("limit", limit);
        }
        if (offset != null) {
            body.put("offset", offset);
        }
        return post("/entities/query", body);
    }

    /**
     * {@code entities/search}：{@code data} 既可以是向量数组（向量检索），也可以是
     * 文本字符串数组（BM25 全文检索，{@code annsField=content_sparse}）。
     * {@code radius}（>0 时）走 {@code searchParams.radius}。
     */
    JsonNode search(String collectionName, JsonNode data, String annsField, String filter,
                    int limit, List<String> outputFields, Double radius) {
        ObjectNode body = Json.object();
        body.put("collectionName", collectionName);
        body.set("data", data);
        body.put("annsField", annsField);
        if (filter != null && !filter.isEmpty()) {
            body.put("filter", filter);
        }
        body.put("limit", limit);
        if (outputFields != null && !outputFields.isEmpty()) {
            ArrayNode fields = body.putArray("outputFields");
            outputFields.forEach(fields::add);
        }
        if (radius != null && radius > 0) {
            body.putObject("searchParams").put("radius", radius);
        }
        return post("/entities/search", body);
    }

    /** {@code entities/delete}（过滤表达式，如 {@code field in [...]}）。 */
    JsonNode delete(String collectionName, String filter) {
        ObjectNode body = Json.object();
        body.put("collectionName", collectionName);
        body.put("filter", filter);
        return post("/entities/delete", body);
    }

    // ── 探针 ───────────────────────────────────────────────────────────────

    /** 探针：{@code collections/list} 成功即视为连通（Milvus 无版本端点，版本恒 ""）。 */
    boolean probe() {
        post("/collections/list", Json.object());
        return true;
    }
}
