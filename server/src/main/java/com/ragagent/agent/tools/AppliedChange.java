package com.ragagent.agent.tools;


/** 已应用的内容重写（页面 + 原文），供回滚。 */

    /** 已应用的变更（对照 appliedWikiContentChange）。 */
    public record AppliedChange(PageView page, String originalContent) {
    }
