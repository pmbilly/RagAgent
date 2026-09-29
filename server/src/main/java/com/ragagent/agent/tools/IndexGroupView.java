package com.ragagent.agent.tools;

import java.util.List;

/** wiki 索引概览分组视图。 */

    /** 对照 WikiIndexGroup 被用子集。 */
    public record IndexGroupView(String type, long total, List<IndexEntryView> items) {
    }
