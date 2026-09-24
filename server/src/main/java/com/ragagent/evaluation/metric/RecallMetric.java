package com.ragagent.evaluation.metric;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对照 Go metric.RecallMetric（recall.go 全文）：
 * 每真值组算「命中数 / 真值组大小」（空真值组跳过该项），再按真值组数平均。
 */
public final class RecallMetric implements Metrics {

    @Override
    public double compute(MetricInput input) {
        List<List<Integer>> gts = input.retrievalGT;
        List<Integer> ids = input.retrievalIDs;

        List<Set<Integer>> gtSets = new ArrayList<>(gts.size());
        for (List<Integer> gt : gts) {
            gtSets.add(MetricCommon.toSet(gt));
        }
        if (gtSets.isEmpty()) {
            return 0.0;
        }
        if (ids.isEmpty()) {
            return 0.0;
        }

        double totalRecall = 0;
        for (Set<Integer> gtSet : gtSets) {
            int hits = MetricCommon.hit(ids, gtSet);
            if (!gtSet.isEmpty()) {
                totalRecall += (double) hits / (double) gtSet.size();
            }
        }
        return totalRecall / (double) gtSets.size();
    }
}
