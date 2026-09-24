package com.ragagent.evaluation.metric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对照 Go metric.MRRMetric（mrr.go 全文）：
 * 每个真值组取命中序列里第一个相关项的 1/位次（1-based），再按真值组数平均。
 */
public final class MrrMetric implements Metrics {

    @Override
    public double compute(MetricInput input) {
        List<List<Integer>> gts = input.retrievalGT;
        List<Integer> ids = input.retrievalIDs;

        List<Set<Integer>> gtSets = new ArrayList<>(gts.size());
        for (List<Integer> gt : gts) {
            gtSets.add(new HashSet<>(gt));
        }

        double sumRR = 0;
        for (Set<Integer> gtSet : gtSets) {
            for (int i = 0; i < ids.size(); i++) {
                if (gtSet.contains(ids.get(i))) {
                    sumRR += 1.0 / (double) (i + 1);
                    break;
                }
            }
        }
        if (gtSets.isEmpty()) {
            return 0;
        }
        return sumRR / (double) gtSets.size();
    }
}
