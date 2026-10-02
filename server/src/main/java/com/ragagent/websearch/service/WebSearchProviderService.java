package com.ragagent.websearch.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.websearch.domain.WebSearchProvider;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.websearch.mapper.WebSearchProviderRepository;
import org.springframework.stereotype.Service;

/**
 * 对照 Go {@code service.webSearchProviderService}
 * （internal/application/service/web_search_provider.go）。
 *
 * <p>所有校验失败抛 Go 同文案的 {@link IllegalStateException}/{@link IllegalArgumentException}，
 * 由控制器决定 HTTP 形态（create/update 一律 500 信封 code 1007；test 端点 200 纯字符串）。
 * SSRF 白名单为进程级静态 {@link SsrfGuard}（§9：与 Go 包级变量同语义）。</p>
 */
@Service
public class WebSearchProviderService {

    public static final Set<String> VALID_PROVIDER_TYPES = Set.of(
            "brave", "bing", "google", "duckduckgo", "tavily", "ollama", "baidu",
            "searxng", "keenable", "metaso", "zhipu", "exa", "bocha");

    private static final Set<String> ZHIPU_ENGINES = Set.of("search_std", "search_pro", "search_pro_sogou", "search_pro_quark");
    private static final Set<String> ZHIPU_SIZES = Set.of("medium", "high");
    private static final Set<String> METASO_SCOPES = Set.of("webpage", "document", "scholar", "podcast", "video", "image");
    private static final Set<String> BOCHA_FRESHNESS = Set.of("noLimit", "oneDay", "oneWeek", "oneMonth", "oneYear");

    private final WebSearchProviderRepository repo;
    private final SsrfGuard ssrfGuard;

    public WebSearchProviderService(WebSearchProviderRepository repo, SsrfGuard ssrfGuard) {
        this.repo = repo;
        this.ssrfGuard = ssrfGuard;
    }

    public WebSearchProvider getByID(long tenantId, String id) {
        return repo.getByID(tenantId, id);
    }

    public List<WebSearchProvider> list(long tenantId) {
        return repo.list(tenantId);
    }

    /** Go CreateProvider：校验（失败=普通 error）→ 清默认（失败仅告警）→ 落库 */
    public void create(WebSearchProvider provider) {
        if (provider.getTenantId() == null || provider.getTenantId() == 0) {
            throw failed("tenant ID is required");
        }
        if (!isValidProviderType(provider.getProvider())) {
            throw failed("invalid provider type: " + provider.getProvider());
        }
        validateProviderParameters(provider.getProvider(), provider.getParameters());
        if (provider.isDefault()) {
            try {
                repo.clearDefault(provider.getTenantId(), "");
            } catch (RuntimeException e) {
                // Go：ClearDefault 失败仅 Warnf 后继续
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        repo.create(provider, now);
        // GORM 的 AutoCreateTime 会把 now 回写内存对象（create 响应直接序列化实体，
        // 不回写就输出 year-1——datasource 波 §9 的同款教训）
        provider.setCreatedAt(now);
        provider.setUpdatedAt(now);
    }

    /** Go UpdateProvider：注意 is_default 无条件覆盖（false 也会清除其它默认语义见 ClearDefault 排除自身） */
    public void update(WebSearchProvider provider) {
        if (provider.getTenantId() == null || provider.getTenantId() == 0) {
            throw failed("tenant ID is required");
        }
        if (provider.getProvider() != null && !provider.getProvider().isEmpty()
                && !isValidProviderType(provider.getProvider())) {
            throw failed("invalid provider type: " + provider.getProvider());
        }
        if (provider.isDefault()) {
            try {
                repo.clearDefault(provider.getTenantId(), provider.getId());
            } catch (RuntimeException e) {
                // 同上：仅告警
            }
        }
        if (provider.getProvider() != null && !provider.getProvider().isEmpty()) {
            validateProviderParameters(provider.getProvider(), provider.getParameters());
        }
        repo.update(provider, OffsetDateTime.now());
    }

    /** Go UpdateProviderCredentials：key 缺失/为空/与现值相同 → 不写库，直接返回现有行 */
    public WebSearchProvider updateCredentials(long tenantId, String id, String apiKey) {
        WebSearchProvider existing = repo.getByID(tenantId, id);
        if (existing == null) {
            throw failed("web search provider not found");
        }
        var params = existing.getParameters() == null ? new WebSearchProviderParams() : existing.getParameters();
        if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals(params.getApiKey())) {
            params.setApiKey(apiKey);
            existing.setParameters(params);
            repo.update(existing, OffsetDateTime.now());
        }
        return existing;
    }

    /** Go ClearProviderCredential：幂等（本就为空 → no-op） */
    public void clearCredential(long tenantId, String id, String field) {
        if (!"apiKey".equals(field)) {
            throw failed("unknown credential field: " + field);
        }
        WebSearchProvider existing = repo.getByID(tenantId, id);
        if (existing == null) {
            throw failed("web search provider not found");
        }
        var params = existing.getParameters() == null ? new WebSearchProviderParams() : existing.getParameters();
        if (params.getApiKey().isEmpty()) {
            return;
        }
        params.setApiKey("");
        existing.setParameters(params);
        repo.update(existing, OffsetDateTime.now());
    }

    public void delete(long tenantId, String id) {
        repo.delete(tenantId, id);
    }

    public static boolean isValidProviderType(String provider) {
        return provider != null && VALID_PROVIDER_TYPES.contains(provider);
    }

    /** 对照 validateProviderParameters：每个 provider 的必填/取值校验，文案逐字对照 */
    public void validateProviderParameters(String provider, WebSearchProviderParams params) {
        WebSearchProviderParams p = params == null ? new WebSearchProviderParams() : params;
        switch (provider == null ? "" : provider) {
            case "brave" -> {
                if (p.getApiKey().trim().isEmpty()) {
                    throw failed("API key is required for Brave provider");
                }
            }
            case "bing" -> requireKey(p, "Bing");
            case "google" -> {
                requireKey(p, "Google");
                if (p.getEngineId().isEmpty()) {
                    throw failed("engine ID is required for Google provider");
                }
            }
            case "tavily" -> requireKey(p, "Tavily");
            case "ollama" -> requireKey(p, "Ollama");
            case "baidu" -> requireKey(p, "Baidu");
            case "exa" -> requireKey(p, "Exa");
            case "zhipu" -> validateZhipu(p);
            case "metaso" -> validateMetaso(p);
            case "bocha" -> validateBocha(p);
            case "duckduckgo", "keenable" -> {
                // 无需凭据（keenable 默认免钥）
            }
            case "searxng" -> validateSearxngBaseUrl(p.getBaseUrl());
            default -> {
                // Go switch 无 default 分支 → 直接落到 proxy 校验
            }
        }
        // validateOptionalProxyURL（所有 provider 通用）
        validateProxyUrl(p.getProxyUrl());
    }

    private static void requireKey(WebSearchProviderParams p, String provider) {
        if (p.getApiKey().isEmpty()) {
            throw failed("API key is required for " + provider + " provider");
        }
    }

    /** 对照 ValidateZhipuParameters（extra_config 键：search_engine / content_size） */
    private void validateZhipu(WebSearchProviderParams p) {
        if (p.getApiKey().trim().isEmpty()) {
            throw failed("API key is required for Zhipu provider");
        }
        Map<String, String> cfg = p.getExtraConfig();
        String engine = option(cfg, "search_engine", "search_std");
        String size = option(cfg, "content_size", "medium");
        if (!ZHIPU_ENGINES.contains(engine)) {
            throw failed("invalid Zhipu search engine: " + engine);
        }
        if (!ZHIPU_SIZES.contains(size)) {
            throw failed("invalid Zhipu content size: " + size);
        }
    }

    /** 对照 ValidateMetasoParameters */
    private void validateMetaso(WebSearchProviderParams p) {
        if (p.getApiKey().trim().isEmpty()) {
            throw failed("API key is required for Metaso provider");
        }
        String scope = option(p.getExtraConfig(), "scope", "webpage");
        if (!METASO_SCOPES.contains(scope)) {
            throw failed("invalid Metaso search scope: " + scope);
        }
    }

    /** 对照 ValidateBochaParameters */
    private void validateBocha(WebSearchProviderParams p) {
        if (p.getApiKey().trim().isEmpty()) {
            throw failed("API key is required for Bocha provider");
        }
        String freshness = option(p.getExtraConfig(), "freshness", "noLimit");
        if (!BOCHA_FRESHNESS.contains(freshness)) {
            // Go: params.ExtraConfig["freshness"]——nil map / 缺键都是零值 ""
            String raw = p.getExtraConfig() == null
                    ? ""
                    : p.getExtraConfig().getOrDefault("freshness", "");
            throw failed("invalid Bocha freshness: " + raw);
        }
    }

    /** 对照 ValidateSearxngBaseURL（四段拒绝文案 + SSRF） */
    public void validateSearxngBaseUrl(String rawUrl) {
        String base = rawUrl == null ? "" : rawUrl.trim();
        if (base.isEmpty()) {
            throw failed("base_url is required for SearXNG provider");
        }
        java.net.URI parsed;
        try {
            parsed = java.net.URI.create(base);
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        if (parsed == null || parsed.getScheme() == null || parsed.getScheme().isEmpty()
                || parsed.getHost() == null || parsed.getHost().isEmpty()) {
            throw failed("invalid SearXNG base_url: must be an absolute http(s) URL");
        }
        String scheme = parsed.getScheme();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw failed("invalid SearXNG base_url scheme: " + scheme);
        }
        if ((parsed.getRawQuery() != null && !parsed.getRawQuery().isEmpty())
                || (parsed.getRawFragment() != null && !parsed.getRawFragment().isEmpty())) {
            throw failed("invalid SearXNG base_url: must not contain query or fragment");
        }
        try {
            ssrfGuard.validateURLForSSRF(base);
        } catch (SsrfGuard.SsrfException e) {
            throw failed("invalid SearXNG base_url: " + e.getMessage());
        }
    }

    /** 对照 ValidateProxyURL：trim 后非空才校验（仅 http/https 通过） */
    public void validateProxyUrl(String proxyUrl) {
        String trimmed = proxyUrl == null ? "" : proxyUrl.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        try {
            ssrfGuard.validateURLForSSRF(trimmed);
        } catch (SsrfGuard.SsrfException e) {
            throw failed(e.getMessage());
        }
    }

    /**
     * 对照 {@code registry.CreateProvider} 的**构造期**校验——每个 provider 工厂
     * （internal/infrastructure/web_search/*.go 的 New*Provider）按各自顺序检查：
     * brave/bing/google/tavily/ollama/baidu/exa = key（google 另查 engine_id）→ 代理；
     * zhipu/metaso/bocha = 全量参数校验 → 代理；searxng = base_url → 代理；
     * duckduckgo/keenable = 仅代理。错误文案逐字对照（test 端点的确定性分支）。
     */
    public void constructProvider(String providerType, WebSearchProviderParams params) {
        WebSearchProviderParams p = params == null ? new WebSearchProviderParams() : params;
        switch (providerType == null ? "" : providerType) {
            case "brave" -> {
                if (p.getApiKey().trim().isEmpty()) {
                    throw failed("API key is required for Brave provider");
                }
                validateProxyUrl(p.getProxyUrl());
            }
            case "bing" -> {
                requireKey(p, "Bing");
                validateProxyUrl(p.getProxyUrl());
            }
            case "google" -> {
                requireKey(p, "Google");
                if (p.getEngineId().isEmpty()) {
                    throw failed("engine ID is required for Google provider");
                }
                validateProxyUrl(p.getProxyUrl());
            }
            case "tavily" -> {
                requireKey(p, "Tavily");
                validateProxyUrl(p.getProxyUrl());
            }
            case "ollama" -> {
                requireKey(p, "Ollama");
                validateProxyUrl(p.getProxyUrl());
            }
            case "baidu" -> {
                requireKey(p, "Baidu");
                validateProxyUrl(p.getProxyUrl());
            }
            case "exa" -> {
                if (p.getApiKey().trim().isEmpty()) {
                    throw failed("API key is required for Exa provider");
                }
                validateProxyUrl(p.getProxyUrl());
            }
            case "zhipu" -> {
                validateZhipu(p);
                validateProxyUrl(p.getProxyUrl());
            }
            case "metaso" -> {
                validateMetaso(p);
                validateProxyUrl(p.getProxyUrl());
            }
            case "bocha" -> {
                validateBocha(p);
                validateProxyUrl(p.getProxyUrl());
            }
            case "searxng" -> {
                validateSearxngBaseUrl(p.getBaseUrl());
                validateProxyUrl(p.getProxyUrl());
            }
            case "keenable", "duckduckgo" -> validateProxyUrl(p.getProxyUrl());
            default -> throw failed("web search provider type " + providerType + " not registered");
        }
    }

    private static String option(Map<String, String> cfg, String key, String def) {
        if (cfg == null) {
            return def;
        }
        String v = cfg.get(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    /** 普通 error（Go fmt.Errorf）——控制器把它包成 500 信封 / 200 纯字符串 */
    public static IllegalArgumentException failed(String message) {
        return new IllegalArgumentException(message);
    }
}
