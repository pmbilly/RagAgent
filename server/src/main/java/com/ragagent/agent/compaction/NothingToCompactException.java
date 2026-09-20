package com.ragagent.agent.compaction;

/**
 * 上下文已无法再缩（对照 Go compaction.ErrNothingToCompact，compactor.go L40-44）：
 * keep-recent 预算之外的一切都已经不在了。调用方必须把它当作<b>停止信号</b>而不是
 * 重试的理由，否则就复现出这个包存在所要阻止的"每轮摘要"循环。
 */
public class NothingToCompactException extends RuntimeException {

    private static final String MESSAGE = "compaction: nothing outside the keep-recent budget";

    public NothingToCompactException() {
        super(MESSAGE);
    }
}
