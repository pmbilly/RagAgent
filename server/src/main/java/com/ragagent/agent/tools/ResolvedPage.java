package com.ragagent.agent.tools;


/** wiki 页面唯一解析结果（页面 + 命中 KB）。 */

    /** 页 + 命中 KB（对照 resolveUniqueWikiPage 的匿名 hit struct）。 */
    public record ResolvedPage(PageView page, String kbId) {
    }
