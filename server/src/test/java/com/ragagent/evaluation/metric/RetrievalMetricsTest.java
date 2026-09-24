package com.ragagent.evaluation.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索类指标移植测试：用例逐条对应 Go
 * {@code internal/application/service/metric/{precision,recall,mrr,map}_test.go}；
 * NDCG 无 Go 测试，用例按 ndcg.go 算法手算（注释给出推导）。
 *
 * <p>精度口径：precision/recall 用 Go 的<b>精确相等</b>断言；mrr/map 用 Go 的
 * almostEqual(1e-6)；NDCG 含对数，用 1e-9。</p>
 */
class RetrievalMetricsTest {

    private final PrecisionMetric precision = new PrecisionMetric();
    private final RecallMetric recall = new RecallMetric();
    private final MrrMetric mrr = new MrrMetric();
    private final MapMetric map = new MapMetric();

    @Test
    @DisplayName("Precision：Go precision_test 五个用例")
    void precisionMatchesGoCases() {
        assertEquals(1.0, precision.compute(
                MetricInput.retrieval(List.of(List.of(1, 3, 5)), List.of(1, 3, 5))), 0.0);
        assertEquals(0.6666666666666666, precision.compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3)), List.of(1, 4, 2))), 0.0);
        assertEquals(0.0, precision.compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3)), List.of(4, 5, 6))), 0.0);
        assertEquals(0.0, precision.compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3)), List.of())), 0.0);
        assertEquals(0.3333333333333333, precision.compute(
                MetricInput.retrieval(List.of(List.of(1, 2), List.of(3, 4)), List.of(1, 3, 5))), 0.0);
    }

    @Test
    @DisplayName("Recall：Go recall_test 五个用例")
    void recallMatchesGoCases() {
        assertEquals(1.0, recall.compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3)), List.of(1, 2, 3, 4))), 0.0);
        // (1/3 + 1/2) / 2 = 0.41666666666666663
        assertEquals(0.41666666666666663, recall.compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3), List.of(4, 5)), List.of(1, 4, 6))), 0.0);
        assertEquals(0.0, recall.compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3)), List.of(4, 5, 6))), 0.0);
        assertEquals(0.0, recall.compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3)), List.of())), 0.0);
        assertEquals(0.3333333333333333, recall.compute(
                MetricInput.retrieval(List.of(List.of(1, 2), List.of(3, 4), List.of(5, 6)),
                        List.of(1, 3, 7))), 0.0);
    }

    @Test
    @DisplayName("MRR：Go mrr_test 五个用例")
    void mrrMatchesGoCases() {
        assertEquals(1.0, mrr.compute(
                MetricInput.retrieval(List.of(List.of(1, 2)), List.of(1, 2, 3))), 1e-6);
        assertEquals(0.5, mrr.compute(
                MetricInput.retrieval(List.of(List.of(1, 2)), List.of(3, 1, 2))), 1e-6);
        assertEquals(0.0, mrr.compute(
                MetricInput.retrieval(List.of(List.of(1, 2)), List.of(3, 4))), 1e-6);
        // (1.0 + 0.5)/2 = 0.75
        assertEquals(0.75, mrr.compute(
                MetricInput.retrieval(List.of(List.of(1, 2), List.of(3, 4)), List.of(1, 3, 2, 4))), 1e-6);
        assertEquals(0.0, mrr.compute(
                MetricInput.retrieval(List.of(), List.of(1, 2))), 1e-6);
    }

    @Test
    @DisplayName("MAP：Go map_test 五个用例")
    void mapMatchesGoCases() {
        assertEquals(1.0, map.compute(
                MetricInput.retrieval(List.of(List.of(2, 4, 6)), List.of(2, 4, 6))), 1e-6);
        assertEquals(0.0, map.compute(
                MetricInput.retrieval(List.of(List.of(1, 2)), List.of(3, 4))), 1e-6);
        // AP = (1/1 + 2/3 + 3/4)/3 ≈ 0.80555555
        assertEquals(0.8055555555555555, map.compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3)), List.of(2, 5, 1, 3))), 1e-6);
        assertEquals(0.0, map.compute(
                MetricInput.retrieval(List.of(), List.of(1, 2))), 1e-6);
        // (0.8333 + 0.5)/2 ≈ 0.6667
        assertEquals(0.6666666666666666, map.compute(
                MetricInput.retrieval(List.of(List.of(1, 2), List.of(3, 4)), List.of(1, 3, 2, 4))), 1e-6);
    }

    @Test
    @DisplayName("NDCG：命中序即理想序（全相关）→ 1.0")
    void ndcgPerfectOrder() {
        // dcg 与 idcg 同式同值 → 比值精确为 1
        assertEquals(1.0, new NdcgMetric(3).compute(
                MetricInput.retrieval(List.of(List.of(1, 2, 3)), List.of(1, 2, 3))), 0.0);
    }

    @Test
    @DisplayName("NDCG：相关项在第 2 位 → 1/log2(3)")
    void ndcgRelevantAtSecondPosition() {
        // dcg = 1/log2(3)；idcg = 1/log2(2) = 1
        assertEquals(0.6309297535714574, new NdcgMetric(3).compute(
                MetricInput.retrieval(List.of(List.of(10)), List.of(5, 10))), 1e-9);
    }

    @Test
    @DisplayName("NDCG：top-k 截断——k=3 截掉第 4 位的相关项（0.0），k=10 命中（1/log2(5)）")
    void ndcgTopKTruncation() {
        assertEquals(0.0, new NdcgMetric(3).compute(
                MetricInput.retrieval(List.of(List.of(1)), List.of(9, 9, 9, 1))), 0.0);
        assertEquals(0.43067655807339306, new NdcgMetric(10).compute(
                MetricInput.retrieval(List.of(List.of(1)), List.of(9, 9, 9, 1))), 1e-9);
    }

    @Test
    @DisplayName("NDCG：空真值 → idcg=0 → 返回 0（防零除分支）")
    void ndcgEmptyGroundTruth() {
        assertEquals(0.0, new NdcgMetric(3).compute(
                MetricInput.retrieval(List.of(), List.of(1))), 0.0);
    }

    @Test
    @DisplayName("公共层：splitSentences 分隔符丢弃 + splitIntoWords 的降级分词")
    void commonTokenization() {
        // 对照 Go splitSentences：中文句号/英文句点分句，分隔符本身不进句子
        assertEquals(List.of("你好", "世界"), MetricCommon.splitSentences("你好。世界。"));
        assertEquals(List.of("Hello", "World"), MetricCommon.splitSentences("Hello. World"));
        // 英文块 + 标点在 english 字符类内（逗号/叹号随块）→ 单 token
        assertEquals(List.of("hello,", "world!"),
                MetricCommon.splitIntoWords(List.of("hello, world!")));
        // 中文块走分词接缝：默认降级 = 二字滑窗，末尾单字也算一段（同 SearchTextUtil 语义）
        assertEquals(List.of("计算", "算机", "机"), MetricCommon.splitIntoWords(List.of("计算机")));
    }
}
