package com.ragagent.webfetch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.llm.chat.LlmTransport;

/**
 * 公网页面抓取器（对照 Go {@code internal/infrastructure/web_fetch/fetcher.go} 全文）。
 *
 * <h2>两个工厂（Go 语义逐条对照）</h2>
 * <ul>
 *   <li>{@link #newFetcher()}（agent 用）：markdown 输出、2MB 上限、60s、带
 *       Chromium 渲染兜底。</li>
 *   <li>{@link #newPipelineFetcher()}（chat 管线用）：纯 HTTP、纯文本抽取、100KB
 *       上限、15s（重构前遗留值）、无浏览器。</li>
 * </ul>
 *
 * <h2>接缝与已知差异（报告同步列明）</h2>
 * <ol>
 *   <li><b>Chromium 渲染（chromedp）无 Java 等价物</b> → {@link BrowserRenderer}
 *       接缝，默认实现恒失败 = 走 Go 自己的 "browser unavailable" 分支（SPA 页面
 *       最终报 empty_content，Go 测试
 *       TestFetcherReturnsErrorWhenBrowserFallbackFailsOnSPA 即该路径）。真要恢复
 *       无头浏览器只需提供实现。</li>
 *   <li><b>IP pinning</b>：Go 在 dial 层解析 DNS 后把连接钉到首个校验通过的 IP；
 *       JDK HttpClient 不能替换 dialer，Java 侧用「发送前 + 每跳重定向前的
 *       SSRF 校验（含 DNS 解析 IP 检查）」近似（LlmTransport 同款取舍）。</li>
 *   <li>超时：Go 的 client.Timeout 覆盖连接+读全响应；Java 挂 per-request timeout。</li>
 * </ol>
 */
public final class Fetcher {

    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(60);
    static final Duration PIPELINE_FETCH_TIMEOUT = Duration.ofSeconds(15);
    static final int MAX_BODY_SIZE = 100 * 1024;
    static final int MAX_AGENT_BODY_SIZE = 2 * 1024 * 1024;

    final boolean markdown;
    final BrowserRenderer renderBrowser;
    final Duration timeout;
    final long maxBodySize;

    Fetcher(boolean markdown, Duration timeout, long maxBodySize, BrowserRenderer renderBrowser) {
        this.markdown = markdown;
        this.timeout = timeout;
        this.maxBodySize = maxBodySize;
        this.renderBrowser = renderBrowser;
    }

    /** 对照 NewFetcher：生产 fetcher（markdown + 浏览器兜底接缝）。 */
    public static Fetcher newFetcher() {
        return new Fetcher(true, FETCH_TIMEOUT, MAX_AGENT_BODY_SIZE,
                BrowserRenderer.UNAVAILABLE);
    }

    /** 对照 NewPipelineFetcher：HTTP-only、旧 15s 超时、无浏览器。 */
    public static Fetcher newPipelineFetcher() {
        return new Fetcher(false, PIPELINE_FETCH_TIMEOUT, MAX_BODY_SIZE, null);
    }

    /** 对照 FetchURLContent：chat 管线的包级 API 保留。 */
    public static String fetchUrlContent(String rawUrl) {
        return newPipelineFetcher().fetch(rawUrl);
    }

    /**
     * 对照 Fetch：下载页面并返回干净文本。所有失败抛 {@link FetchException}
     * （Go 的 {@code (string, error)}）。
     */
    public String fetch(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new FetchException(Code0.INVALID_URL, false, "url is empty");
        }
        URI parsedUrl;
        try {
            parsedUrl = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new FetchException(Code0.INVALID_URL, false, "invalid URL: " + e.getMessage());
        }
        String scheme = parsedUrl.getScheme() == null ? "" : parsedUrl.getScheme();
        String hostname = parsedUrl.getHost() == null ? "" : parsedUrl.getHost();
        if ((!scheme.equals("http") && !scheme.equals("https")) || hostname.isEmpty()) {
            throw new FetchException(Code0.INVALID_URL, false, "invalid URL format");
        }
        try {
            LlmTransport.validateUrlForSsrf(rawUrl);
        } catch (RuntimeException e) {
            throw classifyValidationError(e);
        }
        HttpResult httpResult;
        try {
            httpResult = fetchHttp(rawUrl, parsedUrl);
        } catch (FetchException httpErr) {
            // 对照 Go：HTTP 失败后 403/empty-content 可尝试浏览器兜底，失败仍抛 httpErr
            if (renderBrowser != null && canRenderAfterHttpError(httpErr)) {
                try {
                    String rendered = renderBrowser.render(resolvePinnedTarget(rawUrl));
                    String content = extractContent(new HttpResult(
                            rendered.getBytes(StandardCharsets.UTF_8), rawUrl, "text/html"));
                    if (!content.trim().isEmpty()) {
                        return content;
                    }
                } catch (RuntimeException ignored) {
                    // 回落到 httpErr
                }
            }
            throw httpErr;
        }
        // 对照 Go：parseErr 先于浏览器兜底判定；markdown 模式解析错误直接抛
        String content = null;
        FetchException parseErr = null;
        try {
            content = extractContent(httpResult);
        } catch (FetchException e) {
            parseErr = e;
            if (markdown) {
                throw e;
            }
        }
        boolean requiresBrowser = parseErr == null
                && (!markdown || isHTMLContent(httpResult.contentType()))
                && needsBrowserFallback(content, httpResult.body());
        if (parseErr == null && !content.trim().isEmpty() && !requiresBrowser) {
            return content;
        }
        if (renderBrowser != null) {
            String browserUrl = !httpResult.finalUrl().isEmpty() ? httpResult.finalUrl() : rawUrl;
            try {
                String rendered = renderBrowser.render(resolvePinnedTarget(browserUrl));
                String browserContent = extractContent(new HttpResult(
                        rendered.getBytes(StandardCharsets.UTF_8), browserUrl, "text/html"));
                if (!browserContent.trim().isEmpty()) {
                    return browserContent;
                }
            } catch (RuntimeException ignored) {
                // Go：browser 失败回落
            }
        }
        if (parseErr != null) {
            throw parseErr;
        }
        if (content.trim().isEmpty() || requiresBrowser) {
            throw new FetchException(Code0.EMPTY_CONTENT, false, "page contains no readable text");
        }
        return content;
    }

    private record HttpResult(byte[] body, String finalUrl, String contentType) {
    }

    private HttpResult fetchHttp(String rawUrl, URI parsedUrl) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(parsedUrl).timeout(timeout);
        setBrowserHeaders(builder, parsedUrl);
        HttpResponse<byte[]> resp;
        try {
            resp = LlmTransport.send(builder.GET().build(),
                    LlmTransport.DEFAULT_MAX_REDIRECTS, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw classifyRequestError(ie);
            }
            throw classifyRequestError(e);
        }
        boolean success = resp.statusCode() == 200;
        if (markdown) {
            success = resp.statusCode() >= 200 && resp.statusCode() < 300;
        }
        if (!success) {
            throw classifyHttpStatus(resp.statusCode());
        }
        byte[] body = resp.body();
        if (markdown && body.length > maxBodySize) {
            throw new FetchException(Code0.BODY_TOO_LARGE, false,
                    "page exceeds the " + maxBodySize + " byte download limit");
        }
        String contentType = headerValue(resp, "Content-Type");
        String parsed = parseMediaType(contentType);
        if (parsed.isEmpty()) {
            parsed = detectContentType(body);
        }
        return new HttpResult(body, rawUrl, parsed);
    }

    private static String headerValue(HttpResponse<byte[]> resp, String name) {
        return resp.headers().firstValue(name).orElse("");
    }

    /** mime.ParseMediaType 的有界版：取 ; 前的 media-type 并小写。 */
    static String parseMediaType(String v) {
        if (v == null) {
            return "";
        }
        int idx = v.indexOf(';');
        String mt = (idx >= 0 ? v.substring(0, idx) : v).trim().toLowerCase(Locale.ROOT);
        return mt;
    }

    /** http.DetectContentType 的最小子集（HTML/text/json/pdf 的常见形态）。 */
    static String detectContentType(byte[] body) {
        String head = new String(body, 0, Math.min(body.length, 512),
                StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT).trim();
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            return "text/html; charset=utf-8";
        }
        if (head.startsWith("%pdf-")) {
            return "application/pdf";
        }
        if (head.startsWith("{") || head.startsWith("[")) {
            return "text/plain; charset=utf-8";
        }
        // 对照 DetectContentType 的默认：text/plain; charset=utf-8
        return "text/plain; charset=utf-8";
    }

    /**
     * 对照 resolvePinnedTarget：浏览器渲染前的目标解析（端口/DNS/公网 IP 校验）。
     * 渲染本身走接缝，pin 在 Java 侧不成立（见类注释差异 2），返回校验后的 URL。
     */
    private String resolvePinnedTarget(String rawUrl) {
        URI parsedUrl;
        try {
            parsedUrl = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new FetchException(Code0.INVALID_URL, false, "invalid URL: " + e.getMessage());
        }
        String host = parsedUrl.getHost();
        try {
            if (!new com.ragagent.common.security.SsrfGuard().isWhitelisted(host)) {
                for (InetAddress ip : InetAddress.getAllByName(host)) {
                    if (!com.ragagent.common.security.IpClass.classify(ip).classification()
                            .equals(com.ragagent.common.security.IpClass.Class.PUBLIC)) {
                        throw new FetchException(Code0.SSRF_REJECTED, false,
                                "host resolves to restricted IP " + ip.getHostAddress());
                    }
                }
            }
        } catch (FetchException e) {
            throw e;
        } catch (Exception e) {
            throw new FetchException(Code0.DNS, true,
                    "DNS lookup failed for " + host + ": " + e.getMessage());
        }
        return rawUrl;
    }

    /** 对照 setBrowserHeaders：浏览器式请求头逐条对照。 */
    static void setBrowserHeaders(HttpRequest.Builder b, URI parsedUrl) {
        b.header("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        b.header("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        b.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        b.header("Accept-Encoding", "identity");
        b.header("Cache-Control", "no-cache");
        b.header("Pragma", "no-cache");
        b.header("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"");
        b.header("Sec-Ch-Ua-Mobile", "?0");
        b.header("Sec-Ch-Ua-Platform", "\"macOS\"");
        b.header("Sec-Fetch-Dest", "document");
        b.header("Sec-Fetch-Mode", "navigate");
        b.header("Sec-Fetch-Site", "none");
        b.header("Sec-Fetch-User", "?1");
        b.header("Upgrade-Insecure-Requests", "1");
        b.header("Referer", parsedUrl.getScheme() + "://" + parsedUrl.getHost() + "/");
    }

    /** 对照 classifyHTTPStatus。 */
    static FetchException classifyHttpStatus(int statusCode) {
        if (statusCode == 403) {
            return new FetchException(Code0.HTTP_403, false, "HTTP " + statusCode + " " + phrase(statusCode));
        }
        if (statusCode == 429) {
            return new FetchException(Code0.HTTP_429, true, "HTTP " + statusCode + " " + phrase(statusCode));
        }
        if (statusCode >= 500) {
            return new FetchException(Code0.HTTP_5XX, true, "HTTP " + statusCode + " " + phrase(statusCode));
        }
        return new FetchException(Code0.HTTP_STATUS, false, "HTTP " + statusCode + " " + phrase(statusCode));
    }

    private static String phrase(int code) {
        String s = RerankPhrase.go(code);
        return s.substring(String.valueOf(code).length()).trim();
    }

    /** 对照 classifyValidationError：DNS 文案 → DNS 可重试；其余 SSRF 拒绝。 */
    static FetchException classifyValidationError(RuntimeException err) {
        String message = (err.getMessage() == null ? "" : err.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("dns resolution failed") || message.contains("dns lookup failed")) {
            return new FetchException(Code0.DNS, true, "DNS lookup failed: " + err.getMessage());
        }
        return new FetchException(Code0.SSRF_REJECTED, false, "URL rejected: " + err.getMessage());
    }

    /** 对照 classifyRequestError：传输层异常 → 失败码。 */
    static FetchException classifyRequestError(Exception err) {
        if (err instanceof java.net.http.HttpTimeoutException) {
            return new FetchException(Code0.TIMEOUT, true, "fetch timed out: " + err.getMessage());
        }
        if (err instanceof java.net.UnknownHostException) {
            return new FetchException(Code0.DNS, true, "DNS lookup failed: " + err.getMessage());
        }
        String message = (err.getMessage() == null ? "" : err.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("timed out") || message.contains("timeout")) {
            return new FetchException(Code0.TIMEOUT, true, "fetch timed out: " + err.getMessage());
        }
        if (message.contains("redirect") || message.contains("stopped after")) {
            return new FetchException(Code0.REDIRECT_REJECTED, false,
                    "redirect rejected: " + err.getMessage());
        }
        if (message.contains("dns resolution failed") || message.contains("dns lookup failed")) {
            return new FetchException(Code0.DNS, true, "DNS lookup failed: " + err.getMessage());
        }
        if (message.contains("connection blocked:")) {
            return new FetchException(Code0.SSRF_REJECTED, false, "URL rejected: " + err.getMessage());
        }
        if (message.contains("certificate") || message.contains("tls")
                || err instanceof javax.net.ssl.SSLException) {
            return new FetchException(Code0.TLS, false, "TLS validation failed: " + err.getMessage());
        }
        return new FetchException(Code0.CONNECTION, true, "fetch failed: " + err.getMessage());
    }

    /** 对照 needsBrowserFallback：SPA 症状判定。 */
    static boolean needsBrowserFallback(String content, byte[] html) {
        String trimmed = content == null ? "" : content.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.contains("enable javascript") || trimmed.contains("loading...")) {
            return true;
        }
        if (trimmed.codePointCount(0, trimmed.length()) >= 200) {
            return false;
        }
        String lowerHtml = new String(html, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        boolean hasAppRoot = lowerHtml.contains("id=\"app\"") || lowerHtml.contains("id='app'")
                || lowerHtml.contains("id=\"root\"") || lowerHtml.contains("id='root'");
        return hasAppRoot && lowerHtml.contains("<script");
    }

    /** 对照 canRenderAfterHTTPError。 */
    static boolean canRenderAfterHttpError(FetchException err) {
        return err.getCode() == Code0.HTTP_403 || err.getCode() == Code0.EMPTY_CONTENT;
    }

    /** 对照 extractContent：按 content type 分派。 */
    String extractContent(HttpResult result) {
        if (!markdown) {
            return htmlToText(new String(result.body(), StandardCharsets.UTF_8));
        }
        if (isHTMLContent(result.contentType())) {
            return AgentMarkdown.htmlToMarkdown(new String(result.body(), StandardCharsets.UTF_8),
                    result.finalUrl());
        }
        String ct = result.contentType();
        if (ct.startsWith("text/") || ct.equals("application/json") || ct.equals("application/xml")
                || ct.endsWith("+json") || ct.endsWith("+xml")) {
            if (!isValidUtf8(result.body()) || containsNul(result.body())) {
                throw new FetchException(Code0.UNSUPPORTED_CONTENT, false, "page is not UTF-8 text");
            }
            return new String(result.body(), StandardCharsets.UTF_8).trim();
        }
        throw new FetchException(Code0.UNSUPPORTED_CONTENT, false,
                "unsupported page content type: " + ct + "; use an appropriate document reader");
    }

    static boolean isValidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /** 对照 isHTMLContent。 */
    static boolean isHTMLContent(String contentType) {
        return contentType.equals("text/html") || contentType.equals("application/xhtml+xml");
    }

    /**
     * 对照 htmlToText（goquery 路径的有界复刻）：剥 script/style/nav/footer/header/
     * iframe/noscript/svg/img → 取 body → 剥标签 → 按行 trim → 拼非空行。
     * <b>有界实现</b>：正则级剥除（无 DOM），属性内出现 "&gt;" 的病态 HTML 与
     * goquery 有差——goquery 抽不出时会走 Go 自己的 stripTags 回退，本实现等于
     * 恒走"剥标签"路径，输出对正常页面一致。
     */
    static String htmlToText(String html) {
        String cleaned = HtmlStrip.removeBlocks(html);
        String text = HtmlStrip.stripTags(cleaned);
        if (text.isEmpty()) {
            return text;
        }
        List<String> cleanedLines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            line = HtmlStrip.goTrimSpace(line);
            if (!line.isEmpty()) {
                cleanedLines.add(line);
            }
        }
        return String.join("\n", cleanedLines);
    }

    /** 包内错误码别名（枚举在 FetchException 内，避免重复声明）。 */
    static final class Code0 {
        static final FetchException.Code INVALID_URL = FetchException.Code.INVALID_URL;
        static final FetchException.Code DNS = FetchException.Code.DNS;
        static final FetchException.Code TLS = FetchException.Code.TLS;
        static final FetchException.Code TIMEOUT = FetchException.Code.TIMEOUT;
        static final FetchException.Code CONNECTION = FetchException.Code.CONNECTION;
        static final FetchException.Code READ = FetchException.Code.READ;
        static final FetchException.Code HTML_PARSE = FetchException.Code.HTML_PARSE;
        static final FetchException.Code SNAPSHOT_EXPIRED = FetchException.Code.SNAPSHOT_EXPIRED;
        static final FetchException.Code HTTP_403 = FetchException.Code.HTTP_403;
        static final FetchException.Code HTTP_429 = FetchException.Code.HTTP_429;
        static final FetchException.Code HTTP_5XX = FetchException.Code.HTTP_5XX;
        static final FetchException.Code HTTP_STATUS = FetchException.Code.HTTP_STATUS;
        static final FetchException.Code SSRF_REJECTED = FetchException.Code.SSRF_REJECTED;
        static final FetchException.Code REDIRECT_REJECTED = FetchException.Code.REDIRECT_REJECTED;
        static final FetchException.Code EMPTY_CONTENT = FetchException.Code.EMPTY_CONTENT;
        static final FetchException.Code BODY_TOO_LARGE = FetchException.Code.BODY_TOO_LARGE;
        static final FetchException.Code UNSUPPORTED_CONTENT = FetchException.Code.UNSUPPORTED_CONTENT;

        private Code0() {
        }
    }

    /** HTTP 短语表（对照 Go resp.Status；RerankHttp 同款表的小副本）。 */
    static final class RerankPhrase {
        private RerankPhrase() {
        }

        static String go(int code) {
            return code + " " + switch (code) {
                case 200 -> "OK";
                case 204 -> "No Content";
                case 301 -> "Moved Permanently";
                case 302 -> "Found";
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 429 -> "Too Many Requests";
                case 500 -> "Internal Server Error";
                case 502 -> "Bad Gateway";
                case 503 -> "Service Unavailable";
                case 504 -> "Gateway Timeout";
                default -> "";
            };
        }
    }

    /** 有界 HTML 剥除/解码工具（无 DOM；与 datasource 的 Jdk 系列同思路）。 */
    static final class HtmlStrip {
        private static final Pattern[] BLOCKS = {
                Pattern.compile("(?is)<script\\b[^>]*>.*?</script>"),
                Pattern.compile("(?is)<style\\b[^>]*>.*?</style>"),
                Pattern.compile("(?is)<noscript\\b[^>]*>.*?</noscript>"),
                Pattern.compile("(?is)<nav\\b[^>]*>.*?</nav>"),
                Pattern.compile("(?is)<footer\\b[^>]*>.*?</footer>"),
                Pattern.compile("(?is)<header\\b[^>]*>.*?</header>"),
                Pattern.compile("(?is)<iframe\\b[^>]*>.*?</iframe>"),
                Pattern.compile("(?is)<svg\\b[^>]*>.*?</svg>"),
                Pattern.compile("(?is)<img\\b[^>]*>"),
        };

        private HtmlStrip() {
        }

        static String removeBlocks(String html) {
            String out = html;
            for (Pattern p : BLOCKS) {
                out = p.matcher(out).replaceAll("");
            }
            return out;
        }

        static String stripTags(String html) {
            StringBuilder b = new StringBuilder(html.length());
            boolean inTag = false;
            for (int i = 0; i < html.length(); i++) {
                char c = html.charAt(i);
                if (c == '<') {
                    // html5 分词：'<' 后不是字母/斜杠/感叹号/问号时是字面文本
                    //（"a < b" 保持原样——对照 goquery 的解析行为）
                    char next = i + 1 < html.length() ? html.charAt(i + 1) : 0;
                    if (Character.isLetter(next) || next == '/' || next == '!' || next == '?') {
                        inTag = true;
                    } else {
                        b.append(c);
                    }
                } else if (c == '>') {
                    inTag = false;
                } else if (!inTag) {
                    b.append(c);
                }
            }
            return goTrimSpace(decodeEntities(b.toString()));
        }

        /** 常见命名实体 + 数字实体（有界；HtmlEntities 在 datasource 包内不可见）。 */
        static String decodeEntities(String s) {
            if (!s.contains("&")) {
                return s;
            }
            StringBuffer out = new StringBuffer(s.length());
            Matcher m = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").matcher(s);
            while (m.find()) {
                String rep;
                String g = m.group(1);
                try {
                    if (g.startsWith("#x") || g.startsWith("#X")) {
                        rep = new String(Character.toChars(Integer.parseInt(g.substring(2), 16)));
                    } else if (g.startsWith("#")) {
                        rep = new String(Character.toChars(Integer.parseInt(g.substring(1))));
                    } else {
                        rep = switch (g) {
                            case "amp" -> "&";
                            case "lt" -> "<";
                            case "gt" -> ">";
                            case "quot" -> "\"";
                            case "apos" -> "'";
                            case "nbsp" -> "\u00A0";
                            case "mdash" -> "—";
                            case "ndash" -> "–";
                            case "hellip" -> "…";
                            case "lsquo" -> "‘";
                            case "rsquo" -> "’";
                            case "ldquo" -> "“";
                            case "rdquo" -> "”";
                            default -> "&" + g + ";";
                        };
                    }
                } catch (RuntimeException e) {
                    rep = m.group();
                }
                m.appendReplacement(out, Matcher.quoteReplacement(rep));
            }
            m.appendTail(out);
            return out.toString();
        }

        static String goTrimSpace(String s) {
            int start = 0;
            int end = s.length();
            while (start < end && isGoSpace(s.charAt(start))) {
                start++;
            }
            while (end > start && isGoSpace(s.charAt(end - 1))) {
                end--;
            }
            return s.substring(start, end);
        }

        private static boolean isGoSpace(char c) {
            switch (c) {
                case '\t': case '\n': case '\u000B': case '\f': case '\r':
                case ' ': case '\u0085': case '\u00A0': case '\u1680':
                case '\u2028': case '\u2029': case '\u202F': case '\u205F': case '\u3000':
                    return true;
                default:
                    return c >= '\u2000' && c <= '\u200A';
            }
        }
    }
}
