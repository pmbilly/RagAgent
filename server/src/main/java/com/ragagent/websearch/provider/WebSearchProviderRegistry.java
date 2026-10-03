package com.ragagent.websearch.provider;

import java.util.LinkedHashMap;

import org.springframework.stereotype.Component;
import java.util.Map;

import com.ragagent.websearch.domain.WebSearchProviderParams;

/**
 * provider 类型注册表。
 *
 * <p>类型 ID（"bing"/"google"/...）→ 工厂；按租户参数即时创建实例。
 * 未注册类型报 {@code web search provider type %s not registered}（test 连接
 * 流程的确定性分支）。</p>
 */
@Component
public class WebSearchProviderRegistry {

    /** 参数 → provider 实例（校验失败抛错）。 */
    @FunctionalInterface
    public interface ProviderFactory {
        WebSearchProvider create(WebSearchProviderParams params);
    }

    private final Map<String, ProviderFactory> factories = new LinkedHashMap<>();

    public WebSearchProviderRegistry() {
        factories.put("bing", BingProvider::new);
        factories.put("brave", BraveProvider::new);
        factories.put("google", GoogleProvider::new);
        factories.put("duckduckgo", p -> new DuckDuckGoProvider());
        factories.put("tavily", TavilyProvider::new);
        factories.put("ollama", OllamaProvider::new);
        factories.put("baidu", BaiduProvider::new);
        factories.put("searxng", SearxngProvider::new);
        factories.put("keenable", KeenableProvider::new);
        factories.put("metaso", MetasoProvider::new);
        factories.put("zhipu", ZhipuProvider::new);
        factories.put("exa", ExaProvider::new);
        factories.put("bocha", BochaProvider::new);
    }

    /** 追加注册工厂（初始化后用；如测试注入 stub）。 */
    public void register(String id, ProviderFactory factory) {
        factories.put(id, factory);
    }

    /** 按类型查工厂并创建 provider；未注册的类型报错。 */
    public WebSearchProvider createProvider(String providerType, WebSearchProviderParams params) {
        ProviderFactory factory = factories.get(providerType);
        if (factory == null) {
            throw new SearchHttp.SearchHttpException(
                    "web search provider type " + providerType + " not registered");
        }
        return factory.create(params);
    }
}
