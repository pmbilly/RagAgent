package com.ragagent.agent.tools.wiki;

import java.util.List;

/** wiki 索引页概览视图。 */

    /** 索引概览响应视图（被用字段子集）。 */
    public record IndexOverviewView(String intro, List<IndexGroupView> groups) {
    }
