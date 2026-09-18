package com.ragagent.datasource.connector.feishu.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次 wiki 节点子树抓取的<b>结果计数</b>（对照 Go {@code core/shared.go} L43-73 的
 * {@code fetchTally}）。
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

    /** 对照 Go {@code newFetchTally}。 */
    public FetchTally(int discovered) {
        this.discovered = discovered;
    }

    /** 对照 Go {@code (*fetchTally).fetch}。 */
    public void fetch() {
        fetched++;
    }

    /** 对照 Go {@code (*fetchTally).fail}。 */
    public void fail() {
        failed++;
    }

    /** 对照 Go {@code (*fetchTally).Skip(objType)}：不支持的 obj_type，无条目。 */
    public void skip(String objType) {
        skippedByType.merge(objType == null ? "" : objType, 1, Integer::sum);
    }

    /** 对照 Go {@code (*fetchTally).skipped()}：各类型跳过数之和。 */
    public int skipped() {
        int n = 0;
        for (int c : skippedByType.values()) {
            n += c;
        }
        return n;
    }

    /**
     * 对照 Go {@code (*fetchTally).summary()}。
     *
     * <p>格式逐字照抄：
     * {@code "discovered=%d fetched=%d failed=%d skipped_unsupported=%d by_type=%v"}。
     * 其中 Go 的 {@code %v} 打在 {@code map[string]int} 上是
     * <b>{@code map[键:值 键:值]}（键按字典序）</b>——Go 的 fmt 对 map 恒排序，
     * 所以这里也用 {@link TreeMap} 排一遍再拼，保证与 Go 的日志行逐字一致。</p>
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
