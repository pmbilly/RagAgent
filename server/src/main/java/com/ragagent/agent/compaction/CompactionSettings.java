package com.ragagent.agent.compaction;

/**
 * 压缩设置。
 *
 * <p>不可变值对象：{@code normalize()} 返回修正后的副本。</p>
 */
public record CompactionSettings(
        boolean enabled,
        int maxContextTokens,
        int reserveTokens,
        int keepRecentTokens,
        int maxSummaryTokens) {

    /** 为下一次响应保留的空间下限。 */
    public static final int DEFAULT_RESERVE_TOKENS = 16384;

    /**
     * 压缩后原样保留的近期对话预算。正是它让压缩
     * 会终止：无论上下文长到多大，压缩后都是"摘要 + 这么多"，远低于任何合理阈值，
     * 下一轮不可能立刻再触发。
     */
    public static final int DEFAULT_KEEP_RECENT_TOKENS = 20000;

    /** 全默认构造（各字段为零值，由 normalize() 补默认）。 */
    public static CompactionSettings ofDefaults() {
        return new CompactionSettings(false, 0, 0, 0, 0);
    }

    /**
     * 补齐默认值并调和不能同时满足的预算。
     *
     * <p>关键的是压缩后留出多少空间到下一次触发，而不是一次压掉多少。留半个可用
     * 窗口也能"压缩成功"，但一两轮内就会再触发：摘要还要再占一块，单个工具结果就
     * 可能上万 token。取四分之一，压缩后的上下文约在阈值的一半，是好几轮的工作量。
     * 大窗口不受影响：默认 keep-recent 本就远小于它们的四分之一。工具 schema 不在
     * 这里扣除——keepRecentTokens 是固定的对话预算，不是"扣完工具表剩下的"。</p>
     */
    public CompactionSettings normalize() {
        int reserve = reserveTokens > 0 ? reserveTokens : DEFAULT_RESERVE_TOKENS;
        int keep = keepRecentTokens > 0 ? keepRecentTokens : DEFAULT_KEEP_RECENT_TOKENS;
        if (maxContextTokens > 0) {
            int usable = maxContextTokens - reserve;
            int quarter = usable / 4;
            if (quarter > 0 && keep > quarter) {
                keep = quarter;
            }
        }
        return new CompactionSettings(enabled, maxContextTokens, reserve, keep, maxSummaryTokens);
    }

    /**
     * 必须压缩的上下文阈值。绝对保留量而非窗口比例——要装下的是
     * 响应，其大小不随窗口伸缩。
     */
    public int threshold() {
        if (maxContextTokens <= 0) {
            return 0;
        }
        return Math.max(maxContextTokens - reserveTokens, 1);
    }

    /** 当前上下文是否已越过阈值。 */
    public boolean shouldCompact(int contextTokens) {
        if (!enabled) {
            return false;
        }
        int t = threshold();
        return t > 0 && contextTokens > t;
    }

    /**
     * 摘要调用的补全上限：reserveTokens 的 4/5，再被模型自身
     * 输出上限钳制。旧的硬编码 2000 就是长会话摘要总在半句话停下的原因。另外还以
     * keep-recent 预算封顶：摘要与保留的尾部共处压缩后的上下文里，后续压缩按指令
     * 原地更新摘要——没有同一预算的天花板它就会每轮变大，慢慢吃回刚腾出来的空间。
     */
    public int summaryBudget() {
        int budget = reserveTokens * 4 / 5;
        if (maxSummaryTokens > 0 && maxSummaryTokens < budget) {
            budget = maxSummaryTokens;
        }
        if (keepRecentTokens > 0 && keepRecentTokens < budget) {
            budget = keepRecentTokens;
        }
        return Math.max(budget, 1024);
    }

    /**
     * 被切开的轮次前缀的更小摘要上限：只需解释保留的后缀，
     * 不必承载整个会话。
     */
    public int turnPrefixBudget() {
        return Math.max(summaryBudget() / 2, 512);
    }
}
