package com.ragagent.agent.tools.wiki;


/** 单页内容重写结果（新内容 + 是否有变更）。 */

    /** 重写结果（Java 无多返回值）。 */
    public record RewriteResult(String content, boolean changed) {
    }
