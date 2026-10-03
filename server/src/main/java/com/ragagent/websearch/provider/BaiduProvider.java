package com.ragagent.websearch.provider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.common.web.ProviderJson;

/**
 * Baidu AI 搜索 provider。
 *
 * <p>POST 千帆 {@code /v2/ai_search/web_search}；messages 载荷 +
 * {@code resource_type_filter:[{type:"web",top_k}]}。query 按「CJK/全角算 2」的
 * 宽度模型归一到 72 单位（超限截断）；maxResults 缺省 5、封顶 50；200 响应里的
 * {@code code != 0} 是 API 级错误；date 用正则解析变长格式
 * （"2025-4-24" / "2025-04-27 18:02" 等）。</p>
 */
public final class BaiduProvider implements WebSearchProvider {

    static final String DEFAULT_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    static final int DEFAULT_RESULTS = 5;
    static final int MAX_RESULTS = 50;
    static final int MAX_QUERY_UNITS = 72;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final Pattern BAIDU_DATE_RE = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");

    String baseUrl = DEFAULT_URL;

    private final String apiKey;

    public BaiduProvider(WebSearchProviderParams params) {
        if (params.getApiKey().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Baidu provider");
        }
        this.apiKey = params.getApiKey();
    }

    @Override
    public String name() {
        return "baidu";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        String preparedQuery = normalizeBaiduQuery(query);
        if (preparedQuery.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        if (maxResults <= 0) {
            maxResults = DEFAULT_RESULTS;
        }
        if (maxResults > MAX_RESULTS) {
            maxResults = MAX_RESULTS;
        }

        var body = ProviderJson.object();
        var messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", preparedQuery);
        body.put("search_source", "baidu_search_v2");
        var filter = body.putArray("resource_type_filter").addObject();
        filter.put("type", "web");
        filter.put("top_k", maxResults);
        // search_recency_filter：无值省略该键
        byte[] json = ProviderJson.marshal(body);

        var req = SearchHttp.request(baseUrl, TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        byte[] respBody = resp.body().length > (2 << 20)
                ? java.util.Arrays.copyOf(resp.body(), 2 << 20) : resp.body();
        if (resp.status() != 200) {
            throw new SearchHttp.SearchHttpException("baidu API returned status " + resp.status()
                    + ": " + new String(respBody, java.nio.charset.StandardCharsets.UTF_8));
        }
        JsonNode respData = ProviderJson.parse(respBody);
        if (respData == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal response");
        }
        int code = respData.path("code").asInt(0);
        if (code != 0) {
            throw new SearchHttp.SearchHttpException("baidu API error (code " + code + "): "
                    + respData.path("message").asText(""));
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode ref : respData.path("references")) {
            WebSearchResult result = new WebSearchResult();
            result.setTitle(ref.path("title").asText(""));
            result.setUrl(ref.path("url").asText(""));
            result.setContent(ref.path("content").asText(""));
            result.setSource("baidu");
            String date = ref.path("date").asText("");
            if (includeDate && !date.isEmpty()) {
                OffsetDateTime t = parseBaiduDate(date);
                if (t != null) {
                    result.setPublishedAt(t);
                }
            }
            results.add(result);
        }
        return results;
    }

    /**
     * 正则抓 y-m-d[ h:m[:s]]，补位成 "YYYY-MM-DD HH:MM:SS"
     * 一次性解析（UTC 墙钟）。
     */
    static OffsetDateTime parseBaiduDate(String dateStr) {
        Matcher m = BAIDU_DATE_RE.matcher(dateStr == null ? "" : dateStr);
        if (!m.find()) {
            return null;
        }
        // 月/日/时分秒补前导零到宽 2（"4"→"04"）再拼。
        String normalized = String.format("%s-%s-%s %s:%s:%s",
                m.group(1), pad2(defaultStr(m.group(2), "00")), pad2(defaultStr(m.group(3), "00")),
                pad2(defaultStr(m.group(4), "00")), pad2(defaultStr(m.group(5), "00")),
                pad2(defaultStr(m.group(6), "00")));
        try {
            return java.time.LocalDateTime.parse(normalized,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atOffset(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String pad2(String s) {
        return s.length() >= 2 ? s : "0" + s;
    }

    private static String defaultStr(String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }

    /** CJK/全角按 2 单位计的截断。 */
    static String normalizeBaiduQuery(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return "";
        }
        if (baiduQueryUnits(q) <= MAX_QUERY_UNITS) {
            return q;
        }
        StringBuilder b = new StringBuilder(q.length());
        int used = 0;
        for (int i = 0; i < q.length(); ) {
            int cp = q.codePointAt(i);
            int width = baiduQueryUnitWidth(cp);
            if (used + width > MAX_QUERY_UNITS) {
                break;
            }
            b.appendCodePoint(cp);
            used += width;
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    static int baiduQueryUnits(String query) {
        int units = 0;
        for (int i = 0; i < query.length(); ) {
            int cp = query.codePointAt(i);
            units += baiduQueryUnitWidth(cp);
            i += Character.charCount(cp);
        }
        return units;
    }

    static int baiduQueryUnitWidth(int r) {
        return r <= 0x7F ? 1 : 2;
    }
}
