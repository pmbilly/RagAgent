package com.ragagent.evaluation.metric;

/** 对照 Go {@code interfaces.Metrics}：单一 Compute 入口（internal/types/interfaces/metric.go）。 */
public interface Metrics {

    double compute(MetricInput input);
}
