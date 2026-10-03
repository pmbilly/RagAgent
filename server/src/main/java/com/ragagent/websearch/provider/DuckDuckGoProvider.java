package com.ragagent.websearch.provider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.common.web.ProviderJson;

/**
 * DuckDuckGo 搜索 provider。
 *
 * <p>HTML 端点优先（{@code html.duckduckgo.com/html/?q=&kl=cn-zh} + 桌面 UA），
 * 失败或空结果回落 Instant Answer API（{@code api.duckduckgo.com/?q=&format=json&
 * no_html=1&skip_disambig=1} + UA WeKnora/1.0）。<b>HTML 解析是有界实现</b>：
 * 用正则切 {@code .web-result} 块里的 {@code .result__a}（标题+
 * href）与 {@code .result__snippet}，正常页面的
 * 抽取结果一致，病态嵌套可能有差。</p>
 */
public final class DuckDuckGoProvider implements WebSearchProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    String htmlUrl = "https://html.duckduckgo.com/html/";
    String apiUrl = "https://api.duckduckgo.com/";
    private static final String CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    DuckDuckGoProvider() {
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        if (maxResults <= 0) {
            maxResults = 5;
        }
        // HTML 优先
        List<WebSearchResult> htmlResults = null;
        RuntimeException htmlErr = null;
        try {
            htmlResults = searchHtml(query, maxResults);
        } catch (RuntimeException e) {
            htmlErr = e;
        }
        if (htmlResults != null && !htmlResults.isEmpty()) {
            return htmlResults;
        }
        // 回落 Instant Answer API
        try {
            List<WebSearchResult> apiResults = searchApi(query, maxResults);
            if (!apiResults.isEmpty()) {
                return apiResults;
            }
        } catch (RuntimeException apiErr) {
            if (htmlErr != null) {
                throw new SearchHttp.SearchHttpException(
                        "duckduckgo HTML search failed: " + htmlErr.getMessage(), apiErr);
            }
            throw new SearchHttp.SearchHttpException(
                    "duckduckgo API search failed: " + apiErr.getMessage(), apiErr);
        }
        if (htmlErr != null) {
            throw new SearchHttp.SearchHttpException(
                    "duckduckgo HTML search failed: " + htmlErr.getMessage(), htmlErr);
        }
        throw new SearchHttp.SearchHttpException(
                "duckduckgo API search failed: no results");
    }

    private List<WebSearchResult> searchHtml(String query, int maxResults) {
        // 手拼查询串按键字母序（kl 先于 q）
        String url = htmlUrl + "?kl=cn-zh&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var req = SearchHttp.request(url, TIMEOUT)
                .header("User-Agent", CHROME_UA)
                .GET()
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.status() != 200 && resp.status() != 202) {
            throw new SearchHttp.SearchHttpException(
                    "duckduckgo HTML returned status " + resp.status());
        }
        String html = resp.bodyText();
        List<WebSearchResult> results = new ArrayList<>(maxResults);
        // .web-result 块的有界切分
        Pattern block = Pattern.compile("(?is)class=\"[^\"]*web-result[^\"]*\"");
        List<int[]> blockRanges = new ArrayList<>();
        Matcher bm = block.matcher(html);
        while (bm.find()) {
            blockRanges.add(new int[] {bm.start(), bm.end()});
        }
        for (int i = 0; i < blockRanges.size() && results.size() < maxResults; i++) {
            int from = blockRanges.get(i)[1];
            int to = i + 1 < blockRanges.size() ? blockRanges.get(i + 1)[0] : html.length();
            String section = html.substring(from, to);
            String title = extractText(section, "result__a");
            String link = extractHref(section);
            String snippet = extractText(section, "result__snippet");
            if (!title.isEmpty() && !link.isEmpty()) {
                WebSearchResult result = new WebSearchResult();
                result.setTitle(title);
                result.setUrl(cleanDdgUrl(link));
                result.setSnippet(snippet);
                result.setSource("duckduckgo");
                results.add(result);
            }
        }
        return results;
    }

    /** 取 class 含标记的元素的纯文本（有界：按标签配对剥离）。 */
    private static String extractText(String section, String marker) {
        Pattern p = Pattern.compile("(?is)<(\\w+)([^>]*\\bclass=\"[^\"]*" + marker
                + "[^\"]*\"[^>]*)>(.*?)</\\1>");
        Matcher m = p.matcher(section);
        if (!m.find()) {
            return "";
        }
        return stripTagsAndTrim(m.group(3));
    }

    /** .result__a 的 href。 */
    private static String extractHref(String section) {
        Pattern p = Pattern.compile(
                "(?is)<a[^>]*\\bclass=\"[^\"]*result__a[^\"]*\"[^>]*\\bhref\\s*=\\s*[\"']([^\"']*)[\"']");
        Matcher m = p.matcher(section);
        if (!m.find()) {
            return "";
        }
        return m.group(1);
    }

    private static String stripTagsAndTrim(String html) {
        String text = html.replaceAll("(?is)<[^>]+>", "");
        return SearchDecode.goTrimSpace(text);
    }

    private List<WebSearchResult> searchApi(String query, int maxResults) {
        // 字母序：format / no_html / q / skip_disambig
        String url = apiUrl + "?format=json&no_html=1&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&skip_disambig=1";
        var req = SearchHttp.request(url, TIMEOUT)
                .header("User-Agent", "WeKnora/1.0")
                .GET()
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        if (resp.status() != 200) {
            throw new SearchHttp.SearchHttpException("duckduckgo API returned status "
                    + resp.status() + ": " + resp.bodyText());
        }
        JsonNode apiResponse = ProviderJson.parse(resp.body());
        if (apiResponse == null) {
            throw new SearchHttp.SearchHttpException("failed to decode API response");
        }
        List<WebSearchResult> results = new ArrayList<>(maxResults);
        String abstractText = apiResponse.path("AbstractText").asText("");
        String abstractUrl = apiResponse.path("AbstractURL").asText("");
        if (!abstractText.isEmpty() && !abstractUrl.isEmpty()) {
            WebSearchResult r = new WebSearchResult();
            r.setTitle(apiResponse.path("Heading").asText(""));
            r.setUrl(abstractUrl);
            r.setSnippet(abstractText);
            r.setSource("duckduckgo");
            results.add(r);
        }
        for (JsonNode topic : apiResponse.path("RelatedTopics")) {
            if (results.size() >= maxResults) {
                break;
            }
            String text = topic.path("Text").asText("");
            String firstUrl = topic.path("FirstURL").asText("");
            if (!text.isEmpty() && !firstUrl.isEmpty()) {
                WebSearchResult r = new WebSearchResult();
                r.setTitle(extractTitle(text));
                r.setUrl(firstUrl);
                r.setSnippet(text);
                r.setSource("duckduckgo");
                results.add(r);
            }
        }
        for (JsonNode r2 : apiResponse.path("Results")) {
            if (results.size() >= maxResults) {
                break;
            }
            String text = r2.path("Text").asText("");
            String firstUrl = r2.path("FirstURL").asText("");
            if (!text.isEmpty() && !firstUrl.isEmpty()) {
                WebSearchResult r = new WebSearchResult();
                r.setTitle(extractTitle(text));
                r.setUrl(firstUrl);
                r.setSnippet(text);
                r.setSource("duckduckgo");
                results.add(r);
            }
        }
        return results;
    }

    /** 剥 //duckduckgo.com/l/?uddg= 与 https://duckduckgo.com/l/?uddg= 包装。 */
    static String cleanDdgUrl(String urlStr) {
        if (urlStr == null) {
            return "";
        }
        if (urlStr.startsWith("//duckduckgo.com/l/?uddg=")) {
            String trimmed = urlStr.substring("//duckduckgo.com/l/?uddg=".length());
            int idx = trimmed.indexOf("&rut=");
            if (idx != -1) {
                try {
                    return URLDecoder.decode(trimmed.substring(0, idx), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return "";
                }
            }
        }
        if (urlStr.startsWith("https://duckduckgo.com/l/?uddg=")) {
            try {
                URI parsed = URI.create(urlStr);
                String query = parsed.getRawQuery();
                if (query != null) {
                    for (String kv : query.split("&")) {
                        if (kv.startsWith("uddg=")) {
                            String v = kv.substring(5);
                            return v.isEmpty() ? urlStr
                                    : URLDecoder.decode(v, StandardCharsets.UTF_8);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                return urlStr;
            }
        }
        return urlStr;
    }

    /** 首行 trim，超 100 字节截断加 "..."。 */
    static String extractTitle(String text) {
        String[] lines = text.split("\n", -1);
        String title = SearchDecode.goTrimSpace(lines[0]);
        if (title.length() > 100) {
            title = title.substring(0, 100) + "...";
        }
        return title;
    }
}
