package com.ragagent.agent.compaction;

/**
 * 什么请求了这次压缩（对照 Go compaction.Reason，compactor.go L29-38）。
 * 记日志与给 UI 用。
 */
public final class CompactionReason {

    /** 常规情况：上下文越过了预算（对照 ReasonThreshold）。 */
    public static final String THRESHOLD = "threshold";

    /** 窗口满导致供应商拒绝/截断请求后的修复（对照 ReasonOverflow）。 */
    public static final String OVERFLOW = "overflow";

    private CompactionReason() {
    }
}
