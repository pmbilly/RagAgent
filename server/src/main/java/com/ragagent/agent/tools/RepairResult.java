package com.ragagent.agent.tools;


/** wiki 内容修复结果（修复后内容 + 是否有变更）。 */

    /** 对照 RepairContentLinks 的 (repaired, changed) 返回。 */
    public record RepairResult(String repaired, boolean changed) {
    }
