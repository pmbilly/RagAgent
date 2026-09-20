package com.ragagent.websearch.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.domain.WebSearchFilters;
import com.ragagent.retrieval.domain.WebSearchResult;
import com.ragagent.websearch.domain.WebSearchProvider;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.websearch.mapper.WebSearchProviderRepository;
import com.ragagent.websearch.provider.WebSearchProviderRegistry;

/**
 * WebSearchService 执行链（对照 Go web_search.go 的 Search/resolveProvider/
 * 过滤分支/黑名单）：provider 实体从仓储加载 → 注册表创建 stub provider 打本地
 * stub，确定性分支对照 Go 文案。
 */
class WebSearchServiceExecTest {

    private static String restore;

    @BeforeAll
    static void whitelistOn() {
        new SsrfGuard().reloadWhitelist("127.0.0.1");
    }

    @AfterAll
    static void whitelistOff() {
        new SsrfGuard().reloadWhitelist("");
    }

    private static WebSearchProvider entity(String id, String type) {
        WebSearchProvider e = new WebSearchProvider();
        e.setId(id);
        e.setProvider(type);
        e.setName("my " + type);
        e.setParameters(new WebSearchProviderParams());
        return e;
    }

    @Test
    void searchByIdUsesEntityAndMergesProxyOverride() {
        WebSearchProviderRepository repo = mock(WebSearchProviderRepository.class);
        WebSearchProvider stored = entity("prov-1", "bocha");
        stored.getParameters().setApiKey("stored-key");
        when(repo.getByID(anyLong(), anyString())).thenReturn(stored);

        WebSearchProviderRegistry registry = new WebSearchProviderRegistry();
        // 注入 stub provider（对照 Go 注册表工厂）
        registry.register("bocha", params -> new com.ragagent.websearch.provider.WebSearchProvider() {
            @Override
            public String name() {
                return "bocha";
            }

            @Override
            public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
                WebSearchResult r = new WebSearchResult();
                r.setTitle(query + "/" + params.getApiKey() + "/" + params.getProxyUrl());
                r.setUrl("https://e/1");
                r.setSource("bocha");
                return List.of(r);
            }
        });
        WebSearchService service = new WebSearchService(registry, repo, 5);

        WebSearchService.WebSearchConfig config = new WebSearchService.WebSearchConfig();
        config.proxyUrl = "  ";
        assertEquals(1, service.search(7, "prov-1", config, "q").size());

        config.proxyUrl = "http://127.0.0.1:8080";
        WebSearchResult got = service.search(7, "prov-1", config, "query").get(0);
        assertEquals("query/stored-key/http://127.0.0.1:8080", got.getTitle(),
                "call-time proxy 覆盖 stored 参数");
    }

    @Test
    void searchFiltersBranchRequiresSupport() {
        WebSearchProviderRepository repo = mock(WebSearchProviderRepository.class);
        when(repo.getByID(anyLong(), anyString())).thenReturn(entity("prov-2", "bocha"));
        WebSearchProviderRegistry registry = new WebSearchProviderRegistry();
        registry.register("bocha", params -> new com.ragagent.websearch.provider.WebSearchProvider() {
            @Override
            public String name() {
                return "bocha";
            }

            @Override
            public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
                return List.of();
            }
        });
        WebSearchService service = new WebSearchService(registry, repo, 5);

        WebSearchService.WebSearchConfig config = new WebSearchService.WebSearchConfig();
        config.filters = new WebSearchFilters("de", "pw");
        RuntimeException err = assertThrows(RuntimeException.class,
                () -> service.search(7, "prov-2", config, "q"));
        assertTrue(err.getMessage().contains(
                "provider bocha does not support country/freshness filters; "
                        + "omit them or select Brave"), err.getMessage());

        // 过滤非法：freshness 校验先于能力断言（对照 Go 的 Validate 顺序）
        WebSearchService.WebSearchConfig badFilter = new WebSearchService.WebSearchConfig();
        badFilter.filters = new WebSearchFilters("", "nope");
        RuntimeException invalid = assertThrows(RuntimeException.class,
                () -> service.search(7, "prov-2", badFilter, "q"));
        assertTrue(invalid.getMessage().contains("freshness must be pd, pw, pm, py"),
                invalid.getMessage());
    }

    @Test
    void resolveProviderErrorBranches() {
        WebSearchProviderRepository repo = mock(WebSearchProviderRepository.class);
        when(repo.getByID(anyLong(), anyString())).thenReturn(null);
        WebSearchService service = new WebSearchService(new WebSearchProviderRegistry(), repo, 5);

        // providerID 非空但实体缺失
        RuntimeException notFound = assertThrows(RuntimeException.class,
                () -> service.search(7, "missing", new WebSearchService.WebSearchConfig(), "q"));
        assertTrue(notFound.getMessage().contains("web search provider not found: missing"),
                notFound.getMessage());

        // 两者皆空
        RuntimeException none = assertThrows(RuntimeException.class,
                () -> service.search(7, "", new WebSearchService.WebSearchConfig(), "q"));
        assertEquals("no web search provider configured", none.getMessage());

        // 兼容路径：config.Provider 未注册
        WebSearchService.WebSearchConfig config = new WebSearchService.WebSearchConfig();
        config.provider = "nope";
        RuntimeException unavailable = assertThrows(RuntimeException.class,
                () -> service.search(7, "", config, "q"));
        assertTrue(unavailable.getMessage()
                .contains("web search provider nope is not available"), unavailable.getMessage());

        // null config
        RuntimeException nullConfig = assertThrows(RuntimeException.class,
                () -> service.search(7, "", null, "q"));
        assertEquals("web search config is required", nullConfig.getMessage());
    }

    @Test
    void deprecatedProviderPathCreatesWithConfigApiKey() {
        WebSearchProviderRepository repo = mock(WebSearchProviderRepository.class);
        WebSearchProviderRegistry registry = new WebSearchProviderRegistry();
        registry.register("duckduckgo", params -> new com.ragagent.websearch.provider.WebSearchProvider() {
            @Override
            public String name() {
                return "duckduckgo";
            }

            @Override
            public List<WebSearchResult> search(String query, int maxResults, boolean includeDate) {
                WebSearchResult r = new WebSearchResult();
                r.setTitle("ok");
                return List.of(r);
            }
        });
        WebSearchService service = new WebSearchService(registry, repo, 0);
        WebSearchService.WebSearchConfig config = new WebSearchService.WebSearchConfig();
        config.provider = "duckduckgo";
        assertEquals(1, service.search(7, "", config, "q").size());
    }
}
