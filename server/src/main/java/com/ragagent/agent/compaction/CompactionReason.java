package com.ragagent.agent.compaction;

/**
 * 什么请求了这次压缩。
 * 记日志与给 UI 用。
 */
public final class CompactionReason {

    /** 常规情况：上下文越过了预算。 */
    public static final String THRESHOLD = "threshold";

    /** 窗口满导致供应商拒绝/截断请求后的修复。 */
    public static final String OVERFLOW = "overflow";

    private CompactionReason() {
    }
}
