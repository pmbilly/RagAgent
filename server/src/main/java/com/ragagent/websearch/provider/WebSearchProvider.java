package com.ragagent.websearch.provider;

import java.util.List;

import com.ragagent.retrieval.domain.WebSearchFilters;
import com.ragagent.retrieval.domain.WebSearchResult;

/**
 * 网络搜索 provider（对照 Go {@code interfaces.WebSearchProvider} 与
 * {@code FilteredWebSearchProvider}，以及 test_errors.go 的
 * {@code emptyResultDiagnostics} 可选接口）。
 *
 * <p>Go 的三种能力面合并成一个接口 + default 方法（Java 无隐式接口断言，
 * default 抛异常 = 「未实现 FilteredWebSearchProvider」的运行期语义）。</p>
 */
public interface WebSearchProvider {

    /** 注册表类型标识（如 "bing"、"google"）。 */
    String name();

    /** 对照 Search。 */
    List<WebSearchResult> search(String query, int maxResults, boolean includeDate);

    /**
     * 对照 {@code FilteredWebSearchProvider.SearchWithFilters}。默认实现 =
     * Go 的类型断言失败分支（WebSearchService 报
     * {@code provider %s does not support country/freshness filters}）。
     * Brave 覆写。
     */
    default List<WebSearchResult> searchWithFilters(String query, int maxResults,
                                                    boolean includeDate, WebSearchFilters filters) {
        throw new UnsupportedOperationException(name()
                + " does not support country/freshness filters");
    }

    /**
     * 对照 {@code emptyResultDiagnostics}（test 连接流程用）；默认无诊断。
     */
    default String emptyResultDiagnostics() {
        return "";
    }
}
