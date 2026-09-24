package com.ragagent.evaluation.metric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对照 Go metric.MAPMetric（map.go 全文）：每真值组算 AP
 * （命中位次上的 P@k 之和 / 命中数，无命中则 AP=0），再按真值组数平均。
 */
public final class MapMetric implements Metrics {

    @Override
    public double compute(MetricInput input) {
        List<List<Integer>> gts = input.retrievalGT;
        List<Integer> ids = input.retrievalIDs;

        List<Set<Integer>> gtSets = new ArrayList<>(gts.size());
        for (List<Integer> gt : gts) {
            gtSets.add(new HashSet<>(gt));
        }

        double apSum = 0;
        for (Set<Integer> gtSet : gtSets) {
            boolean[] predHits = new boolean[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                predHits[i] = gtSet.contains(ids.get(i));
            }

            double ap = 0;
            int hitCount = 0;
            for (int k = 0; k < predHits.length; k++) {
                if (predHits[k]) {
                    hitCount++;
                    ap += (double) hitCount / (double) (k + 1);
                }
            }
            if (hitCount > 0) {
                ap /= (double) hitCount;
            }
            apSum += ap;
        }

        if (gtSets.isEmpty()) {
            return 0;
        }
        return apSum / (double) gtSets.size();
    }
}
