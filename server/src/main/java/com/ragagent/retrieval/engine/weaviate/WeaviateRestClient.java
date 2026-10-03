package com.ragagent.retrieval.engine.weaviate;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;

/**
 * Weaviate REST 传输层——统一自持 REST（端点与报文形状逐条对照
 * 客户端源码 + 真实 Weaviate 1.28.4 实测，见 known-issues）。
 *
 * <p>端点映射：class 判存 {@code GET /v1/schema/{cls}}、建类 {@code POST /v1/schema}、
 * 单对象创建 {@code POST /v1/objects}（id 在 body）、
 * 合并更新 {@code PATCH /v1/objects/{cls}/{id}}（期望 204）、
 * 检索 {@code POST /v1/graphql}、探针 {@code GET /v1/.well-known/ready} + {@code GET /v1/meta}。</p>
 *
 * <p>认证：{@code Authorization: Bearer <api-key>}。SSRF：构造期校验
 * base URL 一次（本仓同其它 Java 驱动姿态）。</p>
 */
public final class WeaviateRestClient {

    /** JSON 小工具（本包内共用一套 ObjectMapper）。 */
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

    /** 非 2xx 的失败载体（报文原文只进日志/异常 message）。 */
    public static final class WeaviateHttpException extends RuntimeException {

        private final int status;
        private final String body;

        WeaviateHttpException(int status, String body, String path) {
            super("weaviate " + path + " failed: HTTP " + status + ": " + body);
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;

    public WeaviateRestClient(String host, String scheme, String apiKey, SsrfGuard guard) {
        this.baseUrl = buildBaseUrl(host, scheme);
        this.apiKey = apiKey == null ? "" : apiKey;
        if (guard != null) {
            guard.validateURLForSSRF(this.baseUrl);
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 建 base URL：{@code <scheme>://<host>}（scheme 缺省 http，host 缺省 {@code weaviate:8080}）。 */
    public static String buildBaseUrl(String host, String scheme) {
        String h = host == null || host.trim().isEmpty() ? "weaviate:8080" : host.trim();
        String s = scheme == null || scheme.trim().isEmpty() ? "http" : scheme.trim();
        String base = s + "://" + h;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    // ── 低层请求 ───────────────────────────────────────────────────────────

    /** 发请求并解析 JSON（{@code allowNotFound=true} 时 404 返回 null）。 */
    JsonNode request(String method, String path, Object body, boolean allowNotFound) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(java.time.Duration.ofSeconds(60))
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        Json.mapper().writeValueAsString(body), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("marshal weaviate request body: " + e.getMessage(), e);
        }
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("weaviate " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("weaviate " + path + " interrupted", e);
        }
        String raw = new String(resp.body(), StandardCharsets.UTF_8);
        if (allowNotFound && resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() / 100 != 2) {
            throw new WeaviateHttpException(resp.statusCode(), raw, path);
        }
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Json.mapper().readTree(raw);
        } catch (IOException e) {
            throw new IllegalStateException("decode weaviate response for " + path + ": "
                    + e.getMessage() + " (raw=" + raw + ")", e);
        }
    }

    private JsonNode request(String method, String path, Object body) {
        return request(method, path, body, false);
    }

    static String pathEscape(String segment) {
        return URLEncoder.encode(segment == null ? "" : segment, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    // ── schema 面 ──────────────────────────────────────────────────────────

    /** {@code GET /v1/schema}。 */
    JsonNode schema() {
        return request("GET", "/v1/schema", null);
    }

    /** {@code GET /v1/schema/{cls}}：存在 → true；404 → false。 */
    boolean classExists(String className) {
        JsonNode node = request("GET", "/v1/schema/" + pathEscape(className), null, true);
        return node != null;
    }

    /** {@code POST /v1/schema}（期望 200）。 */
    void createClass(ObjectNode classBody) {
        request("POST", "/v1/schema", classBody);
    }

    // ── 数据面 ─────────────────────────────────────────────────────────────

    /** {@code POST /v1/objects}（id 在 body；期望 200）。 */
    JsonNode createObject(ObjectNode object) {
        return request("POST", "/v1/objects", object);
    }

    /** {@code POST /v1/batch/objects}（REST 批量创建；body 含 {@code fields:["ALL"]}）。 */
    JsonNode batchCreate(ArrayNode objects) {
        ObjectNode body = Json.object();
        ArrayNode fields = body.putArray("fields");
        fields.add("ALL");
        body.set("objects", objects);
        return request("POST", "/v1/batch/objects", body);
    }

    /** {@code DELETE /v1/batch/objects}。 */
    JsonNode batchDelete(String className, ObjectNode where, String output) {
        ObjectNode body = Json.object();
        if (output != null && !output.isEmpty()) {
            body.put("output", output);
        }
        ObjectNode match = body.putObject("match");
        match.put("class", className);
        match.set("where", where);
        return request("DELETE", "/v1/batch/objects", body);
    }

    /** {@code PATCH /v1/objects/{cls}/{id}}（merge；期望 204）。 */
    void mergeUpdate(String className, String id, ObjectNode properties) {
        ObjectNode body = Json.object();
        body.put("class", className);
        body.put("id", id);
        body.set("properties", properties);
        request("PATCH", "/v1/objects/" + pathEscape(className) + "/" + pathEscape(id), body);
    }

    /** {@code POST /v1/graphql}（检索/列举；GraphQL 级错误在响应体的 errors 里，由调用方判）。 */
    JsonNode graphql(String query) {
        ObjectNode body = Json.object();
        body.put("query", query);
        return request("POST", "/v1/graphql", body);
    }

    // ── 探针 ───────────────────────────────────────────────────────────────

    /**
     * {@code GET /v1/.well-known/ready}：非 200 视为
     * 未就绪，不把 HTTP 异常漏给调用方。
     */
    boolean ready() {
        try {
            return request("GET", "/v1/.well-known/ready", null, true) != null;
        } catch (WeaviateHttpException e) {
            return false;
        }
    }

    /** {@code GET /v1/meta}；失败/缺字段 → ""（宽容分支）。 */
    String metaVersion() {
        JsonNode meta;
        try {
            meta = request("GET", "/v1/meta", null);
        } catch (RuntimeException e) {
            return "";
        }
        if (meta == null) {
            return "";
        }
        return meta.path("version").asText("");
    }
}
