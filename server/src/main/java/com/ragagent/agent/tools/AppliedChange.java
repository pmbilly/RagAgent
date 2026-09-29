package com.ragagent.agent.tools;


/** 已应用的内容重写（页面 + 原文），供回滚。 */
    public record AppliedChange(PageView page, String originalContent) {
    }
