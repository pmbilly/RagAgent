package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.retrieval.domain.WebSearchResult;

/**
 * web_search / web_fetch 工具钉（2026-09-23 接线批·切片 2d，对照 Go
 * {@code web_search.go / web_fetch.go}）。
 *
 * <p>此前 {@code registerTools} 对这两个名字只落 {@code default → "Unknown tool"}。
 * schema 与描述按 Go 实录逐字节钉住（GenerateSchema[WebSearchInput/WebFetchInput]
 * 与 fmt.Sprintf 注入后的描述）；行为用 stub fetcher/backend 实弹。</p>
 */
class WebToolsRecordingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // Go 实录（独立程序GenerateSchema 输出原字节 + 描述模板）
    // ------------------------------------------------------------------

    private static final String GO_SEARCH_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Search query string\"},"
                    + "\"count\":{\"type\":[\"null\",\"integer\"],\"description\":\"1 to configured maximum (at most 20)\"},"
                    + "\"country\":{\"type\":\"string\",\"description\":\"Two-letter code or ALL; omit for provider default; requires Brave\"},"
                    + "\"freshness\":{\"type\":\"string\",\"description\":\"pd/pw/pm/py or YYYY-MM-DDtoYYYY-MM-DD (Brave)\"},"
                    + "\"content\":{\"type\":\"boolean\",\"description\":\"Fetch page excerpts; default false\"}},"
                    + "\"required\":[\"query\"],\"additionalProperties\":false}";

    private static final String GO_FETCH_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":[\"null\",\"array\"],"
                    + "\"items\":{\"type\":\"object\",\"properties\":{"
                    + "\"url\":{\"type\":\"string\",\"description\":\"wN page ID or absolute HTTP(S) URL\"},"
                    + "\"offset\":{\"type\":\"integer\",\"description\":\"Zero-based character offset; use next_offset to continue\"},"
                    + "\"limit\":{\"type\":\"integer\",\"description\":\"Maximum characters to return, from 1 to 8000; default 8000\"}},"
                    + "\"required\":[\"url\"],\"additionalProperties\":false},"
                    + "\"description\":\"One to eight reads with url, optional offset and limit\"}},"
                    + "\"required\":[\"items\"],\"additionalProperties\":false}";

    @Test
    void schemasMatchGoRecording() throws Exception {
        WebFetchTool fetch = new WebFetchTool();
        WebSearchTool search = new WebSearchTool(
                (tenantId, providerId, config, query) -> List.of(), 10, "p", () -> 10002L, null);
        // schema 的 JsonNode toString 与 Go RAW 逐字节一致（同序同形）
        assertThat(fetch.getParameters().toString()).isEqualTo(GO_FETCH_SCHEMA);
        assertThat(search.getParameters().toString()).isEqualTo(GO_SEARCH_SCHEMA);
        // 解析重排后仍然同构（防实现里改键序）
        assertThat(MAPPER.readTree(fetch.getParameters().toString()))
                .isEqualTo(MAPPER.readTree(GO_FETCH_SCHEMA));
        assertThat(MAPPER.readTree(search.getParameters().toString()))
                .isEqualTo(MAPPER.readTree(GO_SEARCH_SCHEMA));
        assertThat(fetch.getName()).isEqualTo("web_fetch");
        assertThat(search.getName()).isEqualTo("web_search");
    }

    @Test
    void searchDescriptionInjectsMaxResults() {
        WebSearchTool def = new WebSearchTool(
                (t, p, c, q) -> List.of(), 0, "p", () -> 1L, null);
        assertThat(def.getDescription()).contains("Returns up to 10 results");
        WebSearchTool narrowed = new WebSearchTool(
                (t, p, c, q) -> List.of(), 3, "p", () -> 1L, null);
        assertThat(narrowed.getDescription()).contains("Returns up to 3 results");
        // clamp 到 20
        WebSearchTool clamped = new WebSearchTool(
                (t, p, c, q) -> List.of(), 25, "p", () -> 1L, null);
        assertThat(clamped.getDescription()).contains("Returns up to 20 results");
        // 模板只有一个 %d，格式化后不再有未替换的动词
        assertThat(clamped.getDescription()).doesNotContain("%d");
    }

    // ------------------------------------------------------------------
    // web_fetch：批执行 / 续读 / 缓存 / 去重（stub fetcher 实弹）
    // ------------------------------------------------------------------

    private static WebFetchTool fetchTool(Function<String, String> fn) {
        return new WebFetchTool(fn::apply);
    }

    @Test
    void fetchBatchSuccessAndContinuation() {
        StringBuilder big = new StringBuilder();
        big.append("a".repeat(30));
        WebFetchTool tool = fetchTool(url -> big.toString());
        ToolResult r1 = tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/a\",\"limit\":10}]}")));
        assertThat(r1.isSuccess()).isTrue();
        assertThat(r1.getOutput()).contains("URL: https://e.com/a\nStatus: success\n");
        assertThat(r1.getOutput()).contains("Characters: 0-10 of 30\n");
        assertThat(r1.getOutput()).contains("Truncated; continue with the same url and offset=10.\n");
        Map<String, Object> item = dataItem(r1, 0);
        assertThat(item.get("next_offset")).isEqualTo(10);
        assertThat(item.get("truncated")).isEqualTo(true);
        assertThat(item.get("content_length")).isEqualTo(30);
        assertThat(item.get("returned_chars")).isEqualTo(10);
        assertThat(item.get("evidence_type")).isEqualTo("fetched_page");
        assertThat(item.get("page_verified")).isNull();

        ToolResult r2 = tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/a\",\"offset\":10,\"limit\":10}]}")));
        assertThat(r2.getOutput()).contains("Characters: 10-20 of 30\n");
        assertThat(dataItem(r2, 0).get("next_offset")).isEqualTo(20);
    }

    @Test
    void fetchValidationErrorsMatchGoTexts() {
        WebFetchTool tool = fetchTool(url -> "x");
        // 批大小
        assertThat(tool.execute(ToolRequest.of(json("{\"items\":[]}"))).getError())
                .isEqualTo("items must contain 1 to 8 page reads");
        assertThat(tool.execute(ToolRequest.of(json("{}"))).getError())
                .isEqualTo("items must contain 1 to 8 page reads");
        // 非法 URL
        ToolResult bad = tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"not-a-url\"}]}")));
        assertThat(bad.isSuccess()).isFalse();
        Map<String, Object> item = dataItem(bad, 0);
        assertThat(item.get("status")).isEqualTo("failed");
        assertThat(item.get("error_code")).isEqualTo("invalid_url");
        assertThat(item.get("error_message"))
                .isEqualTo("url must be a known wN page ID or an absolute HTTP(S) URL");
        // 非法 limit
        ToolResult badLimit = tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/a\",\"limit\":8001}]}")));
        assertThat(dataItem(badLimit, 0).get("error_code")).isEqualTo("invalid_arguments");
        // offset 越界（先 offset-0 热缓存——Go 的 readPage 先于 content_length 校验，
        // 未缓存续读会先报 snapshot_expired）
        WebFetchTool small = fetchTool(url -> "abc");
        small.execute(ToolRequest.of(json("{\"items\":[{\"url\":\"https://e.com/a\"}]}")));
        ToolResult over = small.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/a\",\"offset\":5}]}")));
        assertThat(dataItem(over, 0).get("error_message"))
                .isEqualTo("offset must be less than content_length 3");
        // 预算不足
        ToolResult poor = tool.execute(new ToolRequest(json(
                "{\"items\":[{\"url\":\"https://e.com/a\"}]}"), null, ToolCancellation.LIVE, 1200));
        assertThat(poor.getError())
                .isEqualTo("batch exceeds the output budget; request fewer pages per call");
    }

    @Test
    void fetchDedupsCanonicalUrlsAndReportsSkipped() {
        AtomicInteger calls = new AtomicInteger();
        WebFetchTool tool = fetchTool(url -> {
            calls.incrementAndGet();
            return "content";
        });
        ToolResult r = tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/a#frag\"},{\"url\":\"https://e.com/a\"}]}")));
        // 同 canonical URL 的第二项被跳过，只下载一次
        assertThat(calls.get()).isEqualTo(1);
        assertThat(dataItem(r, 0).get("status")).isEqualTo("success");
        Map<String, Object> skipped = dataItem(r, 1);
        assertThat(skipped.get("status")).isEqualTo("skipped");
        assertThat(skipped.get("error_code")).isEqualTo("duplicate_url");
        assertThat(r.getOutput()).contains("Reason: duplicate URL skipped in this batch");
    }

    @Test
    void fetchUnwrapsDoubleEncodedItems() {
        WebFetchTool tool = fetchTool(url -> "page");
        String inner = "[{\"url\":\"https://e.com/a\"}]";
        ToolResult r = tool.execute(ToolRequest.of(
                json("{\"items\":" + quote(inner) + "}")));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).contains("Status: success");
    }

    @Test
    void fetchEmptyContentFailsWithEmptyContentCode() {
        WebFetchTool tool = fetchTool(url -> "   \n  ");
        ToolResult r = tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/a\"}]}")));
        Map<String, Object> item = dataItem(r, 0);
        assertThat(item.get("error_code")).isEqualTo("empty_content");
        assertThat(item.get("error_message")).isEqualTo("page contains no readable content");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getError()).isEqualTo("all page fetches failed");
    }

    @Test
    void fetchCachesPerRunAndEvictsLruBeyond8() {
        // WebFetchTool 用虚拟线程并发抓 items，记录器须线程安全
        List<String> fetched = new CopyOnWriteArrayList<>();
        WebFetchTool tool = fetchTool(url -> {
            fetched.add(url);
            return "body of " + url;
        });
        for (int i = 1; i <= 5; i++) {
            tool.execute(ToolRequest.of(json(
                    "{\"items\":[{\"url\":\"https://e.com/p" + i + "\"}]}")));
        }
        tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://E.com/p1\"}]}")));
        // host 小写归一后命中缓存（不重复下载）
        assertThat(fetched).hasSize(5);
        for (int i = 6; i <= 9; i++) {
            tool.execute(ToolRequest.of(json(
                    "{\"items\":[{\"url\":\"https://e.com/p" + i + "\"}]}")));
        }
        assertThat(fetched).hasSize(9);
        // p1 在第 9 次抓取时被 LRU 逐出 → 再读 p1 重新下载
        tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/p1\"}]}")));
        assertThat(fetched).hasSize(10);
    }

    @Test
    void continuationOnUncachedUrlReportsSnapshotExpired() {
        WebFetchTool tool = fetchTool(url -> "page");
        ToolResult r = tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/a\",\"offset\":10}]}")));
        Map<String, Object> item = dataItem(r, 0);
        assertThat(item.get("error_code")).isEqualTo("snapshot_expired");
        assertThat(item.get("retryable")).isEqualTo(true);
        assertThat(item.get("error_message")).isEqualTo(
                "snapshot unavailable; use read_file on full_output_path or restart at offset 0");
    }

    @Test
    void sameBatchContinuationRunsAfterFirstPopulatesSnapshot() {
        AtomicInteger calls = new AtomicInteger();
        WebFetchTool tool = fetchTool(url -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "0123456789abcdef";
        });
        // 同批：offset-0 先行阶段填快照（慢下载一次），续读阶段命中缓存
        ToolResult r = tool.execute(ToolRequest.of(json(
                "{\"items\":[{\"url\":\"https://e.com/a\",\"limit\":4},{\"url\":\"https://e.com/a\",\"offset\":4,\"limit\":4}]}")));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(dataItem(r, 0).get("status")).isEqualTo("success");
        assertThat(dataItem(r, 0).get("returned_chars")).isEqualTo(4);
        assertThat(dataItem(r, 1).get("status")).isEqualTo("success");
        assertThat(dataItem(r, 1).get("returned_chars")).isEqualTo(4);
        assertThat(r.getOutput()).contains("Characters: 0-4 of 16\n");
        assertThat(r.getOutput()).contains("Characters: 4-8 of 16\n");
    }

    @Test
    void githubBlobUrlsNormalizeToRaw() {
        assertThat(WebFetchTool.normalizeGitHubURL(
                "https://github.com/owner/repo/blob/main/README.md"))
                .isEqualTo("https://raw.githubusercontent.com/owner/repo/main/README.md");
        assertThat(WebFetchTool.normalizeGitHubURL(
                "https://github.com/owner/repo/tree/main"))
                .isEqualTo("https://github.com/owner/repo/tree/main");
    }

    // ------------------------------------------------------------------
    // web_search：入参校验 / 结果整形 / content=true 前置抓取
    // ------------------------------------------------------------------

    private static WebSearchResult row(String title, String url, String snippet) {
        WebSearchResult r = new WebSearchResult();
        r.setTitle(title);
        r.setUrl(url);
        r.setSnippet(snippet);
        r.setSource("stub");
        return r;
    }

    @Test
    void searchRejectsBadInputLikeGo() {
        WebSearchTool tool = new WebSearchTool(
                (t, p, c, q) -> List.of(), 10, "p", () -> 10002L, null);
        assertThat(tool.execute(ToolRequest.of(json("{}"))).getError())
                .isEqualTo("query parameter is required");
        assertThat(tool.execute(ToolRequest.of(
                json("{\"query\":\"  \"}"))).getError())
                .isEqualTo("query parameter is required");
        assertThat(tool.execute(ToolRequest.of(
                json("{\"query\":\"q\",\"count\":0}"))).getError())
                .isEqualTo("count must be between 1 and 10");
        assertThat(tool.execute(ToolRequest.of(
                json("{\"query\":\"q\",\"count\":11}"))).getError())
                .isEqualTo("count must be between 1 and 10");
        assertThat(tool.execute(ToolRequest.of(
                json("{\"query\":\"q\",\"freshness\":\"nope\"}"))).getError())
                .isEqualTo("freshness must be pd, pw, pm, py, or YYYY-MM-DDtoYYYY-MM-DD with start <= end");
        // 无租户
        WebSearchTool noTenant = new WebSearchTool(
                (t, p, c, q) -> List.of(), 10, "p", () -> 0L, null);
        assertThat(noTenant.execute(ToolRequest.of(
                json("{\"query\":\"q\"}"))).getError())
                .isEqualTo("workspace ID not found in context");
    }

    @Test
    void searchFormatsResultsAndDedupes() {
        List<WebSearchResult> backend = List.of(
                row("T1", "https://e.com/one", "s1"),
                row("Dup", "https://e.com/one#x", "dup"),
                row("Dirty", "ftp://bad.example/", "bad"),
                row("T2", "https://e.com/two", "s2"));
        WebSearchTool tool = new WebSearchTool(
                (t, p, c, q) -> backend, 10, "prov-1", () -> 10002L, null);
        ToolResult r = tool.execute(ToolRequest.of(json("{\"query\":\"  hello  \"}")));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).startsWith("=== Web Search Results ===\nQuery: hello\nFound 2 result(s)\n\n");
        assertThat(r.getOutput()).contains("Result #1:\n  Title: T1\n  URL: https://e.com/one\n  Snippet: s1\n");
        assertThat(r.getOutput()).contains("Result #2:\n  Title: T2\n");
        assertThat(r.getOutput()).endsWith(
                "\n=== Next Steps ===\n"
                        + "- Titles, URLs, snippets, and content snippets are usable search-summary evidence.\n"
                        + "- If the evidence is sufficient, answer now. Use web_fetch only for claims that need full-page verification.\n"
                        + "- If fetching fails, retain these results, disclose that page content was not verified, and avoid presenting dynamic facts as certain.\n");
        assertThat(r.getData().get("count")).isEqualTo(2);
        assertThat(r.getData().get("display_type")).isEqualTo("web_search_results");
        assertThat(r.getData().get("query")).isEqualTo("hello");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.getData().get("results");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("result_index")).isEqualTo(1);
        assertThat(rows.get(0).get("evidence_type")).isEqualTo("search_summary");
        assertThat(rows.get(0).get("page_verified")).isEqualTo(false);
    }

    @Test
    void searchEmptyResultsFormat() {
        WebSearchTool tool = new WebSearchTool(
                (t, p, c, q) -> List.of(), 10, "p", () -> 10002L, null);
        ToolResult r = tool.execute(ToolRequest.of(json("{\"query\":\"q\"}")));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getOutput()).isEqualTo("No web search results found for query: q");
        assertThat(r.getData().get("count")).isEqualTo(0);
        assertThat(r.getData().get("results")).isEqualTo(List.of());
    }

    @Test
    void searchBackendFailureFoldsIntoError() {
        WebSearchTool tool = new WebSearchTool(
                (t, p, c, q) -> {
                    throw new IllegalStateException("no web search provider configured");
                }, 10, "p", () -> 10002L, null);
        ToolResult r = tool.execute(ToolRequest.of(json("{\"query\":\"q\"}")));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getError()).isEqualTo("web search failed: no web search provider configured");
    }

    @Test
    void searchWithContentFetchesLeadingPagesViaSharedFetchTool() {
        // 前 3 页由 WebSearchTool 的虚拟线程并发抓取，记录器须线程安全
        List<String> fetchedUrls = new CopyOnWriteArrayList<>();
        WebSearchTool tool = new WebSearchTool(
                (t, p, c, q) -> List.of(
                        row("A", "https://e.com/a", "sa"),
                        row("B", "https://e.com/b", "sb"),
                        row("C", "https://e.com/c", "sc"),
                        row("D", "https://e.com/d", "sd")),
                10, "p", () -> 10002L, null);
        tool.withPageReader(fetchTool(url -> {
            fetchedUrls.add(url);
            return url.endsWith("/d") ? "" : "page body " + url;
        }));
        ToolResult r = tool.execute(ToolRequest.of(json(
                "{\"query\":\"q\",\"content\":true}")));
        // 只抓前 3 条
        assertThat(fetchedUrls).hasSize(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.getData().get("results");
        assertThat(rows.get(0).get("page_status")).isEqualTo("success");
        assertThat(rows.get(0).get("page_verified")).isEqualTo(true);
        assertThat(rows.get(0).get("page_content")).isEqualTo("page body https://e.com/a");
        assertThat(rows.get(2).get("page_status")).isEqualTo("success");
        assertThat(rows.get(3).get("page_status")).isEqualTo("skipped");
        assertThat(rows.get(3).get("page_error"))
                .isEqualTo("content fetch is limited to the first 3 results; use web_fetch for more");
        assertThat(r.getOutput()).contains("Fetched content (untrusted): page body https://e.com/a\n");
        assertThat(r.getOutput()).contains("Page fetch skipped: use web_fetch for this result.\n");
        // 短内容：无截断；Go map 语义——success 页的派生键恒存在，缺席值即 null
        assertThat(rows.get(0).get("page_truncated")).isEqualTo(false);
        assertThat(rows.get(0).get("page_next_offset")).isNull();
        assertThat(rows.get(0).get("full_output_path")).isNull();
    }

    @Test
    void searchPublishedAtUsesRfc3339() {
        WebSearchResult withDate = row("T", "https://e.com/a", "s");
        withDate.setPublishedAt(java.time.OffsetDateTime.parse("2026-09-23T12:34:56.789+08:00"));
        WebSearchTool tool = new WebSearchTool(
                (t, p, c, q) -> List.of(withDate), 10, "p", () -> 10002L, null);
        ToolResult r = tool.execute(ToolRequest.of(json("{\"query\":\"q\"}")));
        // RFC3339 秒精度 + 偏移（Go 的 Format(time.RFC3339)）
        assertThat(r.getOutput()).contains("Published: 2026-09-23T12:34:56+08:00\n");
    }

    @Test
    void searchCountNarrowsResultsNotJustCaps() {
        WebSearchTool tool = new WebSearchTool(
                (t, p, c, q) -> List.of(
                        row("A", "https://e.com/a", "sa"),
                        row("B", "https://e.com/b", "sb")),
                10, "p", () -> 10002L, null);
        ToolResult r = tool.execute(ToolRequest.of(json("{\"query\":\"q\",\"count\":1}")));
        assertThat(r.getData().get("count")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataItem(ToolResult result, int index) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.getData().get("results");
        return rows.get(index);
    }
}
