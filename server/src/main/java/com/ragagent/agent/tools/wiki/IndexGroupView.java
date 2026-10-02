package com.ragagent.agent.tools.wiki;

import java.util.List;

/** wiki 索引概览分组视图。 */

    /** 索引分组视图（被用字段子集）。 */
    public record IndexGroupView(String type, long total, List<IndexEntryView> items) {
    }
