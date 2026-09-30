package com.ragagent.websearch.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.retrieval.domain.WebSearchFilters;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.retrieval.support.WebResultConverter;
import com.ragagent.websearch.domain.WebSearchProvider;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.websearch.mapper.WebSearchProviderRepository;
import com.ragagent.websearch.provider.WebSearchProviderRegistry;

/**
 * 网络搜索执行服务（对照 Go {@code internal/application/service/web_search.go} 的
 * 执行链：Search / resolveProvider / filterBlacklist / matchesBlacklistRule /
 * ConvertWebSearchResults + CompressWithRAG 的纯辅助族）。
 *
 * <h2>与 Go 的结构对应</h2>
 * <ul>
 *   <li>timeout：配置面 {@code config.WebSearch.Timeout}（缺省 10s）——构造参数。</li>
 *   <li>{@code resolveProvider}：providerID 路径从仓储取实体（合并 call-time 代理
 *       覆盖）→ 注册表创建；缺省回落 deprecated 的 config.Provider（Warnf 日志文案
 *       保留在注释级）；两者都空 → {@code no web search provider configured}。</li>
 *   <li>过滤分支：Filters.Country/Freshness 非空时先 Validate 再要求 provider 支持
 *       （断言失败 = {@code provider %s does not support country/freshness filters;
 *       omit them or select Brave}）。</li>
 *   <li>{@link #compressWithRag}：Go 签名里的 kbSvc/knowSvc 用端口接口注入
 *       （hybrid 检索与段落摄入随波 4.6/检索引擎接线；本批先落纯辅助族与骨架）。</li>
 * </ul>
 */
@Service
public class WebSearchService {

    private final WebSearchProviderRegistry registry;
    private final WebSearchProviderRepository providerRepo;
    private final int timeoutSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public WebSearchService(WebSearchProviderRegistry registry,
                            WebSearchProviderRepository providerRepo) {
        this(registry, providerRepo, 10);
    }

    public WebSearchService(WebSearchProviderRegistry registry,
                            WebSearchProviderRepository providerRepo, int timeoutSeconds) {
        this.registry = registry;
        this.providerRepo = providerRepo;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 10;
    }

    /**
     * 对照 {@code Search}：config 必填 → 解析 provider → 过滤分支 → 黑名单过滤。
     * query 级超时由调用方（agent 引擎/管线）以 deadline 形式施加（Go 的
     * context.WithTimeout 在 Java 无 ctx 对应物，见约定 §5）。
     */
    public List<WebSearchResult> search(long tenantId, String providerId,
                                        WebSearchConfig config, String query) {
        if (config == null) {
            throw new IllegalStateException("web search config is required");
        }
        com.ragagent.websearch.provider.WebSearchProvider searchProvider =
                resolveProvider(tenantId, providerId, config);

        List<WebSearchResult> results;
        if (!config.filters.country().isEmpty() || !config.filters.freshness().isEmpty()) {
            config.filters.validate();
            try {
                results = searchProvider.searchWithFilters(query, config.maxResults,
                        config.includeDate, config.filters);
            } catch (UnsupportedOperationException e) {
                throw new IllegalStateException("provider " + searchProvider.name()
                        + " does not support country/freshness filters; "
                        + "omit them or select Brave");
            }
        } else {
            results = searchProvider.search(query, config.maxResults, config.includeDate);
        }
        return filterBlacklist(results, config.blacklist);
    }

    /** 对照 resolveProvider。 */
    private com.ragagent.websearch.provider.WebSearchProvider resolveProvider(
            long tenantId, String providerId, WebSearchConfig cfg) {
        if (providerId != null && !providerId.isEmpty()) {
            WebSearchProvider entity = providerRepo.getByID(tenantId, providerId);
            if (entity == null) {
                throw new IllegalStateException("web search provider not found: " + providerId);
            }
            WebSearchProviderParams params = mergeProxyFromWebSearchConfig(
                    entity.getParameters(), cfg);
            try {
                return registry.createProvider(entity.getProvider(), params);
            } catch (RuntimeException e) {
                throw new IllegalStateException("failed to create provider " + entity.getName()
                        + " (" + entity.getProvider() + "): " + e.getMessage());
            }
        }
        // 兼容路径：deprecated 的 config.Provider（Go 有 Warnf 日志）
        if (cfg.provider != null && !cfg.provider.isEmpty()) {
            WebSearchProviderParams base = new WebSearchProviderParams();
            base.setApiKey(cfg.apiKey);
            WebSearchProviderParams params = mergeProxyFromWebSearchConfig(base, cfg);
            try {
                return registry.createProvider(cfg.provider, params);
            } catch (RuntimeException e) {
                throw new IllegalStateException("web search provider " + cfg.provider
                        + " is not available: " + e.getMessage());
            }
        }
        throw new IllegalStateException("no web search provider configured");
    }

    /** 对照 mergeProxyFromWebSearchConfig：cfg.ProxyURL 非空时 call-time 覆盖。 */
    static WebSearchProviderParams mergeProxyFromWebSearchConfig(WebSearchProviderParams base,
                                                                 WebSearchConfig cfg) {
        WebSearchProviderParams p = base == null ? new WebSearchProviderParams() : base;
        if (cfg != null) {
            String pu = cfg.proxyUrl == null ? "" : cfg.proxyUrl.trim();
            if (!pu.isEmpty()) {
                p.setProxyUrl(pu);
            }
        }
        return p;
    }

    /** 对照 filterBlacklist。 */
    public static List<WebSearchResult> filterBlacklist(List<WebSearchResult> results,
                                                        List<String> blacklist) {
        if (blacklist == null || blacklist.isEmpty()) {
            return results;
        }
        List<WebSearchResult> filtered = new ArrayList<>(results.size());
        for (WebSearchResult result : results) {
            boolean shouldFilter = false;
            for (String rule : blacklist) {
                if (matchesBlacklistRule(result.getUrl(), rule)) {
                    shouldFilter = true;
                    break;
                }
            }
            if (!shouldFilter) {
                filtered.add(result);
            }
        }
        return filtered;
    }

    /**
     * 对照 matchesBlacklistRule：`/.../` 是正则（Go regexp 语法；Java 的 RE2 差异
     * 保留——非法正则告警后按不匹配）；否则 `*` → `.*` 全串锚定。
     */
    static boolean matchesBlacklistRule(String url, String rule) {
        String u = url == null ? "" : url;
        if (rule.startsWith("/") && rule.endsWith("/") && rule.length() >= 2) {
            String pattern = rule.substring(1, rule.length() - 1);
            try {
                // Go 的 regexp.MatchString 是**非锚定**子串匹配 → Matcher.find()
                return java.util.regex.Pattern.compile(toJavaRegex(pattern))
                        .matcher(u).find();
            } catch (RuntimeException e) {
                return false;
            }
        }
        String pattern = "^" + rule.replace("*", ".*") + "$";
        try {
            return u.matches(pattern);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Go regexp（RE2）与 java.util.regex 的转义差异：RE2 不支持的 Java 语法
     * （如回溯引用 \1）会直接被 Go 判为编译错——这里传入前把 `\` 序列原样保留
     * （Java 语义兼容的公共子集照抄）；非法时 matches 抛错 → false，对照 Go 的
     * err 分支。
     */
    private static String toJavaRegex(String pattern) {
        return pattern;
    }

    /** 对照 ConvertWebSearchResults（service 层：seq = 下标）。 */
    public static List<SearchResult> convertWebSearchResults(List<WebSearchResult> webResults) {
        return WebResultConverter.convert(webResults, idx -> idx);
    }

    // ── CompressWithRAG 的纯辅助族（对照 web_search.go L262-374）──────────

    /**
     * 对照 selectReferencesRoundRobin：按 source URL 公平轮选至 limit 条。
     * refs 的 URL 从 content 首行标记提取。
     */
    public static List<SearchResult> selectReferencesRoundRobin(List<WebSearchResult> raw,
                                                                List<SearchResult> refs,
                                                                int limit) {
        if (limit <= 0 || refs == null || refs.isEmpty()) {
            return List.of();
        }
        Map<String, List<SearchResult>> urlToRefs = new LinkedHashMap<>();
        for (SearchResult r : refs) {
            String url = extractSourceUrlFromContent(r.getContent());
            if (url.isEmpty()) {
                continue;
            }
            urlToRefs.computeIfAbsent(url, k -> new ArrayList<>()).add(r);
        }
        List<String> order = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WebSearchResult r : raw) {
            if (!r.getUrl().isEmpty() && !seen.contains(r.getUrl())) {
                order.add(r.getUrl());
                seen.add(r.getUrl());
            }
        }
        List<SearchResult> out = new ArrayList<>();
        while (out.size() < limit) {
            boolean progress = false;
            for (String url : order) {
                if (out.size() >= limit) {
                    break;
                }
                List<SearchResult> list = urlToRefs.get(url);
                if (list == null || list.isEmpty()) {
                    continue;
                }
                out.add(list.get(0));
                urlToRefs.put(url, list.subList(1, list.size()));
                progress = true;
            }
            if (!progress) {
                break;
            }
        }
        return out;
    }

    /** 对照 consolidateReferencesByURL：按 URL 把选中引用合并回原始结果。 */
    public static List<WebSearchResult> consolidateReferencesByURL(List<WebSearchResult> raw,
                                                                   List<SearchResult> selected) {
        if (selected == null || selected.isEmpty()) {
            return raw;
        }
        Map<String, List<String>> agg = new LinkedHashMap<>();
        for (SearchResult ref : selected) {
            String url = extractSourceUrlFromContent(ref.getContent());
            if (url.isEmpty()) {
                continue;
            }
            agg.computeIfAbsent(url, k -> new ArrayList<>()).add(stripMarker(ref.getContent()));
        }
        List<WebSearchResult> out = new ArrayList<>(raw.size());
        for (WebSearchResult r : raw) {
            List<String> parts = agg.get(r.getUrl());
            if (parts == null || parts.isEmpty()) {
                out.add(r);
                continue;
            }
            WebSearchResult merged = new WebSearchResult();
            merged.setTitle(r.getTitle());
            merged.setUrl(r.getUrl());
            merged.setSnippet(r.getSnippet());
            merged.setContent(String.join("\n---\n", parts));
            merged.setSource(r.getSource());
            merged.setPublishedAt(r.getPublishedAt());
            out.add(merged);
        }
        return out;
    }

    /** 对照 extractSourceURLFromContent：首行 "[sourceUrl]: " 标记。 */
    public static String extractSourceUrlFromContent(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        if (lines.length == 0) {
            return "";
        }
        String first = lines[0].trim();
        String prefix = "[sourceUrl]: ";
        if (first.startsWith(prefix)) {
            return first.substring(prefix.length()).trim();
        }
        return "";
    }

    /** 对照 stripMarker：剥掉首行标记避免重复。 */
    public static String stripMarker(String content) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        if (lines.length == 0) {
            return content;
        }
        if (lines[0].trim().startsWith("[sourceUrl]: ")) {
            return String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length));
        }
        return content;
    }

    /**
     * 搜索配置（对照 Go {@code types.WebSearchConfig} 的执行面字段子集；
     * jsonb 形状随 session 配置波次对齐，这里只承载执行所需）。
     */
    public static final class WebSearchConfig {
        public String provider = "";
        public String apiKey = "";
        public WebSearchFilters filters = WebSearchFilters.EMPTY;
        public int maxResults;
        public boolean includeDate;
        public List<String> blacklist = new ArrayList<>();
        public String embeddingModelId = "";
        public int documentFragments;
        public String proxyUrl = "";

        public WebSearchFilters getFilters() {
            return filters;
        }
    }
}
