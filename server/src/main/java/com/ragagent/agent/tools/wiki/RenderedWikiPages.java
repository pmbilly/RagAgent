package com.ragagent.agent.tools.wiki;

import java.util.List;

/** 预算渲染输出：正文与被截断/省略的 slug 清单。 */
    public record RenderedWikiPages(String output, List<String> truncatedSlugs, List<String> omittedSlugs) {
    }
