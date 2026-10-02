package com.ragagent.websearch.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * 对照 Go {@code types.GetWebSearchProviderTypes()}（internal/types/web_search_provider.go
 * L176-372）：/web-search-providers/types 与旧版 /web-search/providers 共用的静态元数据。
 * 条目顺序 = Go 数组字面量顺序（契约，勿排序/增删）。
 */
public final class WebSearchProviderTypes {

    private WebSearchProviderTypes() {
    }

    public static List<TypeInfo> all() {
        List<TypeInfo> out = new ArrayList<>();
        out.add(of("brave", "Brave Search", true, false, false, false, true,
                "Brave Search API (supports country and freshness filters)",
                "https://api-dashboard.search.brave.com/app/keys", null));
        out.add(of("duckduckgo", "DuckDuckGo", false, false, false, false, true,
                "DuckDuckGo Search (free, no API key required)",
                "https://duckduckgo.com/", null));
        out.add(of("bing", "Bing", true, false, false, false, true,
                "Bing Search API (requires API key from Azure)",
                "https://learn.microsoft.com/en-us/bing/search-apis/bing-web-search/overview", null));
        out.add(of("google", "Google", true, false, true, false, true,
                "Google Custom Search API (requires API key and engine ID)",
                "https://developers.google.com/custom-search/v1/overview", null));
        out.add(of("tavily", "Tavily", true, false, false, false, true,
                "Tavily Search API (requires API key)",
                "https://tavily.com/", null));
        out.add(of("ollama", "Ollama Web Search", true, false, false, false, false,
                "Ollama Cloud web search (requires Ollama API key)",
                "https://docs.ollama.com/capabilities/web-search", null));
        out.add(of("searxng", "SearXNG", false, false, false, true, true,
                "Self-hosted SearXNG metasearch instance (provide instance URL; private hosts must be SSRF-whitelisted)",
                "https://docs.searxng.org/", null));
        out.add(of("baidu", "Baidu", true, false, false, false, false,
                "Baidu AI Search (requires API key from Baidu Cloud)",
                "https://cloud.baidu.com/doc/AppBuilder/s/qlvEcai0p", null));
        out.add(of("keenable", "Keenable", false, true, false, false, true,
                "Keenable web search built for AI agents (keyless by default; an optional API key lifts the rate limit)",
                "https://keenable.ai/", null));
        // metaso：config_fields.scope（6 个 option）
        List<Map<String, Object>> metasoFields = new ArrayList<>();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("key", "scope");
        scope.put("label", "Search scope");
        scope.put("type", "select");
        scope.put("required", true);
        scope.put("default", "webpage");
        scope.put("description", "Select the content source searched by Metaso.");
        scope.put("options", options(new String[][] {
                {"Web pages", "webpage"}, {"Documents", "document"}, {"Scholar", "scholar"},
                {"Podcasts", "podcast"}, {"Videos", "video"}, {"Images", "image"}}, false));
        metasoFields.add(scope);
        out.add(of("metaso", "Metaso AI Search", true, false, false, false, true,
                "Metaso AI Search API (requires API key)",
                "https://metaso.cn/search-api/playground", metasoFields));
        // zhipu：config_fields.search_engine + content_size（带 label_key/description_key）
        List<Map<String, Object>> zhipuFields = new ArrayList<>();
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("key", "search_engine");
        engine.put("label", "Search engine");
        engine.put("label_key", "webSearchSettings.configFields.searchEngine");
        engine.put("type", "select");
        engine.put("required", true);
        engine.put("default", "search_std");
        engine.put("description", "Select the Zhipu search engine and per-request price tier.");
        engine.put("description_key", "webSearchSettings.configFields.searchEngineDesc");
        engine.put("options", options(new String[][] {
                {"Standard · ¥0.01/request", "search_std"},
                {"Pro · ¥0.03/request", "search_pro"},
                {"Sogou · ¥0.05/request", "search_pro_sogou"},
                {"Quark · ¥0.05/request", "search_pro_quark"}}, true));
        zhipuFields.add(engine);
        Map<String, Object> size = new LinkedHashMap<>();
        size.put("key", "content_size");
        size.put("label", "Content size");
        size.put("label_key", "webSearchSettings.configFields.contentSize");
        size.put("type", "select");
        size.put("required", true);
        size.put("default", "medium");
        size.put("description", "Medium returns concise summaries; high returns more context.");
        size.put("description_key", "webSearchSettings.configFields.contentSizeDesc");
        size.put("options", options(new String[][] {
                {"Medium", "medium"}, {"High", "high"}}, true));
        zhipuFields.add(size);
        out.add(of("zhipu", "Zhipu AI", true, false, false, false, true,
                "Zhipu AI Web Search API (requires API key)",
                "https://docs.bigmodel.cn/cn/guide/tools/web-search", zhipuFields));
        // exa：config_fields.include_text（required 缺省 false）
        List<Map<String, Object>> exaFields = new ArrayList<>();
        Map<String, Object> includeText = new LinkedHashMap<>();
        includeText.put("key", "include_text");
        includeText.put("label", "Include text");
        includeText.put("type", "select");
        includeText.put("default", "false");
        includeText.put("description", "Include page text in the unified result Content field.");
        includeText.put("options", options(new String[][] {
                {"Enabled", "true"}, {"Disabled", "false"}}, false));
        exaFields.add(includeText);
        out.add(of("exa", "Exa", true, false, false, false, true,
                "Exa Search API for AI applications (requires API key)",
                "https://docs.exa.ai/", exaFields));
        // bocha：config_fields.freshness + summary
        List<Map<String, Object>> bochaFields = new ArrayList<>();
        Map<String, Object> freshness = new LinkedHashMap<>();
        freshness.put("key", "freshness");
        freshness.put("label", "Freshness");
        freshness.put("type", "select");
        freshness.put("required", true);
        freshness.put("default", "noLimit");
        freshness.put("description", "Time range filter applied by Bocha; noLimit is recommended.");
        freshness.put("options", options(new String[][] {
                {"No limit", "noLimit"}, {"Past day", "oneDay"}, {"Past week", "oneWeek"},
                {"Past month", "oneMonth"}, {"Past year", "oneYear"}}, false));
        bochaFields.add(freshness);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("key", "summary");
        summary.put("label", "Summary");
        summary.put("type", "select");
        summary.put("default", "true");
        summary.put("description", "Request long text summaries and prefer them as result snippets.");
        summary.put("options", options(new String[][] {
                {"Enabled", "true"}, {"Disabled", "false"}}, false));
        bochaFields.add(summary);
        out.add(of("bocha", "Bocha AI Search", true, false, false, false, true,
                "Bocha AI Web Search API (requires API key)",
                "https://open.bochaai.com/", bochaFields));
        return out;
    }

    private static List<Map<String, Object>> options(String[][] pairs, boolean withLabelKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] pair : pairs) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("label", pair[0]);
            if (withLabelKey) {
                o.put("label_key", "webSearchSettings.configFields." + labelKeyOf(pair[1]));
            }
            o.put("value", pair[1]);
            out.add(o);
        }
        return out;
    }

    private static String labelKeyOf(String value) {
        return switch (value) {
            case "search_std" -> "searchStd";
            case "search_pro" -> "searchPro";
            case "search_pro_sogou" -> "searchSogou";
            case "search_pro_quark" -> "searchQuark";
            case "medium" -> "contentMedium";
            case "high" -> "contentHigh";
            default -> value;
        };
    }

    private static TypeInfo of(String id, String name, boolean requiresApiKey, boolean supportsOptionalApiKey,
            boolean requiresEngineId, boolean requiresBaseUrl, boolean supportsProxy,
            String description, String docsUrl, List<Map<String, Object>> configFields) {
        TypeInfo t = new TypeInfo();
        t.id = id;
        t.name = name;
        t.requiresApiKey = requiresApiKey;
        t.supportsOptionalApiKey = supportsOptionalApiKey;
        t.requiresEngineId = requiresEngineId;
        t.requiresBaseUrl = requiresBaseUrl;
        t.supportsProxy = supportsProxy;
        t.description = description;
        t.docsUrl = docsUrl;
        t.configFields = configFields;
        return t;
    }

        public static class TypeInfo {
                public String id;
                public String name;
                public boolean requiresApiKey;
                        public boolean supportsOptionalApiKey;
                public boolean requiresEngineId;
                public boolean requiresBaseUrl;
                public boolean supportsProxy;
                public String description;
                        public String docsUrl;
                        public List<Map<String, Object>> configFields;
    }
}
