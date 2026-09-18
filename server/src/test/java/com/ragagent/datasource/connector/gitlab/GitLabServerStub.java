package com.ragagent.datasource.connector.gitlab;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.ConnectorHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * GitLab API 的最小桩服务（对照 Go 测试里的 {@code httptest.NewServer}）。
 *
 * <p>绑 {@code 127.0.0.1}、端口 0 自动分配，通过
 * {@link #baseUrl()} 拿到 {@code http://127.0.0.1:<port>} 当 {@code base_url}
 * ——它已含 {@code ://}，所以 {@code newClient} 只会补 {@code /api/v4}。</p>
 *
 * <h2>SSRF 白名单</h2>
 * <p>{@code ConnectorHttp} 的出站校验会拦掉直连 IP，所以每个用桩的测试类都要在
 * {@code @BeforeAll} 调 {@link #allowLocalServer()}
 * （对应 Go 的 {@code t.Setenv("SSRF_WHITELIST", "127.0.0.1,::1,localhost")}），
 * 并在 {@code @AfterAll} 调 {@link #restoreSsrfGuard()}。
 * <b>不要</b>在测试里写真实公网域名。</p>
 */
final class GitLabServerStub implements AutoCloseable {

    /** 路由处理体；只允许抛 {@link IOException}（与 {@code HttpHandler} 对齐）。 */
    @FunctionalInterface
    interface Route {
        void handle(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;

    /** 收到的请求路径（<b>未解码</b>的原文，用来断言 {@code %2E} 之类）。 */
    final List<String> requestPaths = new CopyOnWriteArrayList<>();
    /** 收到的原始 query 串（含分隔符，按顺序）。 */
    final List<String> requestQueries = new CopyOnWriteArrayList<>();
    /** 收到的 PRIVATE-TOKEN（按顺序）。 */
    final List<String> privateTokens = new CopyOnWriteArrayList<>();

    GitLabServerStub(Route route) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                requestPaths.add(rawPath(exchange));
                String query = exchange.getRequestURI().getRawQuery();
                requestQueries.add(query == null ? "" : query);
                String token = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");
                privateTokens.add(token == null ? "" : token);
                route.handle(exchange);
            } catch (RuntimeException e) {
                // 测试代码里的断言失败要从 handler 传播出来（HttpServer 会吞掉异常）
                throw e;
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    /** 对照 Go 的 {@code t.Setenv("SSRF_WHITELIST", "127.0.0.1,::1,localhost")}。 */
    static void allowLocalServer() {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1,::1,localhost");
        ConnectorHttp.setSsrfGuard(guard);
    }

    /**
     * 配对 {@link #allowLocalServer()}，在 {@code @AfterAll} 里调用。
     *
     * <p>白名单是<b>进程级静态状态</b>（{@code SsrfGuard.whitelist}），
     * 换一个 guard 实例并不会把它还原——所以这里除了换回默认 guard，
     * 还要把白名单重设成 env 推导出来的原文，否则同一个 JVM 里排在后面的测试类
     * 会看到"本机回环被意外放行"。与 {@code NotionTestSupport.restoreSsrf} /
     * {@code FeishuTestSupport} 的处置一致。</p>
     */
    static void restoreSsrfGuard() {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist(envWhitelistRaw());
        ConnectorHttp.setSsrfGuard(guard);
    }

    /** 对照 {@code SsrfGuard.mergeRaws(SSRF_WHITELIST, SSRF_WHITELIST_EXTRA)}。 */
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

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ── 响应工具 ─────────────────────────────────────────────────────────

    /** 未解码的请求路径（{@code %E4%B8%AD} 保持原样，不被 JDK 解成中文）。 */
    static String rawPath(HttpExchange exchange) {
        return exchange.getRequestURI().getRawPath();
    }

    static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    static void json(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, "application/json", body);
    }

    static void text(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, "text/plain", body);
    }

    /** 只回状态行、无正文（对照 Go 的 {@code w.WriteHeader(...)} / {@code http.NotFound}）。 */
    static void status(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
