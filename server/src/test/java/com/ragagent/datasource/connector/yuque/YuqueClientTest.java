package com.ragagent.datasource.connector.yuque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.FlexibleStatus;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2Doc;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2Repo;
import com.ragagent.datasource.connector.yuque.YuqueApiTypes.V2User;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * 语雀客户端的对等测试（逐条对照 Go {@code yuque/client_test.go}）。
 *
 * <p>退避注入 {@link YuqueRetryPolicy#immediate()}，所以重试矩阵（429 / 5xx /
 * 传输失败）都是毫秒级的——Go 那边 {@code TestClient_5xxRetriesOnce} 还得开一个
 * 5 秒超时的 ctx 才不至于等 2 秒。</p>
 */
class YuqueClientTest {

    @BeforeAll
    static void allowLoopback() {
        FakeYuque.allowLoopback();
    }

    @AfterAll
    static void restoreSsrf() {
        FakeYuque.restoreSsrf();
    }

    // ── 编排式 stub ──────────────────────────────────────────────────────

    private record Rs(int status, String body, Map<String, String> headers) {
        static Rs of(int status, String body) {
            return new Rs(status, body, Map.of());
        }

        static Rs of(int status, String body, String header, String value) {
            return new Rs(status, body, Map.of(header, value));
        }
    }

    private static final class Scripted implements AutoCloseable {
        private final HttpServer server;
        private final List<Rs> script;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> queries = new CopyOnWriteArrayList<>();
        private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();

        Scripted(Rs... script) throws IOException {
            this.script = List.of(script);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange ex) throws IOException {
            int n = calls.getAndIncrement();
            paths.add(ex.getRequestURI().getPath());
            queries.add(ex.getRequestURI().getRawQuery() == null
                    ? "" : ex.getRequestURI().getRawQuery());
            Map<String, String> h = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0));
                }
            });
            headers.add(h);
            ex.getRequestBody().readAllBytes();

            Rs r = script.get(Math.min(n, script.size() - 1));
            r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int calls() {
            return calls.get();
        }

        String path(int index) {
            return paths.get(index);
        }

        String query(int index) {
            return queries.get(index);
        }

        Map<String, String> headers(int index) {
            return headers.get(index);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static YuqueClient client(Scripted stub) {
        YuqueConfig cfg = new YuqueConfig();
        cfg.setApiToken("tok-super-secret-value-1234");
        cfg.setBaseUrl(stub.baseUrl());
        return new YuqueClient(cfg, YuqueRetryPolicy.immediate());
    }

    // ── 基本请求形状 ─────────────────────────────────────────────────────

    @Test
    void pingSendsAuthHeader() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(200, "{\"data\":{\"id\":1,\"login\":\"me\"}}"))) {
            client(stub).ping();
            assertThat(stub.path(0)).isEqualTo("/api/v2/user");
            assertThat(stub.headers(0).get("x-auth-token")).isEqualTo("tok-super-secret-value-1234");
            assertThat(stub.headers(0).get("user-agent")).isEqualTo(YuqueClient.USER_AGENT);
        }
    }

    @Test
    void getCurrentUserDecodesData() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            V2User u = new YuqueClient(configFor(f), YuqueRetryPolicy.immediate()).getCurrentUser();
            assertThat(u.getId()).isEqualTo(1L);
            assertThat(u.getLogin()).isEqualTo("me");
        }
    }

    /** 对照 Go {@code TestClient_ListUserRepos_FiltersType}：查询串必须按 key 升序且带 type=Book。 */
    @Test
    void listUserReposSendsBookFilterAndPagination() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            Map<String, Object> repo = repo(9, "book1", "Book", "alice/book1");
            f.handleJson("/api/v2/users/alice/repos", 200, FakeYuque.data(List.of(repo)));

            List<V2Repo> repos = new YuqueClient(configFor(f), YuqueRetryPolicy.immediate())
                    .listUserRepos("alice");

            String query = f.queryOf("/api/v2/users/alice/repos");
            assertThat(query).isEqualTo("limit=100&offset=0&type=Book");
            assertThat(repos).hasSize(1);
            assertThat(repos.get(0).getNamespace()).isEqualTo("alice/book1");
        }
    }

    /** 对照 Go {@code TestClient_ListBookDocs_Pagination}：满页继续、不满页停（2 次调用）。 */
    @Test
    void listBookDocsPaginates() throws Exception {
        StringBuilder page1 = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                page1.append(',');
            }
            page1.append("{\"id\":").append(i + 1).append(",\"type\":\"Doc\",\"status\":\"1\"}");
        }
        page1.append("]}");
        StringBuilder page2 = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 30; i++) {
            if (i > 0) {
                page2.append(',');
            }
            page2.append("{\"id\":").append(i + 101).append(",\"type\":\"Doc\",\"status\":\"1\"}");
        }
        page2.append("]}");

        try (Scripted stub = new Scripted(
                Rs.of(200, page1.toString()), Rs.of(200, page2.toString()))) {
            List<V2Doc> docs = client(stub).listBookDocs(555);

            assertThat(docs).hasSize(130);
            assertThat(stub.calls()).as("one full page + one partial").isEqualTo(2);
            assertThat(stub.query(0)).isEqualTo("limit=100&offset=0");
            assertThat(stub.query(1)).isEqualTo("limit=100&offset=100");
        }
    }

    // ── 重试矩阵 ─────────────────────────────────────────────────────────

    /** 对照 Go {@code TestClient_Ping_401}：401 必须包成 ErrInvalidCredentials 且不重试。 */
    @Test
    void http401WrapsInvalidCredentials() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(401, "{\"message\":\"Unauthorized\"}"))) {
            assertThatThrownBy(() -> client(stub).ping())
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("status=401");
            assertThat(stub.calls()).isEqualTo(1);
        }
    }

    /** 对照 Go {@code TestClient_Ping_403WrapsInvalidCredentials}。 */
    @Test
    void http403WrapsInvalidCredentials() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(403, "{\"message\":\"Forbidden\"}"))) {
            assertThatThrownBy(() -> client(stub).ping())
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessageContaining("status=403");
            assertThat(stub.calls()).isEqualTo(1);
        }
    }

    /** 对照 Go {@code TestClient_429WithRetryAfter_Retries}：{@code Retry-After: 0} → 100ms → 重试成功。 */
    @Test
    void http429WithRetryAfterRetries() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(429, "{\"message\":\"rate limited\"}", "Retry-After", "0"),
                Rs.of(200, "{\"data\":{\"id\":1,\"login\":\"me\"}}"))) {
            client(stub).ping();
            assertThat(stub.calls()).isEqualTo(2);
        }
    }

    /** 对照 Go {@code TestClient_429ExhaustsRetries}：4 次尝试（1 + 3 重试）。 */
    @Test
    void http429ExhaustsRetries() throws Exception {
        try (Scripted stub = new Scripted(
                Rs.of(429, "{\"message\":\"rate limited\"}", "Retry-After", "0"))) {
            assertThatThrownBy(() -> client(stub).ping())
                    .hasMessageContaining("yuque rate limited: status=429");
            assertThat(stub.calls()).isEqualTo(4);
        }
    }

    /** 对照 Go {@code TestClient_5xxRetriesOnce}：5xx 恰好重试 1 次 → 2 次尝试。 */
    @Test
    void http5xxRetriesOnce() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(500, "{\"message\":\"internal error\"}"))) {
            assertThatThrownBy(() -> client(stub).ping())
                    .hasMessageContaining("yuque server error: status=500");
            assertThat(stub.calls()).isEqualTo(2);
        }
    }

    /** 对照 Go {@code TestClient_4xxNotRetried}：非 429/5xx 的 4xx 不重试。 */
    @Test
    void http4xxIsNotRetried() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(400, "{\"message\":\"bad request\"}"))) {
            assertThatThrownBy(() -> client(stub).ping())
                    .hasMessageContaining("yuque api error: status=400 msg=bad request");
            assertThat(stub.calls()).isEqualTo(1);
        }
    }

    /** 非 2xx 且错误体不是 JSON 时回落到响应体预览。 */
    @Test
    void nonJsonErrorBodyFallsBackToPreview() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(418, "teapot"))) {
            assertThatThrownBy(() -> client(stub).ping())
                    .hasMessageContaining("yuque api error: status=418 body=teapot");
        }
    }

    /** 响应不是目标 JSON 形状时是 {@code decode response: ...}（不重试）。 */
    @Test
    void decodeFailureIsAnError() throws Exception {
        try (Scripted stub = new Scripted(Rs.of(200, "[1,2,3]"))) {
            assertThatThrownBy(() -> client(stub).ping())
                    .hasMessageContaining("decode response:");
            assertThat(stub.calls()).isEqualTo(1);
        }
    }

    // ── 令牌不进日志 ─────────────────────────────────────────────────────

    /**
     * 对照 Go {@code TestClient_TokenNeverLoggedInFull}：原始令牌绝不落日志，
     * 只出现脱敏形态。
     */
    @Test
    void tokenIsNeverLoggedInFull() throws Exception {
        Logger logger = logbackLoggerOrSkip(YuqueClient.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        String rawToken = "tok-super-secret-value-1234";
        try (FakeYuque f = new FakeYuque()) {
            new YuqueClient(configFor(f), YuqueRetryPolicy.immediate()).ping();

            String out = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(out).as("expected logger output (sanity check)").isNotEmpty();
            assertThat(out).doesNotContain(rawToken);
            assertThat(out).contains(YuqueClient.redactToken(rawToken));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    /**
     * 取 logback 的具体 logger；没有 logback 绑定时跳过（对照 Go 的
     * "SetOutput wiring may be broken" sanity check）。
     */
    static Logger logbackLoggerOrSkip(Class<?> type) {
        org.slf4j.Logger raw = LoggerFactory.getLogger(type);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                raw instanceof Logger,
                "logback-classic 绑定不可用（当前是 " + raw.getClass().getName() + "），跳过日志捕获断言");
        return (Logger) raw;
    }

    // ── flexibleStatus ───────────────────────────────────────────────────

    /** 对照 Go {@code TestV2Doc_Status_AcceptsNumberAndString}。 */
    @ParameterizedTest
    @CsvSource({
            "'{\"id\":1,\"status\":1}',      1",
            "'{\"id\":1,\"status\":0}',      0",
            "'{\"id\":1,\"status\":\"1\"}',  1",
            "'{\"id\":1,\"status\":null}',   ''",
            "'{\"id\":1}',                   ''",
            "'{\"id\":1,\"status\":-3}',     -3",
            "'{\"id\":1,\"status\":\"\"}',   ''",
            "'{\"id\":1,\"status\":\"0\"}',  0",
    })
    void flexibleStatusAcceptsNumberAndString(String body, String want) throws Exception {
        V2Doc d = new ObjectMapper().readValue(body, V2Doc.class);
        assertThat(d.getStatus().value()).isEqualTo(want == null ? "" : want);
    }

    /**
     * 对照 Go {@code TestV2Doc_Status_RejectsUnexpectedShapes}：
     * 浮点 / 布尔 / 数组 / 对象都<b>大声失败</b>，而不是被静默字符串化
     * ——否则草稿可能被当成已发布文档灌进知识库。
     */
    @ParameterizedTest
    @CsvSource({
            "'{\"id\":1,\"status\":true}'",
            "'{\"id\":1,\"status\":1.5}'",
            "'{\"id\":1,\"status\":[1]}'",
            "'{\"id\":1,\"status\":{\"x\":1}}'",
    })
    void flexibleStatusRejectsUnexpectedShapes(String body) {
        assertThatThrownBy(() -> new ObjectMapper().readValue(body, V2Doc.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("flexibleStatus: expected string or integer");
    }

    /** 超长整数（超出 int64）也必须失败——Go 解到 int64 会报 out of range。 */
    @Test
    void flexibleStatusRejectsOutOfRangeInteger() {
        assertThatThrownBy(() -> new ObjectMapper()
                .readValue("{\"id\":1,\"status\":99999999999999999999}", V2Doc.class))
                .hasMessageContaining("flexibleStatus: expected string or integer");
    }

    /** 端到端：列表响应里 {@code status} 是数字时照样能解码（Go 的真实故障复现）。 */
    @Test
    void numericStatusDecodesEndToEnd() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(101, "Doc", 1, "hello", "hello", "2026-04-20T10:00:00Z"))));

            List<V2Doc> docs = new YuqueClient(configFor(f), YuqueRetryPolicy.immediate())
                    .listBookDocs(10);
            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).getStatus().value()).isEqualTo("1");
        }
    }

    /** 端到端：形状意外时 {@code doRequest} 报 {@code decode response: ...}。 */
    @Test
    void unexpectedStatusShapeFailsTheRequest() throws Exception {
        try (FakeYuque f = new FakeYuque()) {
            f.handleJson("/api/v2/repos/10/docs", 200, FakeYuque.docList(List.of(
                    FakeYuque.doc(101, "Doc", true, "hello", "hello", "2026-04-20T10:00:00Z"))));

            assertThatThrownBy(() -> new YuqueClient(configFor(f), YuqueRetryPolicy.immediate())
                    .listBookDocs(10))
                    .hasMessageContaining("decode response:")
                    .hasMessageContaining("flexibleStatus: expected string or integer");
        }
    }

    // ── parseRetryAfter / GoDuration（Go 实录） ──────────────────────────

    /** 对照 Go {@code TestParseRetryAfter}。 */
    @ParameterizedTest
    @CsvSource({
            "'',   5000",
            "0,    100",
            "-1,   100",
            "3,    3000",
            "abc,  5000",
    })
    void parseRetryAfterMatchesGo(String header, long wantMillis) {
        assertThat(YuqueClient.parseRetryAfter(header, Duration.ofSeconds(5)))
                .isEqualTo(Duration.ofMillis(wantMillis));
    }

    /** 额外的 Go 实录语料：证明"什么算合法"与 Go 的 {@code ParseDuration} 一致。 */
    @ParameterizedTest
    @CsvSource({
            "90m,  90",     // "90ms" 合法 → 90ms
            "0.5,  500",    // "0.5s" → 500ms
            "1.,   1000",   // "1.s" → 1s
            ".5,   500",
            "+2,   2000",
            "1s,   5000",   // "1ss" 未知单位 → 回落
            "'3 ', 5000",   // "3 s" 未知单位 → 回落
            "1e2,  5000",   // "1e2s" 未知单位 → 回落
            "-0,   100",
    })
    void parseRetryAfterFollowsGoDurationGrammar(String header, long wantMillis) {
        assertThat(YuqueClient.parseRetryAfter(header, Duration.ofSeconds(5)))
                .isEqualTo(Duration.ofMillis(wantMillis));
    }

    /** {@link GoDuration} 的溢出与文法边界（Go 实录：这两种输入 Go 也判 invalid）。 */
    @Test
    void goDurationRejectsOverflowAndBadGrammar() {
        assertThatThrownBy(() -> GoDuration.parse("9223372036854775807s"))
                .as("乘 1e9 越界 → Go 也判 invalid")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GoDuration.parse("999999999999999999999s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GoDuration.parse("s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GoDuration.parse("--1s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GoDuration.parse(" 3s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 小数秒与正号（Go 实录）。 */
    @Test
    void goDurationHandlesFractionsAndSigns() {
        assertThat(GoDuration.parse(".5s")).isEqualTo(Duration.ofMillis(500));
        assertThat(GoDuration.parse("1.s")).isEqualTo(Duration.ofSeconds(1));
        assertThat(GoDuration.parse("+2s")).isEqualTo(Duration.ofSeconds(2));
        assertThat(GoDuration.parse("1.5s")).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void goDurationAcceptsCompoundUnits() {
        assertThat(GoDuration.parse("1h2s")).isEqualTo(Duration.ofSeconds(3602));
        assertThat(GoDuration.parse("90ms")).isEqualTo(Duration.ofMillis(90));
        assertThat(GoDuration.parse("0.0001s")).isEqualTo(Duration.ofNanos(100_000));
        assertThat(GoDuration.parse("0s")).isEqualTo(Duration.ZERO);
        assertThat(GoDuration.parse("-1s")).isEqualTo(Duration.ofSeconds(-1));
    }

    // ── buildQuery ───────────────────────────────────────────────────────

    /** 对照 Go 的 {@code url.Values.Encode()}：key 升序、省略空值、带前导 {@code ?}。 */
    @Test
    void buildQueryMatchesGoUrlEncoding() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "Book");
        params.put("offset", "100");
        params.put("limit", "100");
        assertThat(YuqueClient.buildQuery(params)).isEqualTo("?limit=100&offset=100&type=Book");

        Map<String, String> withEmpty = new LinkedHashMap<>();
        withEmpty.put("offset", "0");
        withEmpty.put("cursor", "");
        withEmpty.put("limit", "20");
        assertThat(YuqueClient.buildQuery(withEmpty)).isEqualTo("?limit=20&offset=0");

        assertThat(YuqueClient.buildQuery(Map.of())).isEmpty();
        assertThat(YuqueClient.buildQuery(null)).isEmpty();
    }

    /** 转义按 Go 的 {@code QueryEscape}：空格转 {@code +}，保留 {@code -_.~}。 */
    @Test
    void buildQueryEscapesLikeGo() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", "a b/c*");
        assertThat(YuqueClient.buildQuery(params)).isEqualTo("?q=a+b%2Fc%2A");
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private static YuqueConfig configFor(FakeYuque f) {
        YuqueConfig cfg = new YuqueConfig();
        cfg.setApiToken("tok-super-secret-value-1234");
        cfg.setBaseUrl(f.baseUrl());
        return cfg;
    }

    private static Map<String, Object> repo(long id, String slug, String type, String namespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("slug", slug);
        out.put("type", type);
        out.put("namespace", namespace);
        out.put("name", slug);
        out.put("public", 1);
        return out;
    }

    /** {@link FlexibleStatus} 的直接构造语义（Go 的 {@code flexibleStatus("1")}）。 */
    @Test
    void flexibleStatusOfNormalizesNull() {
        assertThat(FlexibleStatus.of(null).value()).isEmpty();
        assertThat(FlexibleStatus.of("1").value()).isEqualTo("1");
        assertThat(FlexibleStatus.of("1")).isEqualTo(FlexibleStatus.of("1"));
    }
}
