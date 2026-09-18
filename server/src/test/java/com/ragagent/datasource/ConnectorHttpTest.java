package com.ragagent.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

import com.ragagent.common.security.SsrfGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link ConnectorHttp} 的语义测试（对照 Go {@code internal/datasource/httpclient.go} +
 * {@code internal/utils/security.go} 的 {@code NewSSRFSafeHTTPClient} /
 * {@code newSSRFCheckRedirect} / {@code SSRFValidatingRoundTripper}）。
 *
 * <h2>为什么必须用 stub server</h2>
 * <p>§7.5 第 7 条：测试**禁止依赖真实网络**（本机 DNS 会把公网域名解析到受限段，
 * 表现为随机的 SSRF 拒绝）。Go 侧的连接器测试全都起 {@code httptest} 并把
 * {@code SSRF_WHITELIST} 设成 {@code 127.0.0.1,::1,localhost}；Java 侧对应
 * {@link SsrfGuard#reloadWhitelist}。</p>
 *
 * <h2>⚠️ 白名单是进程级静态状态</h2>
 * <p>{@code SsrfGuard.whitelist} 是 static（它的类注释解释了为什么）。
 * 本类在 {@link #setUp()} 里放行 loopback，在 {@link #tearDown()} 里还原成
 * "读 env 的默认值"，避免污染其它测试类。</p>
 *
 * <h2>⚠️ JDK HttpServer 的两个坑（实测）</h2>
 * <ol>
 *   <li><b>必须显式 {@code setExecutor(...)}</b>：不设时 handler 跑在分派线程上，
 *       {@code ServerImpl.Dispatcher} 会空转（CPU 100%）且客户端永远等不到响应。</li>
 *   <li><b>{@code getRequestBody()} 只能读一次</b>：读完再读得到空串且不报错。
 *       所以 {@link #dispatch} 先读出来再交给各路由。</li>
 * </ol>
 */
class ConnectorHttpTest {

    /** 主 stub：loopback 白名单内的同源目标。 */
    private static HttpServer server;
    private static String base;

    /** 第二个 stub：与主 stub 是**不同的 authority**，用来构造跨域跳转。 */
    private static HttpServer other;
    private static String otherBase;

    private record Recorded(String path, Map<String, String> headers, String body) {
    }

    private static final List<Recorded> RECORDED = new ArrayList<>();

    @BeforeAll
    static void setUp() throws IOException {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1,::1,localhost");
        ConnectorHttp.setSsrfGuard(guard);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ConnectorHttpTest::dispatch);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        otherBase = "http://127.0.0.1:" + other.getAddress().getPort();
        other.createContext("/target", exchange -> echoAuth(exchange));
        other.createContext("/from-same-origin", exchange ->
                respond(exchange, 302, "", Map.of("Location", otherBase + "/target")));
        other.createContext("/from-cross-origin", exchange ->
                respond(exchange, 302, "", Map.of("Location", base + "/target")));
        other.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (other != null) {
            other.stop(0);
        }
        ConnectorHttp.setSsrfGuard(new SsrfGuard());
        new SsrfGuard().reloadWhitelist(mergeEnvWhitelist());
    }

    /** 还原成"env 里那一份"（{@code SsrfGuard} 的默认初始化逻辑）。 */
    private static String mergeEnvWhitelist() {
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

    private static void dispatch(HttpExchange exchange) throws IOException {
        // ⚠️ getRequestBody() 只能读一次：先读出来，路由复用
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.put(k.toLowerCase(Locale.ROOT), v.get(0));
            }
        });
        RECORDED.add(new Recorded(exchange.getRequestURI().toString(), headers, body));

        switch (exchange.getRequestURI().getPath()) {
            case "/ok" -> respond(exchange, 200, "hello", Map.of("X-Trace", "t-1"));
            case "/missing" -> respond(exchange, 404, "{\"error\":\"nope\"}", Map.of());
            case "/boom" -> respond(exchange, 500, "kaboom", Map.of());
            case "/redirect" -> respond(exchange, 302, "", Map.of("Location", base + "/ok"));
            case "/redirect-no-location" -> respond(exchange, 302, "body-of-302", Map.of());
            case "/redirect-loop" ->
                    respond(exchange, 302, "", Map.of("Location", base + "/redirect-loop"));
            case "/target" -> echoAuth(exchange);
            default -> respond(exchange, 404, "unknown", Map.of());
        }
    }

    /** 回显收到的 {@code Authorization} 头，用来验证跨域跳转有没有剥凭据。 */
    private static void echoAuth(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        respond(exchange, 200, "auth=" + (auth == null ? "<none>" : auth), Map.of());
    }

    private static void respond(HttpExchange exchange, int status, String body,
                                Map<String, String> headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static ConnectorHttp.Client client() {
        return ConnectorHttp.newConnectorHttpClient(Duration.ofSeconds(10));
    }

    // ── validateConnectorBaseUrl（对照 Go ValidateConnectorBaseURL） ───────

    @Test
    void emptyBaseUrlIsAllowed() {
        ConnectorHttp.validateConnectorBaseUrl("");
        ConnectorHttp.validateConnectorBaseUrl(null);
        ConnectorHttp.validateConnectorBaseUrl("   ");
    }

    @Test
    void baseUrlWithoutSchemeIsPrefixedWithHttpsBeforeValidation() {
        // 放行过的 loopback 主机，裸写（无 scheme）也要过
        ConnectorHttp.validateConnectorBaseUrl("127.0.0.1");
        ConnectorHttp.validateConnectorBaseUrl(" 127.0.0.1 ");
    }

    /**
     * 没有 scheme 的主机会先被补成 {@code https://<host>} 再校验——所以直接写一个
     * 裸内网 IP 一样会被拒（裸 IP 一律不允许）。错误文本与 Go 的
     * {@code fmt.Errorf("base_url SSRF validation failed: %w", err)} 同形。
     */
    @Test
    void ssrfRejectedBaseUrlCarriesGoPrefix() {
        ConnectorHttp.ssrfGuard().reloadWhitelist("example.com");
        try {
            assertThatThrownBy(() -> ConnectorHttp.validateConnectorBaseUrl("http://10.0.0.5"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageStartingWith("base_url SSRF validation failed: ")
                    .hasMessageContaining("direct IP address access is not allowed");
        } finally {
            ConnectorHttp.ssrfGuard().reloadWhitelist("127.0.0.1,::1,localhost");
        }
    }

    // ── Client 的基本行为 ────────────────────────────────────────────────

    @Test
    void getReturnsStatusBodyAndHeaders() {
        ConnectorHttp.Response resp = client().get(base + "/ok", Map.of("X-Custom", "v"));
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.statusLine()).isEqualTo("200 OK");
        assertThat(resp.bodyAsString()).isEqualTo("hello");
        assertThat(resp.header("x-trace")).isEqualTo("t-1");
        assertThat(resp.header("absent")).isEmpty();
        assertThat(resp.ok()).isTrue();
        assertThat(RECORDED.get(RECORDED.size() - 1).headers()).containsEntry("x-custom", "v");
    }

    /** 非 2xx 是**正常返回**（由调用方判），不是异常——对照 Go 的 {@code client.Do} 语义。 */
    @Test
    void nonSuccessStatusIsReturnedNotThrown() {
        ConnectorHttp.Response notFound = client().get(base + "/missing", null);
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.ok()).isFalse();
        assertThat(notFound.bodyAsString()).isEqualTo("{\"error\":\"nope\"}");

        ConnectorHttp.Response boom = client().get(base + "/boom", null);
        assertThat(boom.status()).isEqualTo(500);
        assertThat(boom.statusLine()).isEqualTo("500 Internal Server Error");
    }

    @Test
    void postCarriesBodyAndHeaders() {
        ConnectorHttp.Response resp = client().post(base + "/ok",
                Map.of("Content-Type", "application/json"),
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(resp.status()).isEqualTo(200);
        Recorded last = RECORDED.get(RECORDED.size() - 1);
        assertThat(last.body()).isEqualTo("{\"a\":1}");
        assertThat(last.headers()).containsEntry("content-type", "application/json");
    }

    /** {@code truncatedBody} 对照 Go 的同名函数：超长才截断并补 {@code "..."}。 */
    @Test
    void truncatedBodyMatchesGoTruncate() {
        ConnectorHttp.Response resp = client().get(base + "/ok", null);
        assertThat(resp.truncatedBody(5)).isEqualTo("hello");
        assertThat(resp.truncatedBody(4)).isEqualTo("hell...");
        assertThat(resp.truncatedBody(0)).isEqualTo("...");
    }

    // ── 重定向（对照 Go 的 newSSRFCheckRedirect） ─────────────────────────

    @Test
    void followsSameOriginRedirect() {
        ConnectorHttp.Response resp = client().get(base + "/redirect", null);
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.bodyAsString()).isEqualTo("hello");
    }

    /** 3xx 但没有 Location → 原样返回（Go 的行为）。 */
    @Test
    void redirectWithoutLocationIsReturnedAsIs() {
        ConnectorHttp.Response resp = client().get(base + "/redirect-no-location", null);
        assertThat(resp.status()).isEqualTo(302);
        assertThat(resp.bodyAsString()).isEqualTo("body-of-302");
    }

    @Test
    void stopsAfterMaxRedirectsWithGoMessage() {
        assertThatThrownBy(() -> client().get(base + "/redirect-loop", null))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("stopped after 10 redirects");
    }

    /**
     * 跨域重定向必须剥掉凭据头（对照 Go 的 {@code stripRedirectSensitiveHeaders}）：
     * 两个不同端口的 stub 就是两个不同的 authority → {@code sameHTTPOrigin} 为 false。
     */
    @Test
    void crossOriginRedirectStripsCredentials() {
        // 同源：凭据保留
        ConnectorHttp.Response sameOrigin = client()
                .get(otherBase + "/from-same-origin", Map.of("Authorization", "Bearer tok"));
        assertThat(sameOrigin.bodyAsString()).isEqualTo("auth=Bearer tok");

        // 跨域（跳回主 stub 的另一个端口）：凭据被剥掉
        ConnectorHttp.Response crossOrigin = client()
                .get(otherBase + "/from-cross-origin", Map.of("Authorization", "Bearer tok"));
        assertThat(crossOrigin.bodyAsString()).isEqualTo("auth=<none>");
    }

    // ── SSRF 拦截（对照 SSRFSafeRoundTripper） ────────────────────────────

    /**
     * 未放行 loopback 时，连 stub 都不该连上——校验发生在**连接之前**，
     * 错误文本与 Go 的 {@code outbound request blocked by SSRF policy: %w} 同形。
     */
    @Test
    void blocksNonWhitelistedLoopbackBeforeConnecting() {
        ConnectorHttp.ssrfGuard().reloadWhitelist("example.com");
        try {
            assertThatThrownBy(() -> client().get(base + "/ok", null))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageStartingWith("outbound request blocked by SSRF policy: ")
                    .hasMessageContaining("SSRF validation failed");
        } finally {
            ConnectorHttp.ssrfGuard().reloadWhitelist("127.0.0.1,::1,localhost");
        }
    }

    /** 传输层失败（连不上）也走 {@link ConnectorException}（对照 Go 的 {@code client.Do} 返回 err）。 */
    @Test
    void transportFailureThrowsConnectorException() {
        // 端口 1 上不会有服务；host 已放行，失败发生在连接阶段
        assertThatThrownBy(() -> client().get("http://127.0.0.1:1/x", null))
                .isInstanceOf(ConnectorException.class)
                .hasMessageStartingWith("execute request: ");
    }

    @Test
    void statusTextFallsBackToEmptyForUnknownCodes() {
        assertThat(ConnectorHttp.statusText(200)).isEqualTo("OK");
        assertThat(ConnectorHttp.statusText(429)).isEqualTo("Too Many Requests");
        assertThat(ConnectorHttp.statusText(599)).isEmpty();
    }
}
