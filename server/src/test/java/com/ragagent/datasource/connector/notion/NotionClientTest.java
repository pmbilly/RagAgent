package com.ragagent.datasource.connector.notion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.datasource.ConnectorException;

/**
 * {@code NotionClient} 的对等测试：请求矩阵、重试/退避、分页、块递归、
 * 数据库回落、下载与 SSRF。
 *
 * <h2>没有一条用例在等墙钟</h2>
 * <p>Go 的 {@code 1&lt;&lt;attempt} 秒退避与 429 的 {@code Retry-After}
 * 在 Java 侧是可注入的（{@link NotionClient.Backoff} / {@link NotionClient.Sleeper}），
 * 测试注入"记账但不真睡"的 Sleeper，于是：
 * Go 侧那条 500 重试用例实测要 7–8 秒，这里断言的是
 * <b>休眠序列 {@code [1000, 2000, 4000]}</b>（更精确、且零耗时）。</p>
 */
class NotionClientTest {

    @BeforeAll
    static void allowLoopback() {
        NotionTestSupport.allowLoopback();
    }

    @AfterAll
    static void restoreSsrf() {
        NotionTestSupport.restoreSsrf();
    }

    // ── doRequest 状态码矩阵 ──────────────────────────────────────────────

    @Test
    void requestStatusMatrix() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/users/me", 401, "{\"code\":\"unauthorized\"}");
            server.status("/v1/forbidden", 403, "forbidden body");
            server.status("/v1/notfound", 404, "{\"object\":\"error\"}");
            server.status("/v1/weird", 418, "teapot");
            server.status("/v1/ok299", 299, "ok");
            server.json("/v1/ok", "{\"object\":\"ok\"}");

            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());

            assertThatThrownBy(() -> client.doRequest("GET", "/v1/users/me", null))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessage("invalid credentials: {\"code\":\"unauthorized\"}");
            assertThatThrownBy(() -> client.doRequest("GET", "/v1/forbidden", null))
                    .isInstanceOf(ConnectorException.InvalidCredentials.class)
                    .hasMessage("invalid credentials: forbidden body");
            // 404 的细节是**路径**，不是响应体
            assertThatThrownBy(() -> client.doRequest("GET", "/v1/notfound", null))
                    .isInstanceOf(ConnectorException.ResourceNotFound.class)
                    .hasMessage("resource not found in source system: /v1/notfound");
            assertThatThrownBy(() -> client.doRequest("GET", "/v1/weird", null))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("unexpected status 418: teapot");

            assertThat(client.doRequest("GET", "/v1/ok299", null))
                    .isEqualTo("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(new String(client.doRequest("GET", "/v1/ok", null)))
                    .isEqualTo("{\"object\":\"ok\"}");

            // 401/403/404/418 都不重试
            assertThat(server.requestCount()).isEqualTo(6);
        }
    }

    @Test
    void requestHeaders() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/users/me", "{\"object\":\"user\"}");
            NotionTestSupport.fastClient("tok", server.baseUrl(), null)
                    .doRequest("GET", "/v1/users/me", null);
            NotionStubServer.Recorded recorded = server.lastRequest();
            assertThat(recorded.header("Authorization")).isEqualTo("Bearer tok");
            assertThat(recorded.header("Notion-Version")).isEqualTo("2026-03-11");
            assertThat(recorded.header("Content-Type")).isEqualTo("application/json");
        }
    }

    // ── 重试与退避 ────────────────────────────────────────────────────────

    @Test
    void retriesServerErrorsWithExponentialBackoff() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/fail500", 500, "boom");
            NotionTestSupport.RecordingSleeper sleeper = new NotionTestSupport.RecordingSleeper();
            NotionClient client = NotionTestSupport.fastClient("tok", server.baseUrl(), sleeper,
                    NotionClient.Backoff.exponentialSeconds());

            assertThatThrownBy(() -> client.doRequest("GET", "/v1/fail500", null))
                    .isInstanceOf(ConnectorException.FetchFailed.class)
                    .hasMessage("failed to fetch items from source: server error 500: boom");

            // Go 实测：4 次请求（1 + maxRetries），退避 1s / 2s / 4s
            assertThat(server.requestCount()).isEqualTo(4);
            assertThat(sleeper.slept).containsExactly(1000L, 2000L, 4000L);
        }
    }

    @Test
    void retriesQuotaExceededUsingRetryAfter() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            AtomicLong calls = new AtomicLong();
            server.route("/v1/limited", exchange -> {
                if (calls.incrementAndGet() < 3) {
                    exchange.getResponseHeaders().set("Retry-After", "0.25");
                    NotionStubServer.respond(exchange, 429, "slow down");
                    return;
                }
                NotionStubServer.respond(exchange, 200, "{\"object\":\"ok\"}");
            });
            NotionTestSupport.RecordingSleeper sleeper = new NotionTestSupport.RecordingSleeper();
            NotionClient client = NotionTestSupport.fastClient("tok", server.baseUrl(), sleeper);

            byte[] body = client.doRequest("GET", "/v1/limited", null);
            assertThat(new String(body)).isEqualTo("{\"object\":\"ok\"}");
            assertThat(calls.get()).isEqualTo(3);
            // 小数秒被保留：0.25s → 250ms（Go 的 ParseFloat 语义）
            assertThat(sleeper.slept).containsExactly(250L, 250L);
        }
    }

    @Test
    void quotaExceededFallsBackToOneSecondWhenRetryAfterIsUnusable() throws Exception {
        List<String> headers = List.of("not-a-number", "", "0", "-1");
        for (String header : headers) {
            try (NotionStubServer server = new NotionStubServer()) {
                AtomicLong calls = new AtomicLong();
                server.route("/v1/limited", exchange -> {
                    if (calls.incrementAndGet() < 2) {
                        if (!header.isEmpty()) {
                            exchange.getResponseHeaders().set("Retry-After", header);
                        }
                        NotionStubServer.respond(exchange, 429, "x");
                        return;
                    }
                    NotionStubServer.respond(exchange, 200, "{}");
                });
                NotionTestSupport.RecordingSleeper sleeper = new NotionTestSupport.RecordingSleeper();
                NotionClient client = NotionTestSupport.fastClient("tok", server.baseUrl(), sleeper);
                client.doRequest("GET", "/v1/limited", null);
                assertThat(sleeper.slept).as("Retry-After=%s", header).containsExactly(1000L);
            }
        }
    }

    @Test
    void quotaExceededAfterExhaustingRetriesBecomesFetchFailed() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/limited", 429, "always");
            NotionTestSupport.RecordingSleeper sleeper = new NotionTestSupport.RecordingSleeper();
            NotionClient client = NotionTestSupport.fastClient("tok", server.baseUrl(), sleeper);

            assertThatThrownBy(() -> client.doRequest("GET", "/v1/limited", null))
                    .isInstanceOf(ConnectorException.FetchFailed.class)
                    .hasMessage("failed to fetch items from source: rate limited: always");
            assertThat(server.requestCount()).isEqualTo(4);
            assertThat(sleeper.slept).containsExactly(1000L, 1000L, 1000L);
        }
    }

    @Test
    void transportFailureIsRetriedThenWrappedInFetchFailed() throws Exception {
        String baseUrl;
        try (NotionStubServer server = new NotionStubServer()) {
            baseUrl = server.baseUrl();
        } // 关掉 → 连接被拒绝
        NotionTestSupport.RecordingSleeper sleeper = new NotionTestSupport.RecordingSleeper();
        NotionClient client = NotionTestSupport.fastClient("tok", baseUrl, sleeper,
                NotionClient.Backoff.exponentialSeconds());

        assertThatThrownBy(() -> client.doRequest("GET", "/v1/users/me", null))
                .isInstanceOf(ConnectorException.FetchFailed.class)
                .hasMessageStartingWith("failed to fetch items from source: ");
        assertThat(sleeper.slept).containsExactly(1000L, 2000L, 4000L);
    }

    // ── 分页 ─────────────────────────────────────────────────────────────

    @Test
    void paginatePagesPostSendsPageSizeAndCursor() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.route("/v1/search", exchange -> {
                String body = NotionStubServer.body(exchange);
                if (body.contains("start_cursor")) {
                    NotionStubServer.respond(exchange, 200,
                            "{\"object\":\"list\",\"results\":[{\"id\":\"p2\",\"object\":\"page\"}],"
                                    + "\"has_more\":false}");
                    return;
                }
                NotionStubServer.respond(exchange, 200,
                        "{\"object\":\"list\",\"results\":[{\"id\":\"p1\",\"object\":\"page\"}],"
                                + "\"has_more\":true,\"next_cursor\":\"CUR1\"}");
            });
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            List<NotionPage> pages = client.searchPages();
            assertThat(pages).hasSize(2);
            assertThat(server.requestDescribes()).containsExactly(
                    "POST /v1/search body={\"page_size\":100}",
                    "POST /v1/search body={\"page_size\":100,\"start_cursor\":\"CUR1\"}");
        }
    }

    @Test
    void paginatePagesGetUsesQueryString() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.route("/v1/blocks/gp/children", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null && query.contains("start_cursor")) {
                    NotionStubServer.respond(exchange, 200,
                            "{\"object\":\"list\",\"results\":[{\"id\":\"b2\",\"type\":\"paragraph\"}],"
                                    + "\"has_more\":false}");
                    return;
                }
                NotionStubServer.respond(exchange, 200,
                        "{\"object\":\"list\",\"results\":[{\"id\":\"b1\",\"type\":\"paragraph\"}],"
                                + "\"has_more\":true,\"next_cursor\":\"G2\"}");
            });
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            assertThat(client.paginatePages("GET", "/v1/blocks/gp/children")).hasSize(2);
            assertThat(server.requestDescribes()).containsExactly(
                    "GET /v1/blocks/gp/children",
                    "GET /v1/blocks/gp/children?start_cursor=G2&page_size=100");
        }
    }

    @Test
    void paginatePagesWrapsErrorsLikeGo() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/search", 404, "{}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            // Go: 404 是裸哨兵（ResourceNotFound），paginatePages 只在**其它**错误上加前缀
            assertThatThrownBy(client::searchPages)
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("paginate /v1/search: "
                            + "resource not found in source system: /v1/search");
        }
    }

    @Test
    void paginatePagesMissingResultsFieldIsAnError() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/search", "{\"object\":\"list\",\"has_more\":false}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            assertThatThrownBy(client::searchPages)
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("unmarshal page results: unexpected end of JSON input");
        }
    }

    // ── 块递归 ───────────────────────────────────────────────────────────

    @Test
    void blockRecursionStopsAtMaxDepthFive() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.fallback(exchange -> {
                String path = exchange.getRequestURI().getPath();
                int level = Integer.parseInt(
                        path.replace("/v1/blocks/l", "").replace("/children", ""));
                NotionStubServer.respond(exchange, 200,
                        "{\"object\":\"list\",\"results\":[{\"id\":\"l" + (level + 1)
                                + "\",\"type\":\"paragraph\",\"has_children\":true,"
                                + "\"paragraph\":{\"rich_text\":[]}}],\"has_more\":false}");
            });
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            List<NotionBlock> blocks = client.getBlockChildrenAll("l0");

            // Go 实测：l0…l5 共 6 次请求，Children 层数 5
            assertThat(server.requestCount()).isEqualTo(6);
            assertThat(server.requestDescribes()).containsExactly(
                    "GET /v1/blocks/l0/children", "GET /v1/blocks/l1/children",
                    "GET /v1/blocks/l2/children", "GET /v1/blocks/l3/children",
                    "GET /v1/blocks/l4/children", "GET /v1/blocks/l5/children");
            int levels = 0;
            List<NotionBlock> cur = blocks;
            while (cur != null && !cur.isEmpty() && cur.get(0).children != null) {
                levels++;
                cur = cur.get(0).children;
            }
            assertThat(levels).isEqualTo(5);
        }
    }

    @Test
    void blockRecursionSkipsStructuralBlockTypes() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.fallback(exchange -> NotionStubServer.respond(exchange, 200,
                    "{\"object\":\"list\",\"results\":["
                            + "{\"id\":\"cp\",\"type\":\"child_page\",\"has_children\":true,"
                            + "\"child_page\":{\"title\":\"T\"}},"
                            + "{\"id\":\"cd\",\"type\":\"child_database\",\"has_children\":true,"
                            + "\"child_database\":{\"title\":\"D\"}},"
                            + "{\"id\":\"us\",\"type\":\"unsupported\",\"has_children\":true},"
                            + "{\"id\":\"tp\",\"type\":\"template\",\"has_children\":true},"
                            + "{\"id\":\"bc\",\"type\":\"breadcrumb\",\"has_children\":true},"
                            + "{\"id\":\"toc\",\"type\":\"table_of_contents\",\"has_children\":true},"
                            + "{\"id\":\"nc\",\"type\":\"toggle\",\"has_children\":false},"
                            + "{\"id\":\"tc\",\"type\":\"toggle\",\"has_children\":true,"
                            + "\"toggle\":{\"rich_text\":[]}}],\"has_more\":false}"));
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            List<NotionBlock> blocks = client.getBlockChildrenAll("root");

            assertThat(blocks).hasSize(8);
            // 只有那个 has_children=true 的 toggle 被递归（其余六种被跳过）。
            // 桩对任何路径都回同一份 payload，所以 tc 会被逐层递归到 maxBlockDepth=5
            // ——Go 实录同样是 6 次请求（l0 + tc×5）。
            assertThat(server.requestCount()).isEqualTo(6);
            assertThat(server.requestDescribes().get(1)).isEqualTo("GET /v1/blocks/tc/children");
            assertThat(blocks.get(0).children).isNull();
            assertThat(blocks.get(2).children).isNull();
            assertThat(blocks.get(7).children).isNotNull();
            // 以 type 命名的字段被抽进 RawContent（自定义反序列化）
            assertThat(blocks.get(0).rawContent.toString()).isEqualTo("{\"title\":\"T\"}");
            assertThat(blocks.get(2).rawContent).isNull();
            assertThat(blocks.get(7).rawContent.toString()).isEqualTo("{\"rich_text\":[]}");
        }
    }

    @Test
    void blockPaginationStopsAtMaxBlocksPerPage() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.fallback(exchange -> {
                StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"results\":[");
                for (int i = 0; i < 400; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"id\":\"b").append(i)
                            .append("\",\"type\":\"paragraph\",\"has_children\":false}");
                }
                sb.append("],\"has_more\":true,\"next_cursor\":\"C\"}");
                NotionStubServer.respond(exchange, 200, sb.toString());
            });
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            List<NotionBlock> blocks = client.getBlockChildrenAll("root");
            // Go 实测：3 次请求、1200 块后停下
            assertThat(server.requestCount()).isEqualTo(3);
            assertThat(blocks).hasSize(1200);
        }
    }

    @Test
    void getBlockChildrenFlatDoesNotApplyMaxBlocksLimit() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            AtomicLong calls = new AtomicLong();
            server.fallback(exchange -> {
                long call = calls.incrementAndGet();
                boolean hasMore = call < 4;
                StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"results\":[");
                for (int i = 0; i < 400; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"id\":\"b").append(i).append("\",\"type\":\"paragraph\"}");
                }
                sb.append("],\"has_more\":").append(hasMore)
                        .append(",\"next_cursor\":\"C").append(call).append("\"}");
                NotionStubServer.respond(exchange, 200, sb.toString());
            });
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            // flat 版本**没有** 1000 块上限：它会把 4 页（1600 块）全抓完，
            // 而 getBlockChildrenAll 在第 3 次请求（1200 块）就停了。
            List<NotionBlock> flat = client.getBlockChildrenFlat("root");
            assertThat(calls.get()).isEqualTo(4);
            assertThat(flat).hasSize(1600);
        }
    }

    @Test
    void getBlockChildrenWrapsErrorsWithBlockId() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/blocks/bad/children", 404, "{}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            assertThatThrownBy(() -> client.getBlockChildrenAll("bad"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("get block children for bad: "
                            + "resource not found in source system: /v1/blocks/bad/children");
        }
    }

    // ── 数据库查询回落 ───────────────────────────────────────────────────

    @Test
    void queryDatabaseAllFallsBackToDatabaseContainer() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/data_sources/db-9/query", 404, "{}");
            server.json("/v1/databases/db-9",
                    "{\"id\":\"db-9\",\"object\":\"database\","
                            + "\"title\":[{\"plain_text\":\"Test Database\"}],"
                            + "\"data_sources\":[{\"id\":\"ds-2\",\"name\":\"Default\"}]}");
            server.json("/v1/data_sources/ds-2/query",
                    "{\"object\":\"list\",\"results\":[{\"id\":\"record-1\",\"object\":\"page\","
                            + "\"properties\":{\"Name\":{\"type\":\"title\","
                            + "\"title\":[{\"plain_text\":\"Record One\"}]}}}],\"has_more\":false}");

            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            List<NotionPage> records = client.queryDatabaseAll("db-9");
            assertThat(records).hasSize(1);
            assertThat(records.get(0).id()).isEqualTo("record-1");
            // paginatePages 会回填 Title
            assertThat(records.get(0).title).isEqualTo("Record One");
            assertThat(server.requestDescribes()).containsExactly(
                    "POST /v1/data_sources/db-9/query body={\"page_size\":100}",
                    "GET /v1/databases/db-9",
                    "POST /v1/data_sources/ds-2/query body={\"page_size\":100}");
        }
    }

    @Test
    void queryDatabaseAllDirectDataSourceHitDoesNotFallBack() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/data_sources/ds-2/query",
                    "{\"object\":\"list\",\"results\":[],\"has_more\":false}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            assertThat(client.queryDatabaseAll("ds-2")).isEmpty();
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void queryDatabaseAllReportsBothFailures() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/data_sources/nope/query", 404, "{}");
            server.status("/v1/databases/nope", 404, "{}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            assertThatThrownBy(() -> client.queryDatabaseAll("nope"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("query database nope: not a data_source "
                            + "(paginate /v1/data_sources/nope/query: "
                            + "resource not found in source system: /v1/data_sources/nope/query) "
                            + "and not a database "
                            + "(resource not found in source system: /v1/databases/nope)");
        }
    }

    @Test
    void queryDatabaseAllReportsMissingDataSources() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/v1/data_sources/db-empty/query", 404, "{}");
            server.json("/v1/databases/db-empty",
                    "{\"id\":\"db-empty\",\"object\":\"database\",\"data_sources\":[]}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            assertThatThrownBy(() -> client.queryDatabaseAll("db-empty"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("database db-empty has no data sources");
        }
    }

    @Test
    void getDatabaseInfoAndDataSourceInfo() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.json("/v1/databases/db-9",
                    "{\"id\":\"db-9\",\"object\":\"database\","
                            + "\"title\":[{\"plain_text\":\"Test Database\"}],"
                            + "\"data_sources\":[{\"id\":\"ds-2\"}]}");
            server.json("/v1/data_sources/ds-1",
                    "{\"id\":\"ds-1\",\"object\":\"data_source\",\"properties\":{"
                            + "\"Name\":{\"type\":\"title\",\"title\":{}},"
                            + "\"Status\":{\"type\":\"select\"}}}");
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());

            NotionDatabaseInfo info = client.getDatabaseInfo("db-9");
            assertThat(info.dataSourceId).isEqualTo("ds-2");
            assertThat(info.page.title).isEqualTo("Test Database");

            // GetDataSourceInfo 走顶层 title 回落（这里是空的）→ extractTitle 回 ""
            NotionPage ds = client.getDataSourceInfo("ds-1");
            assertThat(ds.id()).isEqualTo("ds-1");
            assertThat(ds.title).isEmpty();
        }
    }

    // ── 下载 ─────────────────────────────────────────────────────────────

    @Test
    void downloadFileReturnsBytes() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.route("/good.bin", exchange -> NotionStubServer.respond(exchange, 200, "BINARY"));
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            assertThat(new String(client.downloadFile(server.baseUrl() + "/good.bin")))
                    .isEqualTo("BINARY");
        }
    }

    @Test
    void downloadFileDoesNotRetryNonServerErrors() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.status("/notok", 403, "");
            NotionTestSupport.RecordingSleeper sleeper = new NotionTestSupport.RecordingSleeper();
            NotionClient client = NotionTestSupport.fastClient("tok", server.baseUrl(), sleeper);
            assertThatThrownBy(() -> client.downloadFile(server.baseUrl() + "/notok"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("download file: download failed with status 403");
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(sleeper.slept).isEmpty();
        }
    }

    @Test
    void downloadFileRetriesServerErrors() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            AtomicLong calls = new AtomicLong();
            server.route("/flaky", exchange -> {
                if (calls.incrementAndGet() < 3) {
                    NotionStubServer.respond(exchange, 503, "");
                    return;
                }
                NotionStubServer.respond(exchange, 200, "OK");
            });
            NotionTestSupport.RecordingSleeper sleeper = new NotionTestSupport.RecordingSleeper();
            NotionClient client = NotionTestSupport.fastClient("tok", server.baseUrl(), sleeper,
                    NotionClient.Backoff.exponentialSeconds());
            assertThat(new String(client.downloadFile(server.baseUrl() + "/flaky"))).isEqualTo("OK");
            assertThat(sleeper.slept).containsExactly(1000L, 2000L);
        }
    }

    /**
     * 上限判定：Go 是 {@code io.LimitReader(maxDownloadSize+1)} 流式截断，
     * Java 侧只能在读完后判。这里把上限调小（测试缝）来验同一条分支，
     * 免得真的往测试 JVM 里灌 100MB。文案里仍是 Go 的常量值。
     */
    @Test
    void downloadFileRejectsOversizedBody() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            server.route("/big.bin", exchange ->
                    NotionStubServer.respond(exchange, 200, "x".repeat(64)));
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            client.setMaxDownloadSizeForTest(32);
            assertThatThrownBy(() -> client.downloadFile(server.baseUrl() + "/big.bin"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessage("file exceeds maximum download size (100 MB)");
        }
    }

    /**
     * SSRF：默认（无白名单）下，任何直连 IP 的附件地址都被拒。
     *
     * <p>不用真实公网域名——{@code 169.254.169.254} 是 link-local 的云元数据地址，
     * 一定会被 {@code SsrfGuard} 拒绝（对照 Go 的
     * {@code TestDownloadFile_RejectsLoopbackURL}）。</p>
     */
    @Test
    void downloadFileRejectsBlockedUrls() throws Exception {
        try (NotionStubServer server = new NotionStubServer()) {
            NotionClient client = NotionTestSupport.fastClient(server.baseUrl());
            assertThatThrownBy(() ->
                    client.downloadFile("http://169.254.169.254/latest/meta-data/"))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageStartingWith("attachment URL rejected: SSRF validation failed");
            assertThat(server.requestCount()).isZero();
        }
    }

    // ── 限流器 ───────────────────────────────────────────────────────────

    /**
     * 令牌桶的确定性验证：把时钟也换成假的，于是"第 4 个请求等 334ms"
     * 是可精确断言的，而不是靠真等 1/3 秒。
     */
    @Test
    void rateLimiterAllowsBurstThenThrottles() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        List<Long> slept = new ArrayList<>();
        NotionClient.RateLimiter limiter = NotionClient.RateLimiter.perSecond(
                3, 3,
                millis -> {
                    slept.add(millis);
                    clock.addAndGet(millis * 1_000_000L);
                },
                clock::get);

        limiter.waitForToken();
        limiter.waitForToken();
        limiter.waitForToken();
        assertThat(slept).as("突发 3 个令牌，前 3 次不睡").isEmpty();

        limiter.waitForToken();
        assertThat(slept).containsExactly(334L);
    }

    @Test
    void rateLimiterRefillsOverTime() {
        AtomicLong clock = new AtomicLong(0L);
        List<Long> slept = new ArrayList<>();
        NotionClient.RateLimiter limiter = NotionClient.RateLimiter.perSecond(
                3, 1,
                millis -> {
                    slept.add(millis);
                    clock.addAndGet(millis * 1_000_000L);
                },
                clock::get);

        limiter.waitForToken();          // 用掉唯一的突发令牌
        clock.addAndGet(1_000_000_000L); // 过 1 秒 → 回满 3 个（上限 burst=1）
        limiter.waitForToken();
        assertThat(slept).isEmpty();
    }

    @Test
    void unlimitedLimiterNeverSleeps() {
        NotionClient.RateLimiter limiter = NotionClient.RateLimiter.unlimited();
        for (int i = 0; i < 100; i++) {
            limiter.waitForToken();
        }
    }

    @Test
    void retryAfterParsing() {
        assertThat(NotionClient.retryAfterMillis("2")).isEqualTo(2000L);
        assertThat(NotionClient.retryAfterMillis("0.25")).isEqualTo(250L);
        assertThat(NotionClient.retryAfterMillis("")).isEqualTo(1000L);
        assertThat(NotionClient.retryAfterMillis(null)).isEqualTo(1000L);
        assertThat(NotionClient.retryAfterMillis("abc")).isEqualTo(1000L);
        assertThat(NotionClient.retryAfterMillis("0")).isEqualTo(1000L);
        assertThat(NotionClient.retryAfterMillis("-1")).isEqualTo(1000L);
        // Go 的 strconv.ParseFloat 不接受首尾空白
        assertThat(NotionClient.retryAfterMillis(" 2 ")).isEqualTo(1000L);
    }
}
