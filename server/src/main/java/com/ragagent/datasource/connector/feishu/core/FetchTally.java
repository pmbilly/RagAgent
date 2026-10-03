package com.ragagent.datasource.connector.feishu.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次 wiki 节点子树抓取的<b>结果计数</b>。
 *
 * <h2>它存在的理由</h2>
 * <p>没有它时，不支持的节点（mindnote/slides/…）会<b>无声无息</b>地消失：
 * 没有条目、没有错误、没有日志，用户无法解释"13 个文档只同步了 3 个"
 * （Tencent/WeKnora#2136）。所以连接器把每个节点的归属记进这里，
 * 最后 Emit 一条可行动的汇总。</p>
 *
 * <p><b>这是内部类型</b>：只用于日志汇总，从不落 jsonb / 从不进响应。</p>
 */
public final class FetchTally {

    private final int discovered;
    private int fetched;
    private int failed;
    private final Map<String, Integer> skippedByType = new LinkedHashMap<>();

    /** 以发现的节点总数起步。 */
    public FetchTally(int discovered) {
        this.discovered = discovered;
    }

    /** 记一次成功抓取。 */
    public void fetch() {
        fetched++;
    }

    /** 记一次失败。 */
    public void fail() {
        failed++;
    }

    /** 不支持的 obj_type：无条目产出，按类型计数。 */
    public void skip(String objType) {
        skippedByType.merge(objType == null ? "" : objType, 1, Integer::sum);
    }

    /** 各类型跳过数之和。 */
    public int skipped() {
        int n = 0;
        for (int c : skippedByType.values()) {
            n += c;
        }
        return n;
    }

    /**
     * 汇总日志行。
     *
     * <p>格式：
     * {@code "discovered=%d fetched=%d failed=%d skipped_unsupported=%d by_type=map[键:值 …]"}；
     * {@code by_type} 的键按字典序输出（{@link TreeMap}），保证日志行稳定可比对。</p>
     */
    public String summary() {
        Map<String, Integer> sorted = new TreeMap<>(skippedByType);
        StringBuilder byType = new StringBuilder("map[");
        boolean first = true;
        for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            if (!first) {
                byType.append(' ');
            }
            first = false;
            byType.append(e.getKey()).append(':').append(e.getValue());
        }
        byType.append(']');
        return "discovered=" + discovered
                + " fetched=" + fetched
                + " failed=" + failed
                + " skipped_unsupported=" + skipped()
                + " by_type=" + byType;
    }
}
