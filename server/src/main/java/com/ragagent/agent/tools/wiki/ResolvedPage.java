package com.ragagent.agent.tools.wiki;


/** wiki 页面唯一解析结果（页面 + 命中 KB）。 */

    /** 页 + 命中 KB。 */
    public record ResolvedPage(PageView page, String kbId) {
    }
