package com.ragagent.agent.tools;


/** wiki 内容修复结果（修复后内容 + 是否有变更）。 */

    /** 修复结果：(修复后内容, 是否有变更)。 */
    public record RepairResult(String repaired, boolean changed) {
    }
