package com.ragagent.evaluation.metric;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对照 Go metric.NDCGMetric（ndcg.go 全文）：命中序列截断到 top-k，
 * 相关性只取 0/1，IDCG 的理想序按「min(相关总数, 命中数) 个 1 打头」构造。
 */
public final class NdcgMetric implements Metrics {

    private final int k;

    public NdcgMetric(int k) {
        this.k = k;
    }

    @Override
    public double compute(MetricInput input) {
        List<List<Integer>> gts = input.retrievalGT;
        List<Integer> ids = input.retrievalIDs;

        // 截断 top-k（Go：ids[:k]，len<=k 时原样）
        if (ids.size() > k) {
            ids = ids.subList(0, k);
        }

        Set<Integer> gtSets = new HashSet<>();
        int countGt = 0;
        for (List<Integer> gt : gts) {
            countGt += gt.size();
            gtSets.addAll(gt);
        }

        Map<Integer, Integer> relevanceScores = new HashMap<>();
        for (Integer docId : ids) {
            relevanceScores.put(docId, gtSets.contains(docId) ? 1 : 0);
        }

        double dcg = 0;
        for (int i = 0; i < ids.size(); i++) {
            dcg += (Math.pow(2, relevanceScores.get(ids.get(i))) - 1) / MetricCommon.log2(i + 2);
        }

        int idealLen = MetricCommon.min(countGt, ids.size());
        double idcg = 0;
        for (int i = 0; i < ids.size(); i++) {
            int relevance = i < idealLen ? 1 : 0;
            idcg += (double) relevance / MetricCommon.log2(i + 2);
        }

        if (idcg == 0) {
            return 0;
        }
        return dcg / idcg;
    }
}
