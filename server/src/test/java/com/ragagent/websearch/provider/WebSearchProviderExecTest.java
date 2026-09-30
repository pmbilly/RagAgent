package com.ragagent.websearch.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.retrieval.domain.WebSearchFilters;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * web_search 执行面的 stub server A/B：请求体/URL 与 Go 实录（wire/ws_*.json）
 * 逐字节比对 + Go 各 provider 测试的确定性分支移植（日期表/限额/错误文案）。
 */
class WebSearchProviderExecTest {

    @BeforeAll
    static void whitelistOn() {
        new SsrfGuard().reloadWhitelist("127.0.0.1,localhost");
    }

    @AfterAll
    static void whitelistOff() {
        new SsrfGuard().reloadWhitelist("");
    }

    // ── stub ─────────────────────────────────────────────────────────

    record Captured(String method, String path, String query,
                    com.sun.net.httpserver.Headers headers, String body) {
    }

    static final class Stub {
        final HttpServer server;
        final List<Captured> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> responses = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger counter =
                new java.util.concurrent.atomic.AtomicInteger();
        private int status = 200;

        Stub(String... responses) {
            this(200, responses);
        }

        Stub(int status, String... responses) {
            this.status = status;
            this.responses.addAll(List.of(responses));
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void close() {
            server.stop(0);
        }

        private void handle(HttpExchange ex) {
            int idx = Math.min(counter.getAndIncrement(), responses.size() - 1);
            String resp = responses.get(idx);
            try (InputStream in = ex.getRequestBody()) {
                requests.add(new Captured(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                        ex.getRequestURI().getRawQuery(), ex.getRequestHeaders(),
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                byte[] out = resp.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (var os = ex.getResponseBody()) {
                        os.write(out);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static JsonNode wire(String name) {
        try (InputStream in = WebSearchProviderExecTest.class
                .getResourceAsStream("/wire/" + name + ".json")) {
            assertNotNull(in, "wire recording missing: " + name);
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String wireBody(String name) {
        return wire(name).path("body").asText();
    }

    static String wireUrl(String name) {
        return wire(name).path("url").asText();
    }

    static WebSearchProviderParams params(String apiKey) {
        WebSearchProviderParams p = new WebSearchProviderParams();
        p.setApiKey(apiKey);
        return p;
    }

    // ── Bocha（对照 bocha.go / bocha_test.go）────────────────────────

    @Test
    void bochaSearchBodyAndResultMapping() {
        Stub stub = new Stub("{\"code\":200,\"log_id\":\"x\",\"msg\":null,\"data\":{\"webPages\":{\"value\":["
                + "{\"name\":\"First\",\"url\":\"https://example.com/1\",\"summary\":\"Summary\",\"snippet\":\"Snippet\",\"dateLastCrawled\":\"2026-08-09T08:18:30Z\"},"
                + "{\"name\":\"Second\",\"url\":\"https://example.com/2\",\"snippet\":\"Fallback snippet\",\"dateLastCrawled\":\"invalid\"},"
                + "{\"name\":\"Third\",\"url\":\"https://example.com/3\",\"snippet\":\"must be capped\"}"
                + "]}}}");
        try {
            WebSearchProviderParams ps = params("sk-test");
            ps.setExtraConfig(Map.of("freshness", "oneWeek"));
            BochaProvider bocha = new BochaProvider(ps);
            bocha.baseUrl = stub.url();
            List<WebSearchResult> results = bocha.search(" WeKnora ", 2, true);
            assertEquals(wireBody("ws_bocha"), stub.requests.get(0).body());
            assertEquals(2, results.size());
            assertEquals("Summary", results.get(0).getSnippet());
            assertEquals("Fallback snippet", results.get(1).getSnippet());
            assertEquals("bocha", results.get(0).getSource());
            assertNotNull(results.get(0).getPublishedAt());
            // dateLastCrawled 的 +08:00 修正：08:18:30Z → 00:18:30Z（UTC 后）
            assertEquals("2026-08-09T00:18:30Z",
                    results.get(0).getPublishedAt().toInstant().toString());
            assertNull(results.get(1).getPublishedAt(), "非法日期忽略");
        } finally {
            stub.close();
        }
    }

    @Test
    void bochaDateTable() {
        record Case(String name, String published, String lastCrawled, boolean include, String want) {
        }
        List<Case> cases = List.of(
                new Case("legacy fallback", "", "2026-08-09T02:18:30Z", true, "2026-08-08T18:18:30Z"),
                new Case("invalid published fallback", "invalid", " 2026-08-09T02:18:30.123Z ", true,
                        "2026-08-08T18:18:30.123Z"),
                new Case("published UTC preferred", "2026-08-09T02:18:30Z", "2026-08-09T02:18:30Z", true,
                        "2026-08-09T02:18:30Z"),
                new Case("published explicit offset", "2026-08-09T02:18:30+08:00", "invalid", true,
                        "2026-08-08T18:18:30Z"),
                new Case("fallback explicit offset", "", "2026-08-09T02:18:30+08:00", true,
                        "2026-08-08T18:18:30Z"),
                new Case("invalid dates", "invalid", "invalid", true, ""),
                new Case("date excluded", "2026-08-09T02:18:30+08:00", "2026-08-09T02:18:30Z", false, ""));
        for (Case tc : cases) {
            OffsetDateTime got = null;
            if (tc.include()) {
                OffsetDateTime published = BochaProvider.parseFlexibleDate(tc.published(), false);
                got = published != null ? published
                        : BochaProvider.parseFlexibleDate(tc.lastCrawled(), true);
            }
            if (tc.want().isEmpty()) {
                assertNull(got, tc.name());
            } else {
                assertNotNull(got, tc.name());
                assertEquals(tc.want(), got.toInstant().toString(), tc.name());
            }
        }
    }

    @Test
    void bochaValidationAndErrors() {
        RuntimeException noKey = assertThrows(RuntimeException.class,
                () -> BochaProvider.validateParameters(params("  ")));
        assertTrue(noKey.getMessage().contains("API key is required for Bocha provider"));

        WebSearchProviderParams badFreshness = params("sk-test");
        badFreshness.setExtraConfig(Map.of("freshness", "oneHour"));
        RuntimeException badFresh = assertThrows(RuntimeException.class,
                () -> BochaProvider.validateParameters(badFreshness));
        assertTrue(badFresh.getMessage().contains("invalid Bocha freshness: oneHour"));

        assertDoesNotThrow(() -> BochaProvider.validateParameters(params("sk-test")));

        // 实录错误体（2026-09-05 采集）：message 字段 + 字符串 code
        Stub unauthorized = new Stub(401,
                "{\"log_id\":\"4a995aed60e4088e\",\"message\":\"Invalid API KEY\",\"code\":\"401\"}");
        try {
            BochaProvider bocha = new BochaProvider(params("bad"));
            bocha.baseUrl = unauthorized.url();
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> bocha.search("test", 1, false));
            assertTrue(err.getMessage().contains("Invalid API KEY"), err.getMessage());
        } finally {
            unauthorized.close();
        }
        // HTTP 200 但 code 字符串 "429"
        Stub apiError = new Stub("{\"code\":\"429\",\"message\":\"rate limited\"}");
        try {
            BochaProvider bocha = new BochaProvider(params("sk-test"));
            bocha.baseUrl = apiError.url();
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> bocha.search("test", 1, false));
            assertTrue(err.getMessage().contains("429"), err.getMessage());
        } finally {
            apiError.close();
        }
    }

    // ── Brave（对照 brave_test.go）───────────────────────────────────

    @Test
    void braveSearchUrlMatchesRecordingAndMapsAges() {
        Stub stub = new Stub("{\"web\":{\"results\":["
                + "{\"title\":\"One\",\"url\":\"https://example.com/one\",\"description\":\"Snippet\",\"age\":\"2 days ago\"},"
                + "{\"title\":\"Two\",\"url\":\"https://example.com/two\",\"page_age\":\"2026-09-01\"},"
                + "{\"url\":\"https://example.com/extra\"}]}}");
        try {
            BraveProvider p = new BraveProvider(params("test-subscription"));
            p.baseUrl = stub.url();
            List<WebSearchResult> results = p.searchWithFilters("rust & go", 2, false,
                    new WebSearchFilters("de", "pw"));
            // Go 的 url.Values.Encode() 按键字母序（count/country/freshness/q）
            assertEquals("count=2&country=DE&freshness=pw&q=rust+%26+go",
                    stub.requests.get(0).query());
            assertEquals("test-subscription",
                    stub.requests.get(0).headers().getFirst("X-Subscription-Token"));
            assertEquals(2, results.size());
            assertEquals("Snippet", results.get(0).getSnippet());
            assertEquals("2 days ago", results.get(0).getAge());
            assertEquals("2026-09-01", results.get(1).getAge());
            assertNull(results.get(0).getPublishedAt(), "相对 age 不臆造发布时间");
        } finally {
            stub.close();
        }
    }

    @Test
    void braveDefaultsLimitsAndErrors() {
        for (String[] tc : new String[][] {{"0", "5"}, {"99", "20"}}) {
            int requested = Integer.parseInt(tc[0]);
            Stub stub = new Stub(429, "secret upstream diagnostics");
            try {
                BraveProvider p = new BraveProvider(params("k"));
                p.baseUrl = stub.url();
                RuntimeException err = assertThrows(RuntimeException.class,
                        () -> p.search("query", requested, false));
                assertTrue(err.getMessage().contains("HTTP 429"), err.getMessage());
                assertFalse(err.getMessage().contains("secret"), "429 不泄露上游诊断");
                assertTrue(stub.requests.get(0).query().contains("count=" + tc[1]));
                assertFalse(stub.requests.get(0).query().contains("country"));
                assertFalse(stub.requests.get(0).query().contains("freshness"));
            } finally {
                stub.close();
            }
        }
        RuntimeException noKey = assertThrows(RuntimeException.class,
                () -> new BraveProvider(params("")));
        assertTrue(noKey.getMessage().contains("API key"));

        // country=ALL 照发；缺省不发
        Stub stub = new Stub("{\"web\":{\"results\":[]}}");
        try {
            BraveProvider p = new BraveProvider(params("k"));
            p.baseUrl = stub.url();
            assertTrue(p.search("query", 1, false).isEmpty());
            assertFalse(stub.requests.get(0).query().contains("country"));
            p.searchWithFilters("query", 1, false, new WebSearchFilters("ALL", ""));
            assertTrue(stub.requests.get(1).query().contains("country=ALL"));
        } finally {
            stub.close();
        }
    }

    // ── Google（SDK 线格式）─────────────────────────────────────────

    @Test
    void googleRequestShapeAndMapping() {
        Stub stub = new Stub("{\"items\":[{\"title\":\"T\",\"link\":\"https://e/1\",\"snippet\":\"S\"}]}");
        try {
            WebSearchProviderParams p = params("g-key");
            p.setEngineId("engine-1");
            GoogleProvider g = new GoogleProvider(p);
            g.baseUrl = stub.url();
            List<WebSearchResult> results = g.search("hello", 3, false);
            String query = stub.requests.get(0).query();
            assertTrue(query.startsWith("alt=json&cx=engine-1&hl=ch-zh&key=g-key&num=3"
                    + "&prettyPrint=false&q=hello"), query);
            assertEquals(1, results.size());
            assertEquals("T", results.get(0).getTitle());
            assertEquals("google", results.get(0).getSource());
        } finally {
            stub.close();
        }
    }

    // ── Tavily / Ollama / Baidu ─────────────────────────────────────

    @Test
    void tavilyBodyAndMapping() {
        Stub stub = new Stub("{\"results\":[{\"title\":\"T\",\"url\":\"https://e/1\","
                + "\"content\":\"C\",\"score\":0.9,\"published_date\":\"2026-05-01T00:00:00Z\"}]}");
        try {
            TavilyProvider p = new TavilyProvider(params("tv-test"));
            p.baseUrl = stub.url();
            List<WebSearchResult> results = p.search("query", 3, true);
            assertEquals(wireBody("ws_tavily"), stub.requests.get(0).body());
            assertEquals("C", results.get(0).getSnippet());
            assertNotNull(results.get(0).getPublishedAt());
        } finally {
            stub.close();
        }
    }

    @Test
    void ollamaBodyAndMapping() {
        Stub stub = new Stub("{\"results\":[{\"title\":\"T\",\"url\":\"https://e/1\","
                + "\"content\":\"full\",\"snippet\":\"short\"}]}");
        try {
            OllamaProvider p = new OllamaProvider(params("ol-test"));
            p.baseUrl = stub.url();
            List<WebSearchResult> results = p.search("query", 3, false);
            assertEquals(wireBody("ws_ollama"), stub.requests.get(0).body());
            assertEquals("short", results.get(0).getSnippet());
            assertEquals("full", results.get(0).getContent());
        } finally {
            stub.close();
        }
    }

    @Test
    void baiduBodyWidthNormalizationAndDate() {
        Stub stub = new Stub("{\"references\":[{\"title\":\"T\",\"url\":\"https://e/1\","
                + "\"content\":\"C\",\"date\":\"2025-4-24 18:02\"}],\"code\":0}");
        try {
            BaiduProvider p = new BaiduProvider(params("bd-test"));
            p.baseUrl = stub.url();
            List<WebSearchResult> results = p.search("长查询中文内容用于测试宽度模型截断逻辑超长部分", 3, true);
            assertEquals(wireBody("ws_baidu"), stub.requests.get(0).body());
            assertNotNull(results.get(0).getPublishedAt());
            assertEquals("2025-04-24T18:02:00Z",
                    results.get(0).getPublishedAt().toInstant().toString());
        } finally {
            stub.close();
        }
    }

    @Test
    void baiduQueryUnitsAndTruncation() {
        // CJK 按 2 单位计：37 个"智" = 74 单位 > 72 → 截到 36 字（72 单位）
        String longQuery = "智".repeat(37);
        String normalized = BaiduProvider.normalizeBaiduQuery(longQuery);
        assertEquals(36, normalized.codePointCount(0, normalized.length()));
        assertEquals("hello", BaiduProvider.normalizeBaiduQuery("  hello  "));
        assertEquals("", BaiduProvider.normalizeBaiduQuery("   "));
        assertEquals(2, BaiduProvider.baiduQueryUnitWidth('智'));
        assertEquals(1, BaiduProvider.baiduQueryUnitWidth('a'));
    }

    @Test
    void baiduApiLevelErrorWith200() {
        Stub stub = new Stub("{\"code\":1001,\"message\":\"quota exceeded\",\"references\":[]}");
        try {
            BaiduProvider p = new BaiduProvider(params("bd-test"));
            p.baseUrl = stub.url();
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> p.search("q", 1, false));
            assertTrue(err.getMessage().contains("baidu API error (code 1001): quota exceeded"),
                    err.getMessage());
        } finally {
            stub.close();
        }
    }

    // ── Keenable（对照 keenable_test.go）─────────────────────────────

    @Test
    void keenableKeylessAndKeyedBodies() {
        Stub keyless = new Stub("{\"query\":\"hello\",\"results\":["
                + "{\"title\":\"T1\",\"url\":\"https://e/1\",\"description\":\"d1\",\"published_at\":\"2026-05-01T00:00:00Z\"},"
                + "{\"title\":\"T2\",\"url\":\"https://e/2\",\"description\":\"d2\"},"
                + "{\"title\":\"T3\",\"url\":\"https://e/3\",\"description\":\"d3\"}]}");
        try {
            KeenableProvider p = new KeenableProvider(params(""));
            p.baseUrl = keyless.url();
            List<WebSearchResult> results = p.search("hello", 2, true);
            assertEquals(wireBody("ws_keenable_keyless"), keyless.requests.get(0).body());
            assertEquals("/v1/search/public", keyless.requests.get(0).path());
            assertNull(keyless.requests.get(0).headers().getFirst("X-API-Key"));
            assertEquals("WeKnora", keyless.requests.get(0).headers().getFirst("X-Keenable-Title"));
            assertEquals(2, results.size(), "maxResults 截断");
            assertEquals("keenable", results.get(0).getSource());
            assertNotNull(results.get(0).getPublishedAt());
        } finally {
            keyless.close();
        }

        Stub keyed = new Stub("{\"results\":[]}");
        try {
            KeenableProvider p = new KeenableProvider(params("keen_test"));
            p.baseUrl = keyed.url();
            p.search("hi", 2, false);
            assertEquals(wireBody("ws_keenable_keyed"), keyed.requests.get(0).body());
            assertEquals("/v1/search", keyed.requests.get(0).path());
            assertEquals("keen_test", keyed.requests.get(0).headers().getFirst("X-API-Key"));
        } finally {
            keyed.close();
        }
    }

    @Test
    void keenableSnippetFallback() {
        Stub stub = new Stub("{\"results\":["
                + "{\"title\":\"T1\",\"url\":\"https://e/1\",\"description\":\"short\",\"snippet\":\"long excerpt\"},"
                + "{\"title\":\"T2\",\"url\":\"https://e/2\",\"snippet\":\"only long\"}]}");
        try {
            KeenableProvider p = new KeenableProvider(params(""));
            p.baseUrl = stub.url();
            List<WebSearchResult> results = p.search("q", 5, false);
            assertEquals(2, results.size());
            assertEquals("short", results.get(0).getSnippet());
            assertEquals("long excerpt", results.get(0).getContent());
            assertEquals("only long", results.get(1).getSnippet(), "description 缺失回落 snippet");
        } finally {
            stub.close();
        }
    }

    // ── Metaso（对照 metaso_test.go）─────────────────────────────────

    @Test
    void metasoBodyMappingAndValidation() {
        Stub stub = new Stub("{\"webpages\":["
                + "{\"title\":\"First\",\"link\":\"https://example.com/1\",\"summary\":\"Summary\",\"snippet\":\"Snippet\",\"date\":\"2026-08-09\"},"
                + "{\"title\":\"Second\",\"link\":\"https://example.com/2\",\"snippet\":\"Fallback snippet\",\"date\":\"invalid\"},"
                + "{\"title\":\"Third\",\"link\":\"https://example.com/3\",\"snippet\":\"must be capped\"}]}");
        try {
            MetasoProvider p = new MetasoProvider(params("mk-test"));
            p.scope = "scholar";
            p.baseUrl = stub.url();
            List<WebSearchResult> results = p.search(" WeKnora ", 2, true);
            assertEquals(wireBody("ws_metaso"), stub.requests.get(0).body());
            assertEquals(2, results.size());
            assertEquals("Summary", results.get(0).getSnippet());
            assertEquals("Fallback snippet", results.get(1).getSnippet());
            assertEquals("2026-08-09T00:00:00Z", results.get(0).getPublishedAt().toInstant().toString());
            assertNull(results.get(1).getPublishedAt());
        } finally {
            stub.close();
        }

        RuntimeException noKey = assertThrows(RuntimeException.class,
                () -> MetasoProvider.validateParameters(params("")));
        assertTrue(noKey.getMessage().contains("API key is required for Metaso provider"));
        WebSearchProviderParams badScope = params("mk-test");
        badScope.setExtraConfig(Map.of("scope", "unknown"));
        RuntimeException bad = assertThrows(RuntimeException.class,
                () -> MetasoProvider.validateParameters(badScope));
        assertTrue(bad.getMessage().contains("invalid Metaso search scope: unknown"));

        // HTTP 401 带 message 的错误形态
        Stub err = new Stub(401, "{\"message\":\"invalid API key\"}");
        try {
            MetasoProvider p = new MetasoProvider(params("bad"));
            p.baseUrl = err.url();
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> p.search("test", 1, false));
            assertTrue(e.getMessage().contains("Metaso API returned status 401: invalid API key"),
                    e.getMessage());
        } finally {
            err.close();
        }
    }

    // ── Zhipu（对照 zhipu_test.go）───────────────────────────────────

    @Test
    void zhipuBodyTruncationAndMapping() {
        Stub stub = new Stub("{\"id\":\"search-id\",\"request_id\":\"request-id\","
                + "\"search_result\":["
                + "{\"title\":\"Result 1\",\"link\":\"https://example.com/1\",\"content\":\"Summary 1\",\"publish_date\":\"2026-07-16\"},"
                + "{\"title\":\"Result 2\",\"link\":\"https://example.com/2\",\"content\":\"Summary 2\"}]}");
        try {
            ZhipuProvider p = new ZhipuProvider(params("test-key"));
            p.searchEngine = "search_pro";
            p.contentSize = "high";
            p.baseUrl = stub.url();
            String query = "智".repeat(ZhipuProvider.MAX_QUERY_RUNES + 1);
            List<WebSearchResult> results = p.search(query, ZhipuProvider.MAX_RESULTS + 1, true);
            assertEquals(wireBody("ws_zhipu"), stub.requests.get(0).body());
            assertEquals(2, results.size());
            assertEquals("Summary 1", results.get(0).getSnippet());
            assertEquals("", results.get(0).getContent());
            assertEquals("2026-07-16", results.get(0).getPublishedAt().toInstant().toString()
                    .substring(0, 10));
        } finally {
            stub.close();
        }
    }

    @Test
    void zhipuValidationTableAndDateParses() {
        RuntimeException noKey = assertThrows(RuntimeException.class,
                () -> ZhipuProvider.validateParameters(params("  ")));
        assertTrue(noKey.getMessage().contains("API key is required for Zhipu provider"));

        WebSearchProviderParams badEngine = params("key");
        badEngine.setExtraConfig(Map.of("search_engine", "unknown"));
        RuntimeException engine = assertThrows(RuntimeException.class,
                () -> ZhipuProvider.validateParameters(badEngine));
        assertTrue(engine.getMessage().contains("invalid Zhipu search engine: unknown"));

        WebSearchProviderParams badSize = params("key");
        badSize.setExtraConfig(Map.of("content_size", "large"));
        RuntimeException size = assertThrows(RuntimeException.class,
                () -> ZhipuProvider.validateParameters(badSize));
        assertTrue(size.getMessage().contains("invalid Zhipu content size: large"));

        String[] okDates = {"2026-07-16", "2026-07-16 12:30", "2026-07-16 12:30:45",
                "2026-07-16T12:30:45Z"};
        for (String d : okDates) {
            assertNotNull(ZhipuProvider.parseZhipuDate(d), d);
        }
        assertNull(ZhipuProvider.parseZhipuDate("not-a-date"));
    }

    @Test
    void zhipuHttpErrorAndApiError() {
        Stub rateLimited = new Stub(429, "{\"error\":{\"code\":\"1302\",\"message\":\"rate limited\"}}");
        try {
            ZhipuProvider p = new ZhipuProvider(params("test-key"));
            p.baseUrl = rateLimited.url();
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> p.search("test", 1, false));
            assertTrue(err.getMessage().contains("rate limited"), err.getMessage());
            assertTrue(err.getMessage().contains("Zhipu API returned status 429 (1302)"));
        } finally {
            rateLimited.close();
        }
        Stub apiError = new Stub("{\"error\":{\"code\":\"999\",\"message\":\"boom\"}}");
        try {
            ZhipuProvider p = new ZhipuProvider(params("test-key"));
            p.baseUrl = apiError.url();
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> p.search("test", 1, false));
            assertTrue(err.getMessage().contains("Zhipu API error (999): boom"), err.getMessage());
        } finally {
            apiError.close();
        }
    }

    // ── Exa（对照 exa_test.go）───────────────────────────────────────

    @Test
    void exaBodyMappingDatesAndHighlights() {
        Stub stub = new Stub("{\"results\":["
                + "{\"title\":\"One\",\"url\":\"https://example.com/1\",\"highlights\":[\"first\",\"second\"],"
                + "\"text\":\"body\",\"publishedDate\":\"2026-05-01T00:00:00Z\"},"
                + "{\"title\":\"Two\",\"url\":\"https://example.com/2\"},"
                + "{\"title\":\"Three\",\"url\":\"https://example.com/3\"}]}");
        try {
            ExaProvider p = new ExaProvider(params("exa-test"));
            p.includeText = true;
            p.baseUrl = stub.url();
            List<WebSearchResult> results = p.search("hello", 2, true);
            assertEquals(wireBody("ws_exa"), stub.requests.get(0).body());
            assertEquals(2, results.size());
            assertEquals("first\nsecond", results.get(0).getSnippet());
            assertEquals("body", results.get(0).getContent());
            assertEquals("exa", results.get(0).getSource());
            assertEquals("2026-05-01T00:00:00Z", results.get(0).getPublishedAt().toInstant().toString());
            assertEquals("", results.get(1).getSnippet(), "无 highlights 时回落 content（也空）");
        } finally {
            stub.close();
        }
    }

    @Test
    void exaValidationStatusAndEmptyQuery() {
        RuntimeException noKey = assertThrows(RuntimeException.class,
                () -> new ExaProvider(params("")));
        assertTrue(noKey.getMessage().contains("API key is required for Exa provider"));

        Stub rateLimited = new Stub(429, "{\"error\":\"rate limited\"}");
        try {
            ExaProvider p = new ExaProvider(params("key"));
            p.baseUrl = rateLimited.url();
            RuntimeException err = assertThrows(RuntimeException.class,
                    () -> p.search("q", 1, false));
            assertTrue(err.getMessage().contains("exa API returned status 429"), err.getMessage());
            RuntimeException emptyQuery = assertThrows(RuntimeException.class,
                    () -> p.search("  ", 1, false));
            assertTrue(emptyQuery.getMessage().contains("query is empty"));
        } finally {
            rateLimited.close();
        }
    }

    // ── SearXNG（对照 searxng_test.go）───────────────────────────────

    @Test
    void searxngSearchAndUnresponsiveDiagnostics() {
        Stub stub = new Stub("{\"results\":["
                + "{\"title\":\"T1\",\"url\":\"https://e/1\",\"content\":\"c1\",\"publishedDate\":\"2024-05-01\"},"
                + "{\"title\":\"\",\"url\":\"https://e/skip\"},"
                + "{\"title\":\"T2\",\"url\":\"https://e/2\",\"content\":\"c2\"}]}");
        try {
            WebSearchProviderParams p = params("");
            p.setBaseUrl(stub.url());
            SearxngProvider provider = new SearxngProvider(p);
            provider.baseUrl = stub.url();
            List<WebSearchResult> results = provider.search("hello", 5, true);
            assertEquals(wireBody("ws_searxng"), stub.requests.get(0).body());
            // URL 字母序：format 先于 language 先于 q（对照 Go 的 url.Values.Encode）
            String url = wireUrl("ws_searxng");
            assertEquals(url.substring(url.indexOf('?')),
                    "?" + stub.requests.get(0).query());
            assertEquals(2, results.size());
            assertNotNull(results.get(0).getPublishedAt());
            assertEquals("searxng", results.get(0).getSource());
        } finally {
            stub.close();
        }

        // 空结果 + unresponsive_engines → 诊断文案
        Stub empty = new Stub("{\"results\":[],\"unresponsive_engines\":[[\"google\",\"timeout\"]]}");
        try {
            WebSearchProviderParams p = params("");
            p.setBaseUrl(empty.url());
            SearxngProvider provider = new SearxngProvider(p);
            provider.baseUrl = empty.url();
            assertTrue(provider.search("test", 1, false).isEmpty());
            String diagnostics = provider.emptyResultDiagnostics();
            assertTrue(diagnostics.contains("google (timeout)"), diagnostics);
        } finally {
            empty.close();
        }
    }

    @Test
    void searxngBaseUrlValidationTable() {
        record Case(String name, String url, boolean wantErr) {
        }
        List<Case> cases = List.of(
                new Case("empty", "", true),
                new Case("no scheme", "searxng:8080", true),
                new Case("bad scheme", "ftp://searxng:8080", true),
                new Case("with query", "http://localhost:8080/?x=1", true),
                new Case("with fragment", "http://localhost:8080/#frag", true),
                new Case("whitelisted ok", "http://127.0.0.1:8888", false));
        for (Case tc : cases) {
            boolean failed = false;
            try {
                WebSearchProviderParams p = params("");
                p.setBaseUrl(tc.url());
                new SearxngProvider(p);
            } catch (RuntimeException e) {
                failed = true;
            }
            assertEquals(tc.wantErr(), failed, tc.name());
        }
    }

    @Test
    void searxngDateTable() {
        String[][] cases = {
                {"", "false"}, {"2024-05-01", "true"}, {"2024-05-01T12:30:45", "true"},
                {"2024-05-01T12:30:45Z", "true"}, {"2024-05-01T12:30:45.123456789Z", "true"},
                {"2024-05-01 12:30:45", "true"}, {"Wed, 01 May 2024 12:30:45 GMT", "true"},
                {"not-a-date", "false"}};
        for (String[] tc : cases) {
            assertEquals(tc[1], Boolean.toString(
                    SearxngProvider.parseSearxngDate(tc[0]) != null), tc[0]);
        }
    }

    // ── DuckDuckGo（API 回落路径；HTML 有界解析）────────────────────

    @Test
    void duckDuckGoApiFallbackAndTitleExtraction() {
        Stub api = new Stub("{\"AbstractText\":\"DuckDuckGo\",\"AbstractURL\":\"https://ddg\","
                + "\"Heading\":\"DDG\","
                + "\"RelatedTopics\":[{\"FirstURL\":\"https://e/rel\",\"Text\":\"rel text\\nsecond line\"}],"
                + "\"Results\":[]}");
        try {
            DuckDuckGoProvider p = new DuckDuckGoProvider();
            p.htmlUrl = "http://127.0.0.1:" + api.port() + "/html"; // HTML 指向空响应 → 回落 API
            p.apiUrl = "http://127.0.0.1:" + api.port() + "/";
            List<WebSearchResult> results = p.search("query", 5, false);
            assertEquals(2, results.size());
            assertEquals("DDG", results.get(0).getTitle());
            assertEquals("rel text", results.get(1).getTitle(), "标题取首行并截断");
            assertTrue(results.get(1).getSnippet().contains("second line"));
        } finally {
            api.close();
        }
    }

    @Test
    void duckDuckGoCleanUrlVariants() {
        assertEquals("https://e/real", DuckDuckGoProvider.cleanDdgUrl(
                "//duckduckgo.com/l/?uddg=https%3A%2F%2Fe%2Freal&rut=abc"));
        assertEquals("https://e/real", DuckDuckGoProvider.cleanDdgUrl(
                "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fe%2Freal"));
        assertEquals("https://e/plain", DuckDuckGoProvider.cleanDdgUrl("https://e/plain"));
    }

    // ── 注册表 / 空结果错误 / 黑名单 / service 纯函数 ───────────────

    @Test
    void registryCreatesAndReportsUnknown() {
        WebSearchProviderRegistry registry = new WebSearchProviderRegistry();
        assertTrue(registry.createProvider("duckduckgo", params("")) instanceof DuckDuckGoProvider);
        RuntimeException unknown = assertThrows(RuntimeException.class,
                () -> registry.createProvider("nope", params("")));
        assertEquals("web search provider type nope not registered", unknown.getMessage());
    }

    @Test
    void emptyTestResultsErrorMessages() {
        SearxngProvider searxngWithDiagnostics = new SearxngProvider(searxngParams("http://127.0.0.1:1"));
        searxngWithDiagnostics.lastUnresponsive = List.of(List.of("google", "timeout"),
                List.of("bing", "blocked"));
        String with = EmptyTestResults.emptyTestResultsError("searxng",
                searxngWithDiagnostics).getMessage();
        assertTrue(with.contains("searxng returned 0 results"), with);
        assertTrue(with.contains("google (timeout)"), with);
        assertTrue(with.contains("bing (blocked)"), with);

        SearxngProvider plain = new SearxngProvider(searxngParams("http://127.0.0.1:1"));
        String without = EmptyTestResults.emptyTestResultsError("searxng", plain).getMessage();
        assertTrue(without.contains(
                "verify the instance URL is reachable and JSON format is enabled in settings.yml"),
                without);

        assertEquals("duckduckgo returned 0 results; verify network connectivity and proxy settings",
                EmptyTestResults.emptyTestResultsError("duckduckgo", null).getMessage());
        assertTrue(EmptyTestResults.emptyTestResultsError("keenable", null).getMessage()
                .contains("keyless requests are rate-limited"));
        assertTrue(EmptyTestResults.emptyTestResultsError("exa", null).getMessage()
                .contains("verify the API key, account quota"));
        assertEquals("search returned 0 results, please verify your API key and configuration",
                EmptyTestResults.emptyTestResultsError("bing", null).getMessage());
    }

    private static WebSearchProviderParams searxngParams(String baseUrl) {
        WebSearchProviderParams p = new WebSearchProviderParams();
        p.setBaseUrl(baseUrl);
        return p;
    }

    @Test
    void blacklistPatternAndRegexRules() {
        WebSearchResult keep = new WebSearchResult();
        keep.setUrl("https://good.example.com/a");
        WebSearchResult dropPattern = new WebSearchResult();
        dropPattern.setUrl("https://www.bad.example.com/x");
        WebSearchResult dropRegex = new WebSearchResult();
        dropRegex.setUrl("https://spam.example.net/y");

        List<String> rules = List.of("*://*.bad.example.com/*", "/example\\.(net|org)/");
        List<WebSearchResult> filtered = com.ragagent.websearch.service.WebSearchService
                .filterBlacklist(List.of(keep, dropPattern, dropRegex), rules);
        assertEquals(1, filtered.size());
        assertEquals("https://good.example.com/a", filtered.get(0).getUrl());
    }

    @Test
    void serviceHelperRoundTripFunctions() {
        // extractSourceURLFromContent / stripMarker / 轮选 / 合并
        String content = "[sourceUrl]: https://e/1\nbody line";
        assertEquals("https://e/1",
                com.ragagent.websearch.service.WebSearchService.extractSourceUrlFromContent(content));
        assertEquals("body line",
                com.ragagent.websearch.service.WebSearchService.stripMarker(content));
        assertEquals("", com.ragagent.websearch.service.WebSearchService
                .extractSourceUrlFromContent("no marker"));

        WebSearchResult r1 = new WebSearchResult();
        r1.setTitle("R1");
        r1.setUrl("https://e/1");
        r1.setSnippet("s1");
        r1.setSource("exa");
        WebSearchResult r2 = new WebSearchResult();
        r2.setTitle("R2");
        r2.setUrl("https://e/2");
        r2.setSnippet("s2");

        SearchResult ref1 = new SearchResult();
        ref1.setContent("[sourceUrl]: https://e/1\nref a");
        SearchResult ref2 = new SearchResult();
        ref2.setContent("[sourceUrl]: https://e/1\nref b");
        SearchResult ref3 = new SearchResult();
        ref3.setContent("[sourceUrl]: https://e/2\nref c");

        var selected = com.ragagent.websearch.service.WebSearchService
                .selectReferencesRoundRobin(List.of(r1, r2), List.of(ref1, ref2, ref3), 2);
        assertEquals(2, selected.size());
        // 轮选：第一轮 e/1 → ref-a、e/2 → ref-c
        assertTrue(selected.get(0).getContent().endsWith("ref a"));
        assertTrue(selected.get(1).getContent().endsWith("ref c"));

        var consolidated = com.ragagent.websearch.service.WebSearchService
                .consolidateReferencesByURL(List.of(r1, r2), List.of(ref1, ref2));
        assertEquals(2, consolidated.size());
        assertEquals("ref a\n---\nref b", consolidated.get(0).getContent());
        assertEquals("s1", consolidated.get(0).getSnippet(), "原始结果的元数据保留");
    }
}
