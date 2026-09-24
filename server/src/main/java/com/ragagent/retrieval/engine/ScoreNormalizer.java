package com.ragagent.retrieval.engine;

/**
 * 分数归一化口——对照 Go {@code internal/application/service/retriever/normalizer.go}
 * 的 {@code ScoreNormalizer} 接口。
 *
 * <p>把检索引擎的原始分映射到统一的 [0,1] 刻度，让不同引擎产出的向量分可以在同一张
 * 排序表里比较。实现必须并发安全且<b>不做 IO</b>（Normalize 在热循环内调用，
 * 不落日志不阻塞）。</p>
 *
 * <p>只归一化<b>向量</b>分。关键词（BM25）分是正数无界，重缩放会塌掉长尾——原样透传；
 * 下游 RRF 融合按秩计算，对刻度免疫。</p>
 */
public interface ScoreNormalizer {

    /** 对照 {@code Normalize}。 */
    double normalize(double score, String retrieverType, String engineType);
}
