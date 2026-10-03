package com.ragagent.agent.tools;

/**
 * 预算分摊。
 *
 * <p>registry 把输出上限经 {@link ToolRequest#outputBudget()} 发布给工具；本类给"一次结果渲染
 * 多条记录"的工具做 max-min 公平（water-filling）分配：小于均份额的条目保全长并把富余让给大条目
 * ——批式结果因此按"削 biggest"退化而不是"整条丢弃"。</p>
 *
 * <p>行为示例：{@code (100,[10,20,30])→[10,20,30]}、{@code (90,[5,1000,1000])→[5,42,42]}、
 * {@code (1000,[700,20,5000,120])→[430,20,430,120]}、{@code (10,[100])→[10]}。</p>
 */
public final class OutputBudgets {

    private OutputBudgets() {
    }

    /**
     * @param total 总预算（码点数）
     * @param sizes 各条目的全长
     * @return 每条目的 cap；cap ≤ size 且 sum ≤ total
     */
    public static int[] splitBudgetFairly(int total, int[] sizes) {
        int[] caps = new int[sizes == null ? 0 : sizes.length];
        if (sizes == null || sizes.length == 0 || total <= 0) {
            return caps;
        }
        boolean[] settled = new boolean[sizes.length];
        int remaining = total;
        int unsettled = sizes.length;
        while (unsettled > 0) {
            int share = remaining / unsettled;
            if (share <= 0) {
                break;
            }
            boolean progressed = false;
            for (int i = 0; i < sizes.length; i++) {
                if (settled[i] || sizes[i] > share) {
                    continue;
                }
                caps[i] = sizes[i];
                settled[i] = true;
                remaining -= sizes[i];
                unsettled--;
                progressed = true;
            }
            if (!progressed) {
                // 仍未安顿的条目全部超过公平份额——余量均分，分配完成。
                for (int i = 0; i < sizes.length; i++) {
                    if (!settled[i]) {
                        caps[i] = share;
                    }
                }
                break;
            }
        }
        return caps;
    }
}
