package com.ragagent.websearch.provider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProviderParams;

/**
 * Bocha AI 搜索 provider。
 *
 * <p>URL 硬编码（防 SSRF）；freshness 缺省 noLimit（白名单校验，非法值构造报错，
 * 文案用原始 extra_config 值）；summary 缺省 true（仅 "false" 关闭）。
 * <b>code 字段双形态</b>：错误是 JSON 字符串（"401"）、成功可能是数字（200）——
 * 解析时先 trim 再剥引号转数字，空/null 按 0。dateLastCrawled 是 UTC+8 墙钟
 * 被标 Z 的历史遗留：仅该字段在无偏移时按 +08:00 修正（datePublished 与显式
 * 偏移都准确）。响应 4MB 上限；错误 body 截 4096。</p>
 */
public final class BochaProvider implements WebSearchProvider {

    static final String DEFAULT_URL = "https://api.bochaai.com/v1/web-search";
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_RESULTS = 10;
    static final int MAX_RESULTS = 50;
    static final int MAX_RESPONSE_BYTES = 4 << 20;
    static final String DEFAULT_FRESHNESS = "noLimit";
    private static final Set<String> VALID_FRESHNESS =
            Set.of("noLimit", "oneDay", "oneWeek", "oneMonth", "oneYear");

    String baseUrl = DEFAULT_URL;

    private final String apiKey;
    private final String freshness;
    private final boolean summary;

    public BochaProvider(WebSearchProviderParams params) {
        validateParameters(params);
        this.apiKey = params.getApiKey().trim();
        this.freshness = bochaFreshness(params.getExtraConfig());
        this.summary = bochaSummary(params.getExtraConfig());
    }

    /** 入参校验：api_key 必填；freshness/summary 必须在白名单内。 */
    public static void validateParameters(WebSearchProviderParams params) {
        if (params.getApiKey().trim().isEmpty()) {
            throw new SearchHttp.SearchHttpException("API key is required for Bocha provider");
        }
        String freshness = bochaFreshness(params.getExtraConfig());
        if (freshness.isEmpty()) {
            String raw = params.getExtraConfig() == null ? ""
                    : params.getExtraConfig().getOrDefault("freshness", "");
            throw new SearchHttp.SearchHttpException("invalid Bocha freshness: " + raw);
        }
    }

    static String bochaFreshness(Map<String, String> extraConfig) {
        String freshness = extraConfig == null ? "" : extraConfig.getOrDefault("freshness", "");
        freshness = freshness == null ? "" : freshness.trim();
        if (!freshness.isEmpty()) {
            return VALID_FRESHNESS.contains(freshness) ? freshness : "";
        }
        return DEFAULT_FRESHNESS;
    }

    static boolean bochaSummary(Map<String, String> extraConfig) {
        String summary = extraConfig == null ? "" : extraConfig.getOrDefault("summary", "");
        summary = summary == null ? "" : summary.trim();
        return !summary.equals("false");
    }

    @Override
    public String name() {
        return "bocha";
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            throw new SearchHttp.SearchHttpException("query is empty");
        }
        if (maxResults <= 0) {
            maxResults = DEFAULT_RESULTS;
        }
        if (maxResults > MAX_RESULTS) {
            maxResults = MAX_RESULTS;
        }

        var body = GoJson.object();
        body.put("query", q);
        body.put("freshness", freshness);
        body.put("summary", summary);
        body.put("count", maxResults);
        byte[] json = GoJson.marshal(body);

        var req = SearchHttp.request(baseUrl, TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        SearchHttp.Result resp = SearchHttp.sendFollowRedirects(req, null);
        byte[] respBody = readBounded(resp.body(), "Bocha");
        if (resp.status() != 200) {
            throw httpError(resp.status(), respBody);
        }

        JsonNode response = GoJson.parse(respBody);
        if (response == null) {
            throw new SearchHttp.SearchHttpException("failed to unmarshal Bocha response");
        }
        int code = parseFlexibleCode(response.path("code"));
        if (code != 0 && code != 200) {
            throw new SearchHttp.SearchHttpException("Bocha API returned code " + code);
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (JsonNode item : response.path("data").path("webPages").path("value")) {
            if (item.path("name").asText("").trim().isEmpty()
                    && item.path("url").asText("").trim().isEmpty()) {
                continue;
            }
            String snippet = item.path("summary").asText("").trim();
            if (snippet.isEmpty()) {
                snippet = item.path("snippet").asText("").trim();
            }
            WebSearchResult result = new WebSearchResult();
            result.setTitle(item.path("name").asText(""));
            result.setUrl(item.path("url").asText(""));
            result.setSnippet(snippet);
            result.setSource("bocha");
            if (includeDate) {
                OffsetDateTime publishedAt = parseFlexibleDate(
                        item.path("datePublished").asText(""), false);
                if (publishedAt == null) {
                    publishedAt = parseFlexibleDate(item.path("dateLastCrawled").asText(""), true);
                }
                if (publishedAt != null) {
                    result.setPublishedAt(publishedAt);
                }
            }
            results.add(result);
            if (results.size() >= maxResults) {
                break;
            }
        }
        return results;
    }

    private static byte[] readBounded(byte[] body, String label) {
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new SearchHttp.SearchHttpException(
                    label + " response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        return body;
    }

    /** 错误体解析：message/msg 字段优先，body 截 4096。 */
    static SearchHttp.SearchHttpException httpError(int statusCode, byte[] body) {
        JsonNode apiError = GoJson.parse(body);
        if (apiError != null && apiError.isObject()) {
            String detail = apiError.path("message").asText("").trim();
            if (detail.isEmpty()) {
                detail = apiError.path("msg").asText("").trim();
            }
            if (!detail.isEmpty()) {
                return new SearchHttp.SearchHttpException(
                        "Bocha API returned status " + statusCode + ": " + detail);
            }
        }
        String detail = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (detail.length() > 4096) {
            detail = detail.substring(0, 4096);
        }
        if (detail.isEmpty()) {
            return new SearchHttp.SearchHttpException("Bocha API returned status " + statusCode);
        }
        return new SearchHttp.SearchHttpException(
                "Bocha API returned status " + statusCode + ": " + detail);
    }

    /** 三个时间格式依序尝试（失败 null）。 */
    static OffsetDateTime parseFlexibleDate(String value, boolean lastCrawledSemantics) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (lastCrawledSemantics && v.endsWith("Z")) {
            // dateLastCrawled 的历史遗留：UTC+8 墙钟被标 Z，仅该字段按 +08:00 修正
            v = v.substring(0, v.length() - 1) + "+08:00";
        }
        // 格式 1：RFC3339（必须带偏移；ISO 解析容忍 1-9 位小数）
        try {
            return OffsetDateTime.parse(v);
        } catch (RuntimeException ignored) {
            // 尝试下一个
        }
        // 格式 2：yyyy-MM-dd HH:mm:ss（无偏移按 UTC 墙钟解释）
        try {
            return java.time.LocalDateTime.parse(v,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atOffset(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // 尝试下一个
        }
        // 格式 3：yyyy-MM-dd
        try {
            return java.time.LocalDate.parse(v).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** code 字段容错解析：字符串/数字/空 双形态。 */
    static int parseFlexibleCode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return 0;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        String s = node.asText("").trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty() || s.equals("null")) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
