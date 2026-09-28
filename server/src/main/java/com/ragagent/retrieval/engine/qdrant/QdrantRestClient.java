package com.ragagent.retrieval.engine.qdrant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;

/**
 * Qdrant REST 传输层——本仓的"协议决策"落地口径（Go 用 {@code qdrant/go-client} 走 gRPC，
 * 本仓照 ES/OpenSearch 先例自持 HTTP/JSON；语义逐条对照 gRPC 客户端调用点）。
 *
 * <p>信封约定：Qdrant 成功响应是 {@code {"result":…,"status":"ok"}}，失败是非 2xx +
 * {@code {"status":{"error":"…"}}}。本类把非 2xx 折叠成 {@link QdrantHttpException}
 * （status + 报文原文），成功时返回 {@code result} 节点（缺失时返回 {@code null}）。</p>
 *
 * <p><b>与 Go 的差异（备案）</b>：① 传输从 gRPC 换 REST（端点映射见各类方法注释；
 * 语义等价面：过滤 DSL、score_threshold、match any、text match、scroll offset）；
 * ② Go 的 SSRF 在 gRPC dialer 逐连接校验；本仓在构造期校验地址一次（同其它 Java 驱动），
 * 且 api-key 只发往用户配置的 host；③ 超时无请求级 ctx（Java 驱动族同姿态）。</p>
 */
public final class QdrantRestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;

    public QdrantRestClient(String baseUrl, String apiKey, SsrfGuard guard) {
        this.baseUrl = trimRightSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        if (guard != null) {
            guard.validateURLForSSRF(this.baseUrl);
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 建 Qdrant REST base URL：host[:port] + TLS 开关（端口缺省 6334，照 Go）。 */
    public static String buildBaseUrl(String host, int port, boolean useTls) {
        String h = host == null ? "" : host.trim();
        int p = port <= 0 ? 6334 : port;
        return (useTls ? "https" : "http") + "://" + h + ":" + p;
    }

    /** 非 2xx 的失败载体（报文原文只进日志/异常 message，不上抛给最终用户）。 */
    public static final class QdrantHttpException extends RuntimeException {

        private final int status;
        private final String body;

        QdrantHttpException(int status, String body, String path) {
            super("qdrant " + path + " failed: HTTP " + status + ": " + body);
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

    /**
     * 发出请求并返回 {@code result} 节点（无 result 键 → null）。
     * {@code allowNotFound} = true 时 404 返回 null（集合存在性探测用）。
     */
    JsonNode request(String method, String path, Object body, boolean allowNotFound) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(java.time.Duration.ofSeconds(60))
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        if (!apiKey.isEmpty()) {
            builder.header("api-key", apiKey);
        }
        try {
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("marshal qdrant request body: " + e.getMessage(), e);
        }
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("qdrant " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("qdrant " + path + " interrupted", e);
        }
        String raw = new String(resp.body(), StandardCharsets.UTF_8);
        if (allowNotFound && resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() / 100 != 2) {
            throw new QdrantHttpException(resp.statusCode(), raw, path);
        }
        if (raw.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            return root.has("result") ? root.get("result") : null;
        } catch (IOException e) {
            throw new IllegalStateException("decode qdrant response for " + path + ": "
                    + e.getMessage() + " (raw=" + raw + ")", e);
        }
    }

    JsonNode request(String method, String path, Object body) {
        return request(method, path, body, false);
    }

    /** 探针结果壳：状态码 + 报文原文（{@code GET /} 的 version 在顶层，不在 result 信封里）。 */
    public record HttpProbe(int status, String body) {
    }

    /** 探针用裸 GET（不解析 result 信封、不抛非 2xx——由调用方判定）。 */
    HttpProbe rawGet(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(java.time.Duration.ofSeconds(60))
                .uri(URI.create(baseUrl + path))
                .GET();
        if (!apiKey.isEmpty()) {
            builder.header("api-key", apiKey);
        }
        try {
            HttpResponse<byte[]> resp =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new HttpProbe(resp.statusCode(),
                    new String(resp.body(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("qdrant " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("qdrant " + path + " interrupted", e);
        }
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    static String trimRightSlash(String s) {
        String out = s == null ? "" : s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
