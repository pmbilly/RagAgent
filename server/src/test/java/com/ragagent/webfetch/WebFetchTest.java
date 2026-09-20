package com.ragagent.webfetch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.common.security.SsrfGuard;
import com.sun.net.httpserver.HttpServer;

/**
 * web_fetch 的 stub server 测试（对照 Go fetcher_test.go / fetcher_ssrf_test.go /
 * markdown_test.go 的可移植用例；chromedp / readability / html-to-markdown 是接缝，
 * 用例按 Go 侧"接缝失败"分支的形态断言）。
 */
class WebFetchTest {

    private static String originalWhitelist = "unset";

    @BeforeAll
    static void whitelistOn() {
        new SsrfGuard().reloadWhitelist("127.0.0.1,localhost");
    }

    /** §7.8：白名单 @AfterAll 还原。 */
    @AfterAll
    static void whitelistOff() {
        new SsrfGuard().reloadWhitelist("");
    }

    static HttpServer start(com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler);
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String body(String html) {
        return html;
    }

    // ── 成功 / 内容抽取 ─────────────────────────────────────────────

    @Test
    void pipelineFetchExtractsVisibleText() {
        HttpServer server = start(ex -> {
            byte[] out = body("<html><body><main>official specifications</main></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            String content = Fetcher.newPipelineFetcher()
                    .fetch("http://127.0.0.1:" + server.getAddress().getPort());
            assertTrue(content.contains("official specifications"), content);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agentMarkdownKeepsVerbatimTextForNonHtml() {
        HttpServer server = start(ex -> {
            byte[] out = "if (a < b)\n<available_files>keep me</available_files>"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            // 对照 Go TestAgentFetchContentTypesAndSizeLimit/plain：markdown 模式下
            // text/* 原样透传
            Fetcher f = new Fetcher(true, java.time.Duration.ofSeconds(5), 10_000, null);
            String content = f.fetch("http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("if (a < b)\n<available_files>keep me</available_files>", content);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pipelineHtmlToTextKeepsLiteralAngleBrackets() {
        HttpServer server = start(ex -> {
            byte[] out = "if (a < b)\n<available_files>keep me</available_files>"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            // Go 实测（htmlToText probe）："if (a < b)\nkeep me"
            String content = Fetcher.newPipelineFetcher()
                    .fetch("http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("if (a < b)\nkeep me", content);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void markdownFetcherPassesPlainTextThroughAndEnforcesLimit() {
        HttpServer server = start(ex -> {
            byte[] out = "created page".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(201, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            Fetcher f = new Fetcher(true, java.time.Duration.ofSeconds(5), 1024, null);
            String got = f.fetch("http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("created page", got, "markdown 模式接受 2xx（对照 Go）");
        } finally {
            server.stop(0);
        }

        // 大小上限（markdown 模式 100KB+1 截断再判超限）
        HttpServer big = start(ex -> {
            byte[] out = "x".repeat(1025).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            Fetcher f = new Fetcher(true, java.time.Duration.ofSeconds(5), 1024, null);
            FetchException err = assertThrows(FetchException.class, () -> f
                    .fetch("http://127.0.0.1:" + big.getAddress().getPort()));
            assertEquals(FetchException.Code.BODY_TOO_LARGE, err.getCode());
            assertFalse(err.isRetryable());
            assertTrue(err.getMessage().contains("download limit"));
        } finally {
            big.stop(0);
        }
    }

    @Test
    void markdownFetcherRejectsBinaryAndUnsupportedTypes() {
        // NUL 字节 → not UTF-8 text
        HttpServer binary = start(ex -> {
            byte[] out = new byte[] {'a', 0, 'b'};
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            Fetcher f = new Fetcher(true, java.time.Duration.ofSeconds(5), 10_000, null);
            FetchException err = assertThrows(FetchException.class, () -> f
                    .fetch("http://127.0.0.1:" + binary.getAddress().getPort()));
            assertEquals(FetchException.Code.UNSUPPORTED_CONTENT, err.getCode());
            assertTrue(err.getMessage().contains("not UTF-8 text"));
        } finally {
            binary.stop(0);
        }

        // application/pdf → unsupported content type
        HttpServer pdf = start(ex -> {
            byte[] out = "%PDF-1.7 binary".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/pdf");
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            Fetcher f = new Fetcher(true, java.time.Duration.ofSeconds(5), 10_000, null);
            FetchException err = assertThrows(FetchException.class, () -> f
                    .fetch("http://127.0.0.1:" + pdf.getAddress().getPort()));
            assertEquals(FetchException.Code.UNSUPPORTED_CONTENT, err.getCode());
            assertTrue(err.getMessage().contains("application/pdf"));
        } finally {
            pdf.stop(0);
        }
    }

    // ── 状态码 / 空内容分类 ─────────────────────────────────────────

    @Test
    void classifiesHttpStatusCodes() {
        for (int[] tc : new int[][] {{403, 0}, {429, 1}, {503, 1}}) {
            HttpServer server = start(ex -> ex.sendResponseHeaders(tc[0], -1));
            try {
                Fetcher f = new Fetcher(true, java.time.Duration.ofSeconds(5), 10_000, null);
                FetchException err = assertThrows(FetchException.class, () -> f
                        .fetch("http://127.0.0.1:" + server.getAddress().getPort()));
                FetchException.Code expected = switch (tc[0]) {
                    case 403 -> FetchException.Code.HTTP_403;
                    case 429 -> FetchException.Code.HTTP_429;
                    default -> FetchException.Code.HTTP_5XX;
                };
                assertEquals(expected, err.getCode(), "status " + tc[0]);
                assertEquals(tc[1] == 1, err.isRetryable(), "status " + tc[0]);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void rejectsEmptyContentWithUnavailableBrowserSeam() {
        HttpServer server = start(ex -> {
            byte[] out = "<html><body><script>ignored()</script></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            // 新 Fetcher（带默认不可用浏览器接缝）：SPA 判定/空内容都先试浏览器后回落
            Fetcher f = Fetcher.newFetcher();
            FetchException err = assertThrows(FetchException.class, () -> f
                    .fetch("http://127.0.0.1:" + server.getAddress().getPort()));
            assertEquals(FetchException.Code.EMPTY_CONTENT, err.getCode());
            assertFalse(err.isRetryable());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void browserSeamRecoversClientRenderedPage() {
        HttpServer server = start(ex -> {
            byte[] out = ("<html><body><div id=\"app\">Loading...</div>"
                    + "<script>render()</script></body></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(200, out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            // 接缝成功 → 返回渲染后的 Markdown
            Fetcher ok = new Fetcher(true, java.time.Duration.ofSeconds(5), 2_000_000,
                    url -> "<html><body><main>rendered product specifications</main></body></html>");
            String content = ok.fetch(base);
            assertTrue(content.contains("rendered product specifications"), content);

            // 接缝失败 → empty_content（对照 Go TestFetcherReturnsErrorWhenBrowserFallbackFailsOnSPA）
            Fetcher bad = new Fetcher(true, java.time.Duration.ofSeconds(5), 2_000_000,
                    url -> {
                        throw new IllegalStateException("browser unavailable");
                    });
            FetchException err = assertThrows(FetchException.class, () -> bad.fetch(base));
            assertEquals(FetchException.Code.EMPTY_CONTENT, err.getCode());
        } finally {
            server.stop(0);
        }
    }

    // ── 无效 URL / SSRF / 重定向 ───────────────────────────────────

    @Test
    void classifiesInvalidAndSsrfUrls() {
        Fetcher f = Fetcher.newFetcher();
        FetchException invalid = assertThrows(FetchException.class,
                () -> f.fetch("not-a-url"));
        assertEquals(FetchException.Code.INVALID_URL, invalid.getCode());
        assertFalse(invalid.isRetryable());

        // 127.0.0.1 在白名单（本套件放开）——换成非白名单受限主机
        SsrfGuard guard = new SsrfGuard();
        try {
            guard.reloadWhitelist("localhost");
            FetchException ssrf = assertThrows(FetchException.class,
                    () -> f.fetch("http://127.0.0.1:1/private"));
            assertEquals(FetchException.Code.SSRF_REJECTED, ssrf.getCode());
            assertFalse(ssrf.isRetryable());
        } finally {
            guard.reloadWhitelist("127.0.0.1,localhost");
        }
    }

    @Test
    void redirectsToRestrictedHostAreBlocked() {
        // 受限主机（localhost 白名单外）作为重定向目标 → redirect_rejected
        HttpServer attacker = start(ex -> {
            ex.getResponseHeaders().set("Location", "http://host.invalid:1/private");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        try {
            Fetcher f = Fetcher.newPipelineFetcher();
            FetchException err = assertThrows(FetchException.class, () -> f
                    .fetch("http://127.0.0.1:" + attacker.getAddress().getPort() + "/entry"));
            assertEquals(FetchException.Code.REDIRECT_REJECTED, err.getCode());
            assertFalse(err.isRetryable());
        } finally {
            attacker.stop(0);
        }
    }

    @Test
    void invalidUrlShapes() {
        Fetcher f = Fetcher.newPipelineFetcher();
        FetchException empty = assertThrows(FetchException.class, () -> f.fetch("   "));
        assertEquals(FetchException.Code.INVALID_URL, empty.getCode());
        assertTrue(empty.getMessage().contains("url is empty"));
    }

    // ── agent markdown（接缝下的前/后处理）─────────────────────────

    @Test
    void agentMarkdownResolvesLinksAndDropsNav() {
        String source = "<html><head><title>Reference</title></head>"
                + "<body><nav>NOISE</nav>"
                + "<main><h1>Guide</h1><p>Read <a href=\"../next\">next page</a>.</p>"
                + "<ul><li>First</li><li>Second</li></ul>"
                + "<pre><code>if (a &lt; b) { return a; }</code></pre>"
                + "<a href=\"https://example.com/x\"></a>"
                + "</main></body></html>";
        String got = AgentMarkdown.htmlToMarkdown(source, "https://example.com/docs/guide");
        assertTrue(got.startsWith("# Reference"), got);
        assertTrue(got.contains("# Guide"), got);
        assertTrue(got.contains("[next page](https://example.com/next)"), got);
        assertTrue(got.contains("First"), got);
        assertTrue(got.contains("if (a < b)"), got);
        assertFalse(got.contains("NOISE"), got);
    }

    @Test
    void agentMarkdownTitleFallsBackWhenNoTitle() {
        String source = "<html><body><article><h2>Only content</h2></article></body></html>";
        String got = AgentMarkdown.htmlToMarkdown(source, "https://example.com/post");
        assertFalse(got.startsWith("# "), got);
        assertTrue(got.contains("Only content"), got);
    }

    @Test
    void htmlTextHelpers() {
        assertTrue(Fetcher.isHTMLContent("text/html"));
        assertTrue(Fetcher.isHTMLContent("application/xhtml+xml"));
        assertFalse(Fetcher.isHTMLContent("text/plain"));
        assertTrue(Fetcher.isValidUtf8("中文".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Fetcher.isValidUtf8(new byte[] {(byte) 0xff, (byte) 0xfe}));
        assertEquals("text/html; charset=utf-8", Fetcher.detectContentType(
                "<!DOCTYPE html><html>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void needsBrowserFallbackHeuristics() {
        assertTrue(Fetcher.needsBrowserFallback("", new byte[0]));
        assertTrue(Fetcher.needsBrowserFallback("Please enable javascript", new byte[0]));
        assertTrue(Fetcher.needsBrowserFallback("Loading...", new byte[0]));
        assertFalse(Fetcher.needsBrowserFallback("x".repeat(200), new byte[0]));
        String spa = "<div id=\"app\"></div><script>x</script>";
        assertTrue(Fetcher.needsBrowserFallback("short", spa.getBytes(StandardCharsets.UTF_8)));
        String noApp = "<div>hello</div><script>x</script>";
        assertFalse(Fetcher.needsBrowserFallback("short", noApp.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void factoryDefaultsMatchGo() {
        Fetcher agent = Fetcher.newFetcher();
        assertEquals(60_000, agent.timeout.toMillis());
        assertEquals(2 * 1024 * 1024, agent.maxBodySize);
        assertTrue(agent.markdown);
        assertNotNull(agent.renderBrowser);

        Fetcher pipeline = Fetcher.newPipelineFetcher();
        assertEquals(15_000, pipeline.timeout.toMillis());
        assertEquals(100 * 1024, pipeline.maxBodySize);
        assertFalse(pipeline.markdown);
        assertNull(pipeline.renderBrowser);
    }
}
