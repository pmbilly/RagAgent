package com.ragagent.agent.tools;

import java.util.List;

/** wiki 索引页概览视图。 */

    /** 对照 renderIndexOverviewForAgent 的 WikiIndexResponse 被用子集。 */
    public record IndexOverviewView(String intro, List<IndexGroupView> groups) {
    }
