package com.ragagent.retrieval.engine.tencentvectordb;

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
 * 腾讯 VectorDB <b>HTTP API</b> 传输层——本仓自持 SDK 的 HTTP 面（同一服务端的等价接口，SDK 的
 * {@code tcvectordb.Client} 走的就是它），零新依赖、不引 protobuf。
 *
 * <h2>wire 形状（对齐 SDK v1.8.4）</h2>
 * <ul>
 *   <li>鉴权：{@code Authorization: Bearer account=<username>&api_key=<key>}（明文，非 TC3）；</li>
 *   <li>头：{@code Content-Type: application/json} + {@code Sdk-Version: v1.8.4}；</li>
 *   <li>端点：{@code /database/create|list|drop}、{@code /collection/create|describe|list}、
 *       {@code /document/upsert|search|fullTextSearch|query|delete|update}
 *       （库名/集合名在<b>请求体</b>里，路径是静态的）；</li>
 *   <li>响应信封 {@code {code,msg,...}}：HTTP 非 2xx → {@code response code is %d, %s}；
 *       {@code code != 0} → {@code code: %d, message: %s}。</li>
 * </ul>
 *
 * <p>地址形态照 SDK：必须 {@code http://}（或裸 host，自动补 http://）；<b>https:// 被 SDK 拒绝</b>
 * （"invalid url param with %v for not supporting https://"）——本仓同样拒。</p>
 */
public final class TencentVectorDbRestClient {

    /** 与 SDK 一致的版本头。 */
    static final String SDK_VERSION = "v1.8.4";

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

    private final HttpClient http;
    private final String baseUrl;
    private final String authHeader;

    public TencentVectorDbRestClient(String addr, String username, String apiKey, SsrfGuard guard) {
        String url = addr == null ? "" : addr.trim();
        if (!url.startsWith("http://")) {
            if (url.startsWith("https://")) {
                throw new IllegalStateException(
                        "invalid url param with " + url + " for not supporting https://");
            }
            url = "http://" + url;
        }
        if (username == null || username.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("username or key is empty");
        }
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.authHeader = "Bearer account=" + username + "&api_key=" + apiKey;
        if (guard != null) {
            guard.validateURLForSSRF(this.baseUrl);
        }
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(java.time.Duration.ofSeconds(15)).build();
    }

    // ── 低层 ───────────────────────────────────────────────────────────────

    private JsonNode send(String method, String path, JsonNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(java.time.Duration.ofSeconds(60)).uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .header("Sdk-Version", SDK_VERSION);
        try {
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        Json.mapper().writeValueAsString(body == null ? Json.object() : body),
                        StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("marshal tencent vectordb request: " + e.getMessage(), e);
        }
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "tencent vectordb " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tencent vectordb " + path + " interrupted", e);
        }
        String raw = new String(resp.body(), StandardCharsets.UTF_8);
        if (resp.statusCode() / 100 != 2) {
            throw new TencentVectorDbApiException(
                    "response code is " + resp.statusCode() + ", " + raw);
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(raw);
        } catch (IOException e) {
            throw new IllegalStateException("invalid response content: " + raw, e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("invalid response content: " + raw);
        }
        int code = root.path("code").asInt(0);
        if (code != 0) {
            throw new TencentVectorDbApiException(
                    "code: " + code + ", message: " + root.path("msg").asText(""));
        }
        return root;
    }

    /** API 级失败（{@code code != 0} 或非 2xx）；文案与 SDK 的 handleResponse 一致。 */
    public static final class TencentVectorDbApiException extends RuntimeException {

        TencentVectorDbApiException(String message) {
            super(message);
        }
    }

    // ── 库 / 集合 ──────────────────────────────────────────────────────────

    List<String> listDatabases() {
        JsonNode root = send("GET", "/database/list", null);
        List<String> names = new ArrayList<>();
        for (JsonNode node : root.path("databases")) {
            names.add(node.asText(""));
        }
        return names;
    }

    void createDatabase(String database) {
        ObjectNode body = Json.object();
        body.put("database", database);
        send("POST", "/database/create", body);
    }

    /** 建库（如缺）：列表查不到才建。 */
    void createDatabaseIfNotExists(String database) {
        if (listDatabases().contains(database)) {
            return;
        }
        createDatabase(database);
    }

    JsonNode describeCollection(String database, String collection) {
        ObjectNode body = Json.object();
        body.put("database", database);
        body.put("collection", collection);
        return send("POST", "/collection/describe", body);
    }

    /** 集合存在性：describe 报 15202（未定义集合）→ false。 */
    boolean existsCollection(String database, String collection) {
        try {
            describeCollection(database, collection);
            return true;
        } catch (TencentVectorDbApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("15202")) {
                return false;
            }
            throw e;
        }
    }

    JsonNode listCollections(String database) {
        ObjectNode body = Json.object();
        body.put("database", database);
        return send("POST", "/collection/list", body);
    }

    void createCollection(ObjectNode body) {
        send("POST", "/collection/create", body);
    }

    // ── 文档 ───────────────────────────────────────────────────────────────

    JsonNode upsert(String database, String collection, ArrayNode documents, boolean buildIndex) {
        ObjectNode body = Json.object();
        body.put("database", database);
        body.put("collection", collection);
        body.put("buildIndex", buildIndex);
        body.set("documents", documents);
        return send("POST", "/document/upsert", body);
    }

    JsonNode search(String database, String collection, ObjectNode searchCond) {
        ObjectNode body = Json.object();
        body.put("database", database);
        body.put("collection", collection);
        // 读一致性 = eventualConsistency（SDK 默认）
        body.put("readConsistency", "eventualConsistency");
        body.set("search", searchCond);
        return send("POST", "/document/search", body);
    }

    JsonNode fullTextSearch(String database, String collection, ObjectNode searchCond) {
        ObjectNode body = Json.object();
        body.put("database", database);
        body.put("collection", collection);
        body.put("readConsistency", "eventualConsistency");
        body.set("search", searchCond);
        return send("POST", "/document/fullTextSearch", body);
    }

    JsonNode query(String database, String collection, ObjectNode queryCond) {
        ObjectNode body = Json.object();
        body.put("database", database);
        body.put("collection", collection);
        body.set("query", queryCond);
        return send("POST", "/document/query", body);
    }

    void delete(String database, String collection, ObjectNode queryCond) {
        ObjectNode body = Json.object();
        body.put("database", database);
        body.put("collection", collection);
        body.set("query", queryCond);
        send("POST", "/document/delete", body);
    }

    void update(String database, String collection, ObjectNode queryCond, ObjectNode fields) {
        ObjectNode body = Json.object();
        body.put("database", database);
        body.put("collection", collection);
        body.set("query", queryCond);
        body.set("update", fields);
        send("POST", "/document/update", body);
    }

    /** 探针：{@code /database/list} 成功即连通。 */
    void probe() {
        listDatabases();
    }
}
