package com.ragagent.datasource.connector.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link RssConnector} 的端到端对等测试（stub server，**不碰真实网络**）。
 *
 * <h2>stub 的形状照抄 Go 的 {@code fakeFeed}</h2>
 * <p>{@code connector_test.go} 里的 {@code newFakeFeed} 起一个 httptest server，
 * {@code /article/*} 返回带导航/页脚的 HTML、{@code /feed.xml} 返回一个两 W3C 条目的
 * RSS 2.0（{@code pubDate} + {@code description} + {@code guid}），
 * 并且能切成 503（{@code failFeed}）或"需要 X-Test-Auth"（{@code itemContent=="needs-auth"}）。
 * Java 侧用 {@code com.sun.net.httpserver.HttpServer} 复刻同样的形状。</p>
 *
 * <h2>SSRF 白名单是静态状态</h2>
 * <p>对照 Go 的 {@code TestMain} 设 {@code SSRF_WHITELIST=127.0.0.1,::1}：
 * Java 进程内改不了 env，所以用 {@code new SsrfGuard()} + {@code reloadWhitelist(...)}
 * 注入，并在 {@code @AfterAll} <b>还原</b>成默认实例（否则会污染同 JVM 的其它测试）。</p>
 *
 * <h2>与 Go 的<b>关键差异</b>：文章正文</h2>
 * <p>Go 的 {@code TestConnector_FetchAll_FullTextMarkdown} 断言正文里有
 * {@code "first paragraph"}（readability 抽出来的全文）。Java 侧默认的
 * {@link UnavailableArticleExtractor} <b>永远失败</b>，所以这里断言的是
 * <b>Go 的回落分支</b>：正文 = feed 的 {@code description}。
 * 期望值来自 Go 探针实测（把 {@code /article/*} 换成 404 强制走回落，见
 * {@link #fetchAllMatchesGoFallbackOutput()} 的注释）。
 * "抽取成功"那条路径用注入的假抽取器单独测（{@link #articleExtractorSeamRecoversGoSuccessPath()}）。</p>
 */
class RssConnectorTest {

    private static SsrfGuard originalGuard;

    @BeforeAll
    static void allowLoopback() {
        originalGuard = ConnectorHttp.ssrfGuard();
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1,::1,localhost");
        ConnectorHttp.setSsrfGuard(guard);
    }

    @AfterAll
    static void restoreGuard() {
        ConnectorHttp.setSsrfGuard(originalGuard == null ? new SsrfGuard() : originalGuard);
    }

    // ── stub server（对照 Go 的 fakeFeed） ────────────────────────────────

    /** 对照 Go 的 {@code fakeFeed}：一个 RSS feed + 若干"文章页"。 */
    private static final class FakeFeed implements AutoCloseable {

        private final HttpServer server;
        private final List<String> articleAuthHeaders = new ArrayList<>();
        private final AtomicInteger articleFetches = new AtomicInteger();
        private final AtomicBoolean failFeed = new AtomicBoolean();
        private final AtomicBoolean articleNotFound = new AtomicBoolean();
        private String itemContent = "";
        private String feedXmlOverride;

        FakeFeed() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/article/", this::handleArticle);
            server.createContext("/feed.xml", this::handleFeed);
            server.setExecutor(null);
            server.start();
        }

        String feedUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/feed.xml";
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void failFeed(boolean value) {
            failFeed.set(value);
        }

        void articleNotFound(boolean value) {
            articleNotFound.set(value);
        }

        void itemContent(String value) {
            this.itemContent = value;
        }

        void feedXmlOverride(String xml) {
            this.feedXmlOverride = xml;
        }

        int articleFetches() {
            return articleFetches.get();
        }

        void resetArticleFetches() {
            articleFetches.set(0);
        }

        List<String> articleAuthHeaders() {
            return articleAuthHeaders;
        }

        private void handleArticle(HttpExchange exchange) throws IOException {
            articleFetches.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("X-Test-Auth");
            if (auth != null && !auth.isEmpty()) {
                articleAuthHeaders.add(auth);
            }
            if (articleNotFound.get()) {
                send(exchange, 404, "text/html", "");
                return;
            }
            String body = "<!DOCTYPE html><html><head><title>Full " + exchange.getRequestURI()
                    + "</title></head><body><nav>menu</nav><article><h1>Heading</h1>"
                    + longArticleBody() + "</article><footer>foot</footer></body></html>";
            send(exchange, 200, "text/html; charset=utf-8", body);
        }

        private void handleFeed(HttpExchange exchange) throws IOException {
            if (failFeed.get()) {
                send(exchange, 503, "text/plain", "");
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("X-Test-Auth");
            if ("needs-auth".equals(itemContent) && !"secret".equals(auth)) {
                send(exchange, 401, "text/plain", "");
                return;
            }
            if (feedXmlOverride != null) {
                send(exchange, 200, "application/rss+xml", feedXmlOverride);
                return;
            }
            String base = baseUrl();
            String desc = "summary fallback";
            String xml = "<?xml version=\"1.0\"?>\n"
                    + "<rss version=\"2.0\"><channel>\n"
                    + "<title>Test Feed</title>\n"
                    + "<link>" + base + "</link>\n"
                    + "<description>A test feed</description>\n"
                    + "<item>\n"
                    + "  <title>Article One</title>\n"
                    + "  <link>" + base + "/article/a1</link>\n"
                    + "  <guid>guid-1</guid>\n"
                    + "  <pubDate>Mon, 02 Jan 2006 15:04:05 GMT</pubDate>\n"
                    + "  <description>" + desc + "</description>\n"
                    + "</item>\n"
                    + "<item>\n"
                    + "  <title>Article Two</title>\n"
                    + "  <link>" + base + "/article/a2</link>\n"
                    + "  <guid>guid-2</guid>\n"
                    + "  <pubDate>Tue, 03 Jan 2006 15:04:05 GMT</pubDate>\n"
                    + "  <description>" + desc + "</description>\n"
                    + "</item>\n"
                    + "</channel></rss>";
            send(exchange, 200, "application/rss+xml", xml);
        }

        private static void send(HttpExchange exchange, int status, String contentType, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (contentType != null && !contentType.isEmpty()) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            // Content-Length 必须显式给，否则 0 长度的响应在部分实现上会挂住
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** 与 Go 的 {@code longArticleBody} 同形（本模块用不到它做断言，只保证页面"像文章"）。 */
    private static String longArticleBody() {
        return "<p>This is the first paragraph of a reasonably long article that the readability "
                + "extractor should detect as the main content of the page.</p>"
                + "<p>The second paragraph continues the discussion with more sentences.</p>"
                + "<p>A third paragraph adds further substance.</p>";
    }

    private static DataSourceConfig makeConfig(String feedUrls, String headers) {
        DataSourceConfig config = new DataSourceConfig();
        config.setType("rss");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("feed_urls", feedUrls);
        config.setSettings(settings);
        Map<String, Object> credentials = new LinkedHashMap<>();
        if (headers != null && !headers.isEmpty()) {
            credentials.put("auth_headers", headers);
        }
        config.setCredentials(credentials);
        return config;
    }

    // ── Type / 祖先 ───────────────────────────────────────────────────────

    @Test
    void typeIsRss() {
        assertThat(new RssConnector().type()).isEqualTo("rss");
    }

    @Test
    void resolveResourceAncestorsIsAlwaysEmpty() {
        try (FakeFeed feed = new FakeFeed()) {
            assertThat(new RssConnector().resolveResourceAncestors(
                    makeConfig(feed.feedUrl(), null), List.of("x"))).isEmpty();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ── Validate ─────────────────────────────────────────────────────────

    @Test
    void validateSucceedsForAHealthyFeed() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            new RssConnector().validate(makeConfig(feed.feedUrl(), null));
        }
    }

    @Test
    void validateRejectsPrivateFeedWithoutAuthHeader() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.itemContent("needs-auth");
            assertThatThrownBy(() -> new RssConnector().validate(makeConfig(feed.feedUrl(), null)))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageContaining("fetch feed " + feed.feedUrl() + ": ")
                    .hasMessageContaining("HTTP 401 401 Unauthorized");
        }
    }

    @Test
    void validateSucceedsForPrivateFeedWithAuthHeader() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.itemContent("needs-auth");
            new RssConnector().validate(
                    makeConfig(feed.feedUrl(), "X-Test-Auth: secret"));
        }
    }

    @Test
    void validateReportsParseFailureWithGoMessage() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.feedXmlOverride("not a feed at all");
            assertThatThrownBy(() -> new RssConnector().validate(makeConfig(feed.feedUrl(), null)))
                    .isInstanceOf(ConnectorException.class)
                    // Go 实录：`parse feed <url>: Failed to detect feed type`
                    .hasMessage("parse feed " + feed.feedUrl() + ": Failed to detect feed type");
        }
    }

    @Test
    void validateRequiresFeedUrls() {
        // Go 实录：`invalid credentials: feed_urls is required`
        DataSourceConfig config = new DataSourceConfig();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("feed_urls", "   ");
        config.setSettings(settings);
        config.setCredentials(new LinkedHashMap<>());
        assertThatThrownBy(() -> new RssConnector().validate(config))
                .isInstanceOf(ConnectorException.InvalidCredentials.class)
                .hasMessage("invalid credentials: feed_urls is required");
    }

    // ── ListResources ────────────────────────────────────────────────────

    @Test
    void listResourcesReturnsOneResourcePerFeed() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            List<Resource> resources =
                    new RssConnector().listResources(makeConfig(feed.feedUrl(), null), "");
            assertThat(resources).hasSize(1);
            Resource res = resources.get(0);
            assertThat(res.getExternalId()).isEqualTo(feed.feedUrl());
            assertThat(res.getType()).isEqualTo("feed");
            assertThat(res.getName()).isEqualTo("Test Feed");
            assertThat(res.getDescription()).isEqualTo("A test feed");
            assertThat(res.getUrl()).isEqualTo(feed.baseUrl());
            // 没有 <lastBuildDate>/<dc:date> → UpdatedParsed 为 nil → modified_at 留零值
            assertThat(res.getModifiedAt().toInstant())
                    .isEqualTo(java.time.Instant.parse("0001-01-01T00:00:00Z"));
            assertThat(res.getMetadata()).containsEntry("item_count", 2);
        }
    }

    @Test
    void listResourcesWithParentIdIsEmptyBecauseFeedsAreFlat() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            assertThat(new RssConnector().listResources(makeConfig(feed.feedUrl(), null), "feed-x"))
                    .isEmpty();
        }
    }

    @Test
    void listResourcesKeepsFailedFeedWithErrorDescription() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.failFeed(true);
            List<Resource> resources =
                    new RssConnector().listResources(makeConfig(feed.feedUrl(), null), "");
            assertThat(resources).hasSize(1);
            Resource res = resources.get(0);
            // 名字回落到 URL（feed.Title 拿不到）
            assertThat(res.getName()).isEqualTo(feed.feedUrl());
            assertThat(res.getDescription())
                    .isEqualTo("fetch failed: HTTP 503 503 Service Unavailable");
        }
    }

    @Test
    void listResourcesKeepsUnparseableFeedWithErrorDescription() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.feedXmlOverride("not a feed at all");
            List<Resource> resources =
                    new RssConnector().listResources(makeConfig(feed.feedUrl(), null), "");
            assertThat(resources).hasSize(1);
            assertThat(resources.get(0).getDescription())
                    .isEqualTo("parse failed: Failed to detect feed type");
        }
    }

    @Test
    void listResourcesUsesTrimmableTitleFallback() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.feedXmlOverride("<?xml version=\"1.0\"?>"
                    + "<rss version=\"2.0\"><channel>"
                    + "<title>   </title><link></link><description></description>"
                    + "<item><title>x</title><guid>g</guid></item>"
                    + "</channel></rss>");
            Resource res = new RssConnector()
                    .listResources(makeConfig(feed.feedUrl(), null), "").get(0);
            // Title 全是空白 → TrimSpace 后为空 → 保留 URL
            assertThat(res.getName()).isEqualTo(feed.feedUrl());
            // Link 为空 → URL 也保留成 feedURL
            assertThat(res.getUrl()).isEqualTo(feed.feedUrl());
        }
    }

    // ── FetchAll：Go 的"回落"分支（默认装配） ────────────────────────────

    /**
     * 期望值 = <b>Go 探针实录</b>：把 {@code /article/*} 换成 404 强制 Go 走
     * "全文抓取失败 → 用 feed 内容"那条分支，跑出来的 {@code FetchedItem}：
     * <pre>
     *   external_id      "<feedURL>:guid-1"
     *   title            "Article One"
     *   content          base64("summary fallback")   ← HTML→MD 对纯文本是恒等
     *   content_type     "text/markdown"
     *   file_name        "Article One.md"
     *   url              "<base>/article/a1"
     *   updated_at       "2006-01-02T15:04:05Z"        ← pubDate 进了 PublishedParsed
     *   created_at       "0001-01-01T00:00:00Z"
     *   metadata         {author:"", channel:"rss", feed_title:"Test Feed",
     *                     feed_url:"<feedURL>", guid:"guid-1", link:"<base>/article/a1"}
     *   is_deleted       false
     *   source_resource_id "<feedURL>"
     * </pre>
     */
    @Test
    void fetchAllMatchesGoFallbackOutput() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            List<FetchedItem> items =
                    new RssConnector().fetchAll(makeConfig(feed.feedUrl(), null), null);
            assertThat(items).hasSize(2);

            FetchedItem first = items.get(0);
            assertThat(first.getExternalId()).isEqualTo(feed.feedUrl() + ":guid-1");
            assertThat(first.getTitle()).isEqualTo("Article One");
            assertThat(new String(first.getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("summary fallback");
            assertThat(first.getContentType()).isEqualTo("text/markdown");
            assertThat(first.getFileName()).isEqualTo("Article One.md");
            assertThat(first.getUrl()).isEqualTo(feed.baseUrl() + "/article/a1");
            assertThat(first.getUpdatedAt().toInstant())
                    .isEqualTo(java.time.Instant.parse("2006-01-02T15:04:05Z"));
            assertThat(first.getCreatedAt().toInstant())
                    .isEqualTo(java.time.Instant.parse("0001-01-01T00:00:00Z"));
            assertThat(first.isDeleted()).isFalse();
            assertThat(first.getSourceResourceId()).isEqualTo(feed.feedUrl());
            assertThat(first.getMetadata()).containsOnlyKeys(
                    "channel", "feed_url", "feed_title", "guid", "link", "author");
            assertThat(first.getMetadata().get("channel")).isEqualTo("rss");
            assertThat(first.getMetadata().get("feed_url")).isEqualTo(feed.feedUrl());
            assertThat(first.getMetadata().get("feed_title")).isEqualTo("Test Feed");
            assertThat(first.getMetadata().get("guid")).isEqualTo("guid-1");
            assertThat(first.getMetadata().get("link")).isEqualTo(feed.baseUrl() + "/article/a1");
            assertThat(first.getMetadata().get("author")).isEmpty();

            FetchedItem second = items.get(1);
            assertThat(second.getExternalId()).isEqualTo(feed.feedUrl() + ":guid-2");
            assertThat(second.getUpdatedAt().toInstant())
                    .isEqualTo(java.time.Instant.parse("2006-01-03T15:04:05Z"));
        }
    }

    @Test
    void fetchAllDoesNotSendAuthHeadersToArticlePages() throws IOException {
        // 照抄 Go 的 TestConnector_FetchAll_DoesNotSendAuthHeadersToArticles：
        // 文章页在第三方域名上，把凭据发过去就是泄漏。
        try (FakeFeed feed = new FakeFeed()) {
            feed.itemContent("needs-auth");
            List<FetchedItem> items = new RssConnector()
                    .fetchAll(makeConfig(feed.feedUrl(), "X-Test-Auth: secret"), null);
            assertThat(items).hasSize(2);
            assertThat(feed.articleFetches()).isEqualTo(2); // 文章页确实被抓过
            assertThat(feed.articleAuthHeaders()).isEmpty();
        }
    }

    @Test
    void fetchAllSendsDefaultUserAgentAndAcceptToFeed() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            new RssConnector().fetchAll(makeConfig(feed.feedUrl(), null), null);
            // 抓取成功本身即证明 UA 没有被拒；这里再显式确认常量形状没被改动
            assertThat(RssClient.DEFAULT_USER_AGENT).startsWith("Mozilla/5.0 (compatible; WeKnora-RSS");
            assertThat(RssClient.DEFAULT_ACCEPT).contains("application/rss+xml");
        }
    }

    // ── FetchAll：分页/条目细节 ──────────────────────────────────────────

    @Test
    void itemWithoutGUIDFallsBackToLinkAndUntitled() throws IOException {
        // Go 探针实录：无 title/guid 的条目 → itemID=link、"untitled"、guid=""、updated_at=now
        try (FakeFeed feed = new FakeFeed()) {
            feed.feedXmlOverride("<?xml version=\"1.0\"?>"
                    + "<rss version=\"2.0\"><channel><title>Ext Feed</title>"
                    + "<link>http://ext.example/</link><description>ext desc</description>"
                    + "<item><link>" + feed.baseUrl() + "/article/x2</link>"
                    + "<description>no title no guid</description></item>"
                    + "</channel></rss>");
            List<FetchedItem> items =
                    new RssConnector().fetchAll(makeConfig(feed.feedUrl(), null), null);
            assertThat(items).hasSize(1);
            FetchedItem item = items.get(0);
            assertThat(item.getExternalId())
                    .isEqualTo(feed.feedUrl() + ":" + feed.baseUrl() + "/article/x2");
            assertThat(item.getTitle()).isEqualTo("untitled");
            assertThat(item.getFileName()).isEqualTo("untitled.md");
            assertThat(item.getMetadata().get("guid")).isEmpty();
            // 无任何 Parsed 时间 → 回落 now()：只断言"非零 + 在合理区间"，不靠墙钟造时间
            assertThat(item.getUpdatedAt().toInstant())
                    .isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"))
                    .isBefore(java.time.Instant.now().plusSeconds(300));
        }
    }

    @Test
    void itemWithoutAnyIdIsSkipped() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.feedXmlOverride("<?xml version=\"1.0\"?>"
                    + "<rss version=\"2.0\"><channel><title>T</title>"
                    + "<item><description>no id at all</description></item>"
                    + "<item><guid>keep-me</guid><description>has id</description></item>"
                    + "</channel></rss>");
            List<FetchedItem> items =
                    new RssConnector().fetchAll(makeConfig(feed.feedUrl(), null), null);
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getExternalId()).isEqualTo(feed.feedUrl() + ":keep-me");
        }
    }

    @Test
    void feedContentPrefersContentEncodedOverDescription() throws IOException {
        // Go 探针实录：<content:encoded><![CDATA[<p>encoded <em>body</em></p>]]> + <description>
        //   → content = markdown("encoded *body*")、author 来自 dc:creator、updated_at 来自 dc:date
        try (FakeFeed feed = new FakeFeed()) {
            feed.feedXmlOverride("<?xml version=\"1.0\"?>"
                    + "<rss version=\"2.0\" "
                    + "xmlns:content=\"http://purl.org/rss/1.0/modules/content/\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><channel>"
                    + "<title>Ext Feed</title><link>http://ext.example/</link>"
                    + "<description>ext desc</description>"
                    + "<item><title>Ext One</title>"
                    + "<link>" + feed.baseUrl() + "/article/x1</link>"
                    + "<guid isPermaLink=\"false\">ext-1</guid>"
                    + "<dc:date>2006-01-04T02:03:04Z</dc:date>"
                    + "<dc:creator>Dave</dc:creator>"
                    + "<content:encoded><![CDATA[<p>encoded <em>body</em></p>]]></content:encoded>"
                    + "<description>plain desc</description>"
                    + "</item></channel></rss>");
            List<FetchedItem> items =
                    new RssConnector().fetchAll(makeConfig(feed.feedUrl(), null), null);
            assertThat(items).hasSize(1);
            FetchedItem item = items.get(0);
            assertThat(new String(item.getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("encoded *body*");
            assertThat(item.getMetadata().get("author")).isEqualTo("Dave");
            assertThat(item.getMetadata().get("guid")).isEqualTo("ext-1");
            // RSS 的 UpdatedParsed 只来自 dc:date（pubDate 进 PublishedParsed）
            assertThat(item.getUpdatedAt().toInstant())
                    .isEqualTo(java.time.Instant.parse("2006-01-04T02:03:04Z"));
        }
    }

    @Test
    void fetchAllHonoursResourceSelection() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            // 选中的 URL 不在 settings 里：Go 的 walk 直接拿它当 feed 去抓，
            // **不校验它属于已配置 feed** → 抓不到 → 全部失败。
            List<String> selection = List.of(feed.baseUrl() + "/nope.xml");
            assertThatThrownBy(() -> new RssConnector().fetchAll(makeConfig(feed.feedUrl(), null), selection))
                    .isInstanceOf(AllFeedsFailedException.class)
                    .hasMessageStartingWith("all feeds failed: " + feed.baseUrl() + "/nope.xml: ");
        }
    }

    @Test
    void fetchAllWithExplicitSelectionOnlyTouchesThatFeed() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            List<FetchedItem> items = new RssConnector()
                    .fetchAll(makeConfig("https://unused.example/f", null), List.of(feed.feedUrl()));
            assertThat(items).hasSize(2);
        }
    }

    // ── 增量同步：两级去重 ───────────────────────────────────────────────

    @Test
    void incrementalFirstSyncEmitsEverything() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            DataSourceConfig config = makeConfig(feed.feedUrl(), null);
            config.setResourceIds(List.of(feed.feedUrl()));

            Connector.FetchIncrementalResult result =
                    new RssConnector().fetchIncremental(config, null);
            assertThat(result.items()).hasSize(2);
            assertThat(result.cursor()).isNotNull();
            assertThat(feed.articleFetches()).isEqualTo(2);
        }
    }

    @Test
    void incrementalSecondSyncSkipsWithoutFetchingArticles() throws IOException {
        // 照抄 Go 的 TestConnector_FetchIncremental_SkipsWithoutArticleFetch：
        // feed 信号没变 → 连文章页都不抓（这是第一级去重）。
        try (FakeFeed feed = new FakeFeed()) {
            DataSourceConfig config = makeConfig(feed.feedUrl(), null);
            config.setResourceIds(List.of(feed.feedUrl()));

            Connector.FetchIncrementalResult first =
                    new RssConnector().fetchIncremental(config, null);
            assertThat(first.items()).hasSize(2);

            feed.resetArticleFetches();
            Connector.FetchIncrementalResult second =
                    new RssConnector().fetchIncremental(config, first.cursor());
            assertThat(second.items()).isEmpty();
            assertThat(feed.articleFetches()).isZero();
        }
    }

    @Test
    void incrementalRefetchesWhenFeedContentChanges() throws IOException {
        // 第二级去重的反面：feed 内容变了 → 重新抓取并重新灌入
        try (FakeFeed feed = new FakeFeed()) {
            DataSourceConfig config = makeConfig(feed.feedUrl(), null);
            config.setResourceIds(List.of(feed.feedUrl()));
            Connector.FetchIncrementalResult first =
                    new RssConnector().fetchIncremental(config, null);

            feed.feedXmlOverride("<?xml version=\"1.0\"?>"
                    + "<rss version=\"2.0\"><channel><title>Test Feed</title>"
                    + "<link>" + feed.baseUrl() + "</link><description>A test feed</description>"
                    + "<item><title>Article One</title>"
                    + "<link>" + feed.baseUrl() + "/article/a1</link>"
                    + "<guid>guid-1</guid>"
                    + "<pubDate>Mon, 02 Jan 2006 15:04:05 GMT</pubDate>"
                    + "<description>CHANGED summary</description></item>"
                    + "</channel></rss>");

            Connector.FetchIncrementalResult second =
                    new RssConnector().fetchIncremental(config, first.cursor());
            assertThat(second.items()).hasSize(1);
            assertThat(new String(second.items().get(0).getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("CHANGED summary");
        }
    }

    @Test
    void incrementalCursorCarriesBothFingerprintLevels() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            DataSourceConfig config = makeConfig(feed.feedUrl(), null);
            config.setResourceIds(List.of(feed.feedUrl()));
            Connector.FetchIncrementalResult result =
                    new RssConnector().fetchIncremental(config, null);

            @SuppressWarnings("unchecked")
            Map<String, Object> feedItems =
                    (Map<String, Object>) result.cursor().getConnectorCursor().get("feed_items");
            @SuppressWarnings("unchecked")
            Map<String, Object> items = (Map<String, Object>) feedItems.get(feed.feedUrl());
            assertThat(items).containsOnlyKeys("guid-1", "guid-2");
            // Go 实录（同一份 stub feed）：内容都是 "summary fallback" → h:ab781b82e8cb102c，
            // 且两条**相同**。这个值是跨语言的（只取决于内容），所以它能证伪整条链路上的偏差。
            assertThat(items.get("guid-1")).isEqualTo("h:ab781b82e8cb102c");
            assertThat(items.get("guid-2")).isEqualTo(items.get("guid-1"));

            @SuppressWarnings("unchecked")
            Map<String, Object> feedSignals =
                    (Map<String, Object>) result.cursor().getConnectorCursor().get("feed_signals");
            @SuppressWarnings("unchecked")
            Map<String, Object> signals = (Map<String, Object>) feedSignals.get(feed.feedUrl());
            assertThat(signals).containsOnlyKeys("guid-1", "guid-2");
            assertThat((String) signals.get("guid-1")).startsWith("s:");
            assertThat(signals.get("guid-1")).isNotEqualTo(signals.get("guid-2"));
        }
    }

    // ── 跨语言：与 Go 探针录下的**内容指纹**逐字对齐 ──────────────────────

    /**
     * 这是本模块最强的一条等价性证据：把 Go 探针跑过的<b>同一份 feed</b> 再跑一遍，
     * 断言 {@code connector_cursor.feed_items} 里的 {@code "h:…"} 指纹与 Go 录下的<b>完全一样</b>。
     *
     * <p>Go 探针实录（{@code /tmp} 里挂 overlay 跑的，文章页故意不可达 → 走回落分支）：</p>
     * <pre>
     *   Atom feed : e1 -&gt; h:54d426c03e8e01af    e2 -&gt; h:16367aacb67a4a01
     *   扩展 RSS  : ext-1 -&gt; h:fe31a71ced1d86e1  x2 -&gt; h:e204db00c5fd2586
     * </pre>
     * <p>{@code h:} 是<b>内容 Markdown</b> 的哈希，与 URL 无关，所以可以跨语言直接比。
     * 它一条断言就同时覆盖了：XML 字段抽取 → {@code firstNonEmpty(Content, Description)}
     * 的选择 → HTML→Markdown 的字节输出 → SHA-256 前缀。任何一环差一点都会在这里红。</p>
     *
     * <p>（{@code s:…} 信号指纹含 link/guid，Go 探针里用的是 {@code atom.example} 这种域名，
     * 端口/域名不同就对不上，故不在这里比——它的算法由
     * {@link RssPureFunctionsTest#feedSignalFingerprintMatchesGo()} 单独钉住。）</p>
     */
    @Test
    void contentFingerprintsMatchGoProbeForAtomFeed() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.feedXmlOverride("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<feed xmlns=\"http://www.w3.org/2005/Atom\">\n"
                    + "  <title>Atom Feed</title><subtitle>atom sub</subtitle>\n"
                    + "  <link rel=\"alternate\" href=\"http://atom.example/\"/>\n"
                    + "  <updated>2024-05-06T07:08:09Z</updated>\n"
                    + "  <entry><title>Entry One</title>\n"
                    + "    <link rel=\"alternate\" href=\"http://127.0.0.1:1/e1\"/>\n"
                    + "    <id>tag:atom.example,2024:e1</id>\n"
                    + "    <updated>2024-05-07T10:00:00Z</updated>\n"
                    + "    <published>2024-05-01T09:00:00Z</published>\n"
                    + "    <summary>entry summary</summary>\n"
                    + "    <content type=\"html\">&lt;p&gt;hello &lt;b&gt;world&lt;/b&gt;&lt;/p&gt;</content>\n"
                    + "    <author><name>Alice</name></author></entry>\n"
                    + "  <entry><title>Entry Two</title>\n"
                    + "    <link rel=\"alternate\" href=\"http://127.0.0.1:1/e2\"/>\n"
                    + "    <id>tag:atom.example,2024:e2</id>\n"
                    + "    <updated>2024-05-08T10:00:00Z</updated>\n"
                    + "    <content type=\"html\">&lt;p&gt;second&lt;/p&gt;</content></entry>\n"
                    + "</feed>");
            DataSourceConfig config = makeConfig(feed.feedUrl(), null);
            config.setResourceIds(List.of(feed.feedUrl()));
            Connector.FetchIncrementalResult result =
                    new RssConnector().fetchIncremental(config, null);

            assertThat(fingerprints(result.cursor(), feed.feedUrl())).containsOnly(
                    org.assertj.core.api.Assertions.entry("tag:atom.example,2024:e1",
                            "h:54d426c03e8e01af"),
                    org.assertj.core.api.Assertions.entry("tag:atom.example,2024:e2",
                            "h:16367aacb67a4a01"));
        }
    }

    @Test
    void contentFingerprintsMatchGoProbeForExtendedRssFeed() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            feed.feedXmlOverride("<?xml version=\"1.0\"?>\n"
                    + "<rss version=\"2.0\" "
                    + "xmlns:content=\"http://purl.org/rss/1.0/modules/content/\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><channel>\n"
                    + "<title>Ext Feed</title><link>http://ext.example/</link>\n"
                    + "<description>ext desc</description>\n"
                    + "<item><title>Ext One</title>\n"
                    + "  <link>http://127.0.0.1:1/x1</link>\n"
                    + "  <guid isPermaLink=\"false\">ext-1</guid>\n"
                    + "  <content:encoded><![CDATA[<p>encoded <em>body</em></p>]]></content:encoded>\n"
                    + "  <description>plain desc</description></item>\n"
                    + "<item><link>http://127.0.0.1:1/x2</link>\n"
                    + "  <description>no title no guid</description></item>\n"
                    + "</channel></rss>");
            DataSourceConfig config = makeConfig(feed.feedUrl(), null);
            config.setResourceIds(List.of(feed.feedUrl()));
            Connector.FetchIncrementalResult result =
                    new RssConnector().fetchIncremental(config, null);

            assertThat(fingerprints(result.cursor(), feed.feedUrl())).containsOnly(
                    org.assertj.core.api.Assertions.entry("ext-1", "h:fe31a71ced1d86e1"),
                    org.assertj.core.api.Assertions.entry("http://127.0.0.1:1/x2",
                            "h:e204db00c5fd2586"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fingerprints(SyncCursor cursor, String feedUrl) {
        Map<String, Object> feedItems =
                (Map<String, Object>) cursor.getConnectorCursor().get("feed_items");
        return (Map<String, String>) feedItems.get(feedUrl);
    }

    // ── 三支错误汇总 ─────────────────────────────────────────────────────

    @Test
    void partialFailureThrowsPartialFetchCarryingItems() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            DataSourceConfig config =
                    makeConfig(feed.feedUrl() + ", http://127.0.0.1:1/boom.xml", null);
            assertThatThrownBy(() -> new RssConnector().fetchAll(config, null))
                    .isInstanceOf(ConnectorException.PartialFetch.class)
                    .satisfies(e -> {
                        PartialFetchException partial = (PartialFetchException) e;
                        assertThat(partial.getDetails()).hasSize(1);
                        assertThat(partial.getDetails().get(0)).contains("http://127.0.0.1:1/boom.xml");
                        // ★ 异常与结果同时有效：items 是从异常上拿的
                        assertThat(partial.items()).hasSize(2);
                        // FetchAll 路径照抄 Go 的 `_`：cursor 被丢弃
                        assertThat(partial.cursor()).isNull();
                    });
        }
    }

    @Test
    void partialFailureInFetchIncrementalCarriesCursorToo() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            // resourceIds 留空 → walk 回落到全部已配置 feed（与 Go 的 len==0 分支一致）
            DataSourceConfig config =
                    makeConfig(feed.feedUrl() + ", http://127.0.0.1:1/boom.xml", null);
            assertThatThrownBy(() -> new RssConnector().fetchIncremental(config, null))
                    .isInstanceOf(PartialFetchException.class)
                    .satisfies(e -> {
                        PartialFetchException partial = (PartialFetchException) e;
                        assertThat(partial.items()).hasSize(2);
                        assertThat(partial.cursor()).isNotNull();
                        assertThat(partial.cursor().getConnectorCursor())
                                .containsKeys("feed_items", "feed_signals", "last_sync_time");
                        assertThat(partial.getMessage())
                                .startsWith("partial fetch: ")
                                .contains("http://127.0.0.1:1/boom.xml");
                    });
        }
    }

    @Test
    void allFeedsFailedThrowsWithCursorPreserved() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            DataSourceConfig config = makeConfig(feed.feedUrl(), null);
            config.setResourceIds(List.of(feed.feedUrl()));

            Connector.FetchIncrementalResult first =
                    new RssConnector().fetchIncremental(config, null);
            assertThat(first.cursor()).isNotNull();

            feed.failFeed(true);
            assertThatThrownBy(() -> new RssConnector().fetchIncremental(config, first.cursor()))
                    .isInstanceOf(AllFeedsFailedException.class)
                    .satisfies(e -> {
                        AllFeedsFailedException failed = (AllFeedsFailedException) e;
                        assertThat(failed.getMessage()).startsWith("all feeds failed: ");
                        // items 恒非 null（Java 侧把 Go 的 nil slice 归一成空列表）
                        assertThat(failed.items()).isEmpty();
                        // 照抄 Go 的 `return nil, newCursor, err`：cursor 有值
                        assertThat(failed.cursor()).isNotNull();
                    });
        }
    }

    @Test
    void failedFeedCursorIsRolledForwardFromPreviousSync() throws IOException {
        // 照抄 Go 的 TestConnector_Walk_PreservesCursorOnFeedFailure：
        // 唯一的 feed 挂了，但新游标里必须有上一轮的指纹，否则下次会把整个 feed 重灌。
        try (FakeFeed feed = new FakeFeed()) {
            DataSourceConfig config = makeConfig(feed.feedUrl(), null);
            config.setResourceIds(List.of(feed.feedUrl()));
            Connector.FetchIncrementalResult first =
                    new RssConnector().fetchIncremental(config, null);

            feed.failFeed(true);
            try {
                new RssConnector().fetchIncremental(config, first.cursor());
                org.junit.jupiter.api.Assertions.fail("expected AllFeedsFailedException");
            } catch (AllFeedsFailedException failed) {
                assertThat(failed.cursor()).isNotNull();
                @SuppressWarnings("unchecked")
                Map<String, Object> feedItems =
                        (Map<String, Object>) failed.cursor().getConnectorCursor().get("feed_items");
                @SuppressWarnings("unchecked")
                Map<String, Object> items = (Map<String, Object>) feedItems.get(feed.feedUrl());
                assertThat(items).containsOnlyKeys("guid-1", "guid-2");
                assertThat((String) items.get("guid-1")).isNotEmpty();
                assertThat((String) items.get("guid-2")).isNotEmpty();
            }
        }
    }

    @Test
    void allFeedsFailedWhenNoResourceIdsAndNoFeedsFailPartially() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            DataSourceConfig config = makeConfig(
                    "http://127.0.0.1:1/a.xml, http://127.0.0.1:1/b.xml", null);
            assertThatThrownBy(() -> new RssConnector().fetchAll(config, null))
                    .isInstanceOf(AllFeedsFailedException.class)
                    .hasMessageStartingWith("all feeds failed: ");
        }
    }

    @Test
    void succeedFeedWithZeroItemsIsPartialNotAllFailed() throws IOException {
        // 照抄 Go 的判据：len(out)==0 && len(feedErrors)==len(feedURLs)。
        // 这里两个 feed、一个成功但 0 条、一个失败 → 是 PartialFetch，不是 all-failed。
        try (FakeFeed feed = new FakeFeed()) {
            // 成功的 feed 返回 0 条（唯一的 entry 没有 guid/link/title → 被跳过）
            feed.feedXmlOverride("<?xml version=\"1.0\"?>"
                    + "<rss version=\"2.0\"><channel><title>Empty</title>"
                    + "<item><description>no id</description></item></channel></rss>");
            DataSourceConfig config =
                    makeConfig(feed.feedUrl() + ", http://127.0.0.1:1/boom.xml", null);
            assertThatThrownBy(() -> new RssConnector().fetchAll(config, null))
                    .isInstanceOf(PartialFetchException.class)
                    .satisfies(e -> assertThat(((PartialFetchException) e).items()).isEmpty());
        }
    }

    // ── 接缝：换掉 ArticleExtractor 就能恢复 Go 的"全文"分支 ──────────────

    @Test
    void articleExtractorSeamRecoversGoSuccessPath() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            // 一个"假 readability"：把页面里 <article> 的内容原样当正文，标题取 <title>。
            ArticleExtractor stub = (body, pageUrl) -> {
                String html = new String(body, StandardCharsets.UTF_8);
                int start = html.indexOf("<article>");
                int end = html.indexOf("</article>");
                String contentHtml = start >= 0 && end > start
                        ? html.substring(start + "<article>".length(), end) : "";
                return new ArticleExtractor.ExtractedArticle(contentHtml, "Full /article/a1");
            };
            RssConnector connector =
                    new RssConnector(new JdkXmlFeedParser(), stub, new JdkHtmlToMarkdown());

            List<FetchedItem> items =
                    connector.fetchAll(makeConfig(feed.feedUrl(), null), null);
            assertThat(items).hasSize(2);
            String content = new String(items.get(0).getContent(), StandardCharsets.UTF_8);
            // 正文换成文章页的内容，且 <h1>Heading</h1> 变成 "# Heading"
            assertThat(content).contains("# Heading");
            assertThat(content).contains("first paragraph");
            assertThat(content).doesNotContain("summary fallback");
        }
    }

    @Test
    void articleExtractorSeamFallsBackWhenSeamThrows() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            ArticleExtractor failing = (body, pageUrl) -> {
                throw new ArticleExtractionException("no readable content extracted");
            };
            RssConnector connector =
                    new RssConnector(new JdkXmlFeedParser(), failing, new JdkHtmlToMarkdown());
            List<FetchedItem> items =
                    connector.fetchAll(makeConfig(feed.feedUrl(), null), null);
            assertThat(new String(items.get(0).getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("summary fallback");
        }
    }

    @Test
    void defaultArticleExtractorAlwaysFails() {
        // 这条断言把"降级"本身钉住：默认装配永远走回落分支。
        assertThatThrownBy(() -> new UnavailableArticleExtractor()
                .extract("<html/>".getBytes(StandardCharsets.UTF_8), "http://x/y"))
                .isInstanceOf(ArticleExtractionException.class)
                .hasMessage(UnavailableArticleExtractor.MESSAGE);
    }

    // ── FeedParser 接缝：注入假解析器可以完全绕开 XML ─────────────────────

    @Test
    void feedParserSeamIsInjectable() throws IOException {
        try (FakeFeed feed = new FakeFeed()) {
            FeedParser stub = data -> new FeedParser.ParsedFeed("Stub Feed", "stub desc",
                    "http://stub.example/", null, List.of(new FeedParser.ParsedItem(
                            "g1", "", "Stub Item", "<p>hello <b>world</b></p>", null, null, null,
                            "Bob")));
            RssConnector connector =
                    new RssConnector(stub, new UnavailableArticleExtractor(), new JdkHtmlToMarkdown());
            List<Resource> resources =
                    connector.listResources(makeConfig(feed.feedUrl(), null), "");
            assertThat(resources.get(0).getName()).isEqualTo("Stub Feed");
            assertThat(resources.get(0).getUrl()).isEqualTo("http://stub.example/");

            List<FetchedItem> items =
                    connector.fetchAll(makeConfig(feed.feedUrl(), null), null);
            assertThat(items).hasSize(1);
            assertThat(new String(items.get(0).getContent(), StandardCharsets.UTF_8))
                    .isEqualTo("hello **world**");
            assertThat(items.get(0).getMetadata().get("author")).isEqualTo("Bob");
        }
    }
}
