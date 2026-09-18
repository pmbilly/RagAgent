package com.ragagent.datasource.connector.yuque;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 语雀 Open API v2 的进程内替身（对照 Go {@code yuque/client_test.go} 里的
 * {@code fakeYuque}），绑在 {@code 127.0.0.1} 的随机端口上。
 *
 * <h2>用法</h2>
 * <p>默认已经挂好 {@code GET /api/v2/user}；其余端点用
 * {@link #handleJson(String, int, Object)} 逐个登记（path 精确匹配，query 被忽略）。
 * 每个响应体都用 {@link LinkedHashMap} 手写键名——<b>刻意不复用生产 DTO</b>，
 * 这样 fake 是一份独立的线上形状描述（拼错的键名不会两边一起错）。</p>
 *
 * <h2>SSRF</h2>
 * <p>与 IMA 侧同一处置：{@link #allowLoopback()} / {@link #restoreSsrf()}，
 * 后者必须在 {@code @AfterAll} 调用（白名单是进程级静态状态）。</p>
 */
final class FakeYuque implements AutoCloseable {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一条登记好的响应。 */
    private record Canned(int status, Object body) {
    }

    private final HttpServer server;
    private final Map<String, Canned> handlers = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<JsonNode> requestBodies = new CopyOnWriteArrayList<>();

    FakeYuque() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();

        // 默认的 /api/v2/user（多数用例至少会打它一次）。
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", 1);
        user.put("login", "me");
        user.put("name", "Me");
        handleJson("/api/v2/user", 200, data(user));
    }

    // ── SSRF 白名单（对照 Go 的 TestMain） ─────────────────────────────────

    static void allowLoopback() {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1,::1,localhost");
        ConnectorHttp.setSsrfGuard(guard);
    }

    static void restoreSsrf() {
        ConnectorHttp.setSsrfGuard(new SsrfGuard());
        ConnectorHttp.ssrfGuard().reloadWhitelist(envWhitelistRaw());
    }

    private static String envWhitelistRaw() {
        String primary = System.getenv("SSRF_WHITELIST");
        String extra = System.getenv("SSRF_WHITELIST_EXTRA");
        primary = primary == null ? "" : primary.trim();
        extra = extra == null ? "" : extra.trim();
        if (primary.isEmpty()) {
            return extra;
        }
        if (extra.isEmpty()) {
            return primary;
        }
        return primary + "," + extra;
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 登记一条响应；同名 path 覆盖（Go 的 fake 会 panic，Java 侧刻意为覆盖以便简化用例）。 */
    void handleJson(String path, int status, Object body) {
        handlers.put(path, new Canned(status, body));
    }

    /** 便利构造：{@code {"data": <payload>}}。 */
    static Map<String, Object> data(Object payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", payload);
        return out;
    }

    /** 便利构造：{@code {"meta":{"total":N},"data":[...]}}。 */
    static Map<String, Object> docList(List<Map<String, Object>> docs) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", docs.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("data", docs);
        return out;
    }

    /** 便利构造：一篇文档摘要（列表接口的形状）。 */
    static Map<String, Object> doc(long id, String type, Object status, String title,
                                   String slug, String contentUpdatedAt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", type);
        out.put("status", status);
        out.put("title", title);
        out.put("slug", slug);
        out.put("content_updated_at", contentUpdatedAt);
        out.put("word_count", 42);
        out.put("user_id", 7);
        return out;
    }

    /** 便利构造：一篇文档详情（详情接口的形状）。 */
    static Map<String, Object> docDetail(long id, String title, String format, String body,
                                         String contentUpdatedAt, String bookNamespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", "Doc");
        out.put("title", title);
        out.put("format", format);
        out.put("body", body);
        out.put("status", "1");
        out.put("content_updated_at", contentUpdatedAt);
        out.put("word_count", 3);
        Map<String, Object> book = new LinkedHashMap<>();
        book.put("namespace", bookNamespace);
        out.put("book", book);
        return out;
    }

    void resetCalls() {
        calls.clear();
    }

    /** 全部请求的 {@code "METHOD path?query"} 记录（对照 Go 的 {@code calls}）。 */
    List<String> calls() {
        return new ArrayList<>(calls);
    }

    /** 某个精确 path 被请求的次数。 */
    long callCount(String path) {
        return calls.stream().filter(c -> c.contains(" " + path + "?") || c.endsWith(" " + path)).count();
    }

    /** 某次请求的查询串（不含 {@code ?}），找不到回空串。 */
    String queryOf(String path) {
        for (String c : calls) {
            int q = c.indexOf('?');
            if (q >= 0 && c.substring(0, q).endsWith(" " + path)) {
                return c.substring(q + 1);
            }
        }
        return "";
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String method = ex.getRequestMethod();
        calls.add(method + " " + path + "?" + (query == null ? "" : query));
        byte[] raw = ex.getRequestBody().readAllBytes();
        requestBodies.add(raw.length == 0 ? null : MAPPER.readTree(raw));

        Canned canned = handlers.get(path);
        if (canned == null) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("message", "not found");
            send(ex, 404, MAPPER.writeValueAsBytes(notFound));
            return;
        }
        byte[] body = MAPPER.writeValueAsBytes(canned.body());
        send(ex, canned.status(), body);
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /** 构造 connector 用的配置（base_url 指向本 stub）。 */
    DataSourceConfig config(String... resourceIds) {
        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setType("yuque");
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("api_token", "tok-super-secret-value-1234");
        credentials.put("base_url", baseUrl());
        cfg.setCredentials(credentials);
        cfg.setResourceIds(new ArrayList<>(List.of(resourceIds)));
        return cfg;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
