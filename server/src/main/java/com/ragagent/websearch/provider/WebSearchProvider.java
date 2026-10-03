package com.ragagent.websearch.provider;

import java.util.List;

import com.ragagent.retrieval.domain.WebSearchFilters;
import com.ragagent.retrieval.domain.WebSearchResult;

/**
 * 网络搜索 provider（含按 country/freshness 过滤的可选能力与空结果诊断的可选能力）。
 *
 * <p>三种能力面合并成一个接口 + default 方法：default 抛异常 =
 * 「未实现过滤能力」的运行期语义。</p>
 */
public interface WebSearchProvider {

    /** 注册表类型标识（如 "bing"、"google"）。 */
    String name();

    /** 执行搜索。 */
    List<WebSearchResult> search(String query, int maxResults, boolean includeDate);

    /**
     * 带 country/freshness 过滤的搜索。默认实现抛异常（WebSearchService 报
     * {@code provider %s does not support country/freshness filters}）。
     * Brave 覆写。
     */
    default List<WebSearchResult> searchWithFilters(String query, int maxResults,
                                                    boolean includeDate, WebSearchFilters filters) {
        throw new UnsupportedOperationException(name()
                + " does not support country/freshness filters");
    }

    /**
     * 空结果诊断（test 连接流程用）；默认无诊断。
     */
    default String emptyResultDiagnostics() {
        return "";
    }
}
