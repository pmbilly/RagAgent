package com.ragagent.evaluation.metric;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对照 Go metric.PrecisionMetric（precision.go 全文）：
 * 每真值组算「命中数 / 命中序列长度」，再按真值组数平均。
 */
public final class PrecisionMetric implements Metrics {

    @Override
    public double compute(MetricInput input) {
        List<List<Integer>> gts = input.retrievalGT;
        List<Integer> ids = input.retrievalIDs;

        List<Set<Integer>> gtSets = new ArrayList<>(gts.size());
        for (List<Integer> gt : gts) {
            gtSets.add(MetricCommon.toSet(gt));
        }
        if (gts.isEmpty()) {
            return 0.0;
        }
        if (ids.isEmpty()) {
            return 0.0;
        }

        double totalPrecision = 0;
        for (Set<Integer> gtSet : gtSets) {
            int hits = MetricCommon.hit(ids, gtSet);
            totalPrecision += (double) hits / (double) ids.size();
        }
        return totalPrecision / (double) gts.size();
    }
}
