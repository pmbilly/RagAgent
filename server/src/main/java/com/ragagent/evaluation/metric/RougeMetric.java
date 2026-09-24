package com.ragagent.evaluation.metric;

import java.util.List;
import java.util.Map;

/**
 * 对照 Go metric.RougeMetric（rouge.go 全文）：句切后按 metric 名选算法，
 * 取指定统计量（{@code "f"}）——调用侧只用 rouge-1/rouge-2/rouge-l + exclusive=true。
 */
public final class RougeMetric implements Metrics {

    private final boolean exclusive;
    private final String metric;
    private final String stats;

    public RougeMetric(boolean exclusive, String metric, String stats) {
        this.exclusive = exclusive;
        this.metric = metric;
        this.stats = stats;
    }

    @Override
    public double compute(MetricInput input) {
        List<String> hyps = List.of(input.generatedTexts);
        List<String> refs = List.of(input.generatedGT);

        double scores = 0.0;
        int count = 0;

        for (int i = 0; i < hyps.size(); i++) {
            List<String> hyp = MetricCommon.splitSentences(hyps.get(i));
            List<String> ref = MetricCommon.splitSentences(refs.get(i));

            RougeScore.RougeFn fn = RougeScore.AVAILABLE_METRICS.get(metric);
            Map<String, Double> sc = fn.apply(hyp, ref, exclusive);
            scores += sc.get(stats);
            count++;
        }

        if (count == 0) {
            return 0;
        }
        return scores / (double) count;
    }
}
