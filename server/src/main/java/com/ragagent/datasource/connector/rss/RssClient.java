package com.ragagent.datasource.connector.rss;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;

/**
 * RSS 连接器的出站 HTTP 客户端。
 *
 * <h2>三道防护与四条常量</h2>
 * <ul>
 *   <li>{@code requestTimeout = 20s} → {@link ConnectorHttp#newConnectorHttpClient}{@code (Duration.ofSeconds(20))}。
 *       超时是<b>整个交互</b>的上限（含重定向与读体）。</li>
 *   <li>{@code maxFeedSize = 10MB} / {@code maxArticleSize = 5MB} → 返回前截断。
 *       ⚠️ <b>内存注记</b>：{@link ConnectorHttp.Client#exchange}
 *       只会把整个响应体读完再交给我们，
 *       所以这里只能"读完再截"。<b>返回的字节数一致</b>，但面对恶意超大响应时
 *       内存峰值不受这个上限保护（JDK HttpClient 不给流式钩子，{@code ConnectorHttp} 已有的取舍）。</li>
 *   <li>{@code defaultUserAgent} / 默认 {@code Accept} → 只在<b>没有</b>同名请求头时补
 *       （自定义头写了空值也算"没有"）。</li>
 * </ul>
 *
 * <h2>⚠️ 凭据泄漏防护：自定义头只给 feed，绝不给文章页</h2>
 * <p>{@code fetch(rawURL, maxSize, withAuthHeaders)} 就是这个开关：
 * {@code fetchFeed} 传 true、{@code extractArticle} 传 false。
 * 因为文章页在第三方域名上，把 {@code Authorization} 发过去等于泄漏 feed 的凭据。
 * 有测试钉住"文章请求里没有 X-Test-Auth"。</p>
 *
 * <h2>SSRF 与错误文案</h2>
 * <p>发送前先显式过一次 SSRF 校验。
 * {@link ConnectorHttp.Client#exchange} <b>自己也会</b>在发送前校验 SSRF，
 * 但它的文案是 {@code "outbound request blocked by SSRF policy: …"}，与
 * {@code "URL rejected: SSRF validation failed: …"} 不同。所以这里<b>显式先校验一次</b>
 * 并捕获 {@link ConnectorException}，重包成既定文案——保证
 * {@code fetch feed <url>: URL rejected: SSRF validation failed: …} 形状稳定。</p>
 */
final class RssClient {

    /** 单次请求超时。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** feed 文档的截断上限。 */
    static final long MAX_FEED_SIZE = 10L * 1024 * 1024;

    /** 文章页的截断上限。 */
    static final long MAX_ARTICLE_SIZE = 5L * 1024 * 1024;

    /** 默认 User-Agent：有些 feed 会拒绝空 UA。 */
    static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (compatible; WeKnora-RSS/1.0; +https://weknora.weixin.qq.com)";

    /** 默认 Accept 值。 */
    static final String DEFAULT_ACCEPT =
            "application/rss+xml, application/atom+xml, application/xml, text/xml, "
                    + "application/json, text/html;q=0.9, */*;q=0.8";

    private final ConnectorHttp.Client httpClient;
    private final Map<String, String> headers;
    private final ArticleExtractor articleExtractor;

    RssClient(Map<String, String> headers, ArticleExtractor articleExtractor) {
        this.httpClient = ConnectorHttp.newConnectorHttpClient(REQUEST_TIMEOUT);
        this.headers = headers;
        this.articleExtractor = articleExtractor;
    }

    // ── 端点 ──────────────────────────────────────────────────────────────

    /** 抓 feed 文档，<b>带</b>自定义头。 */
    byte[] fetchFeed(String feedUrl) {
        return fetch(feedUrl, MAX_FEED_SIZE, true);
    }

    /**
     * 抓文章页 → 交给 {@link ArticleExtractor} 抽正文。
     *
     * <p>两个步骤刻意分开：<b>抓取</b>这一层不带自定义头（安全语义），
     * <b>抽取</b>那一层是降级接缝。默认抽取器不可用时 {@code resolveItem} 不会走到这里
     * （直接跳过请求，见 {@link ArticleExtractor}）；测试注入可用实现后，
     * 这条路径的 HTTP 行为（含鉴权头不泄漏）仍可被钉住。</p>
     */
    ArticleExtractor.ExtractedArticle extractArticle(String articleUrl) {
        byte[] body = fetch(articleUrl, MAX_ARTICLE_SIZE, false);
        return articleExtractor.extract(body, articleUrl);
    }

    // ── fetch ─────────────────────────────────────────────────────────────

    private byte[] fetch(String rawUrl, long maxSize, boolean withAuthHeaders) {
        try {
            ConnectorHttp.ssrfGuard().validateURLForSSRF(rawUrl);
        } catch (SsrfGuard.SsrfException e) {
            throw new ConnectorException("URL rejected: " + e.getMessage(), e);
        }
        try {
            URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("invalid URL: " + e.getMessage(), e);
        }

        Map<String, String> requestHeaders = new LinkedHashMap<>();
        if (withAuthHeaders && headers != null) {
            // ⚠️ 值为空的请求头直接丢掉（JDK 的 HttpRequest.Builder.header 不接受空值），
            // 让"配了个空头"退化成"没配这个头"而不是整次抓取炸掉。
            headers.forEach((name, value) -> {
                if (name != null && !name.isEmpty() && value != null && !value.isEmpty()) {
                    requestHeaders.put(name, value);
                }
            });
        }
        if (!hasHeader(requestHeaders, "User-Agent")) {
            putHeader(requestHeaders, "User-Agent", DEFAULT_USER_AGENT);
        }
        if (!hasHeader(requestHeaders, "Accept")) {
            putHeader(requestHeaders, "Accept", DEFAULT_ACCEPT);
        }

        ConnectorHttp.Response response;
        try {
            response = httpClient.exchange("GET", rawUrl, requestHeaders, null);
        } catch (ConnectorException e) {
            // 传输层失败（连不上 / 超时 / 重定向被 SSRF 拦）统一加这个前缀。
            throw new ConnectorException("fetch failed: " + e.getMessage(), e);
        }

        if (response.status() < 200 || response.status() >= 300) {
            throw new ConnectorException("HTTP " + response.status() + " " + response.statusLine());
        }

        byte[] body = response.body();
        if (body == null) {
            body = new byte[0];
        }
        if (body.length > maxSize) {
            body = Arrays.copyOf(body, (int) maxSize);
        }
        return body;
    }

    /**
     * 名字大小写不敏感地找头，
     * <b>值为空串也算"没有"</b>（于是会补上默认值）。
     */
    private static boolean hasHeader(Map<String, String> headers, String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(lowered)) {
                return entry.getValue() != null && !entry.getValue().isEmpty();
            }
        }
        return false;
    }

    /**
     * <b>覆盖</b>同名头（大小写不敏感），
     * 而不是并排放两个。JDK 的 {@code HttpRequest.Builder.header} 拒绝空值，
     * 所以自定义头写了 {@code "User-Agent: "}（空值）时必须先把它换成默认值。
     */
    private static void putHeader(Map<String, String> headers, String name, String value) {
        String lowered = name.toLowerCase(Locale.ROOT);
        headers.keySet().removeIf(k -> k != null && k.toLowerCase(Locale.ROOT).equals(lowered));
        headers.put(name, value);
    }
}
