package com.ragagent.evaluation.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生成类指标（BLEU/ROUGE）测试：Go 侧无对应测试文件，用例全部用<b>纯英文</b>输入
 * （分词确定：英文块按空白切 + 标点成 token），保证不依赖中文分词接缝的降级差异；
 * 期望值按源码公式手算（注释给出推导）。
 */
class GenerationMetricsTest {

    @Test
    @DisplayName("BLEU-1/4：完全一致 → 1.0")
    void bleuIdenticalText() {
        assertEquals(1.0, new BleuMetric(true, BleuMetric.BLEU1_GRAM).compute(
                MetricInput.generation("the cat sat", "the cat sat")), 1e-12);
        assertEquals(1.0, new BleuMetric(true, BleuMetric.BLEU4_GRAM).compute(
                MetricInput.generation("the cat sat", "the cat sat")), 1e-12);
    }

    @Test
    @DisplayName("BLEU-1：空生成文本 → 无 n-gram → 0")
    void bleuEmptyCandidate() {
        assertEquals(0.0, new BleuMetric(true, BleuMetric.BLEU1_GRAM).compute(
                MetricInput.generation("", "the cat sat")), 0.0);
    }

    @Test
    @DisplayName("BLEU-1：完全无重叠 + smoothing → 精确度 (0+1)/(1+1) → exp(ln 0.5) = 0.5")
    void bleuSmoothingNoOverlap() {
        // candidate=[a]，reference=[b]：clipped=0，counts=1 → (0+1)/(1+1)=0.5
        // bp：c=1, r=1 → exp(1-1)=1 → 结果 = exp(1.0*ln(0.5)) = 0.5
        assertEquals(0.5, new BleuMetric(true, BleuMetric.BLEU1_GRAM).compute(
                MetricInput.generation("a", "b")), 1e-12);
    }

    @Test
    @DisplayName("ROUGE-1：完全一致 → f = 2·(1·1)/(2+1e-8) ≈ 1（含 Go 的 +1e-8 护栏）")
    void rouge1Identical() {
        assertEquals(1.0, new RougeMetric(true, "rouge-1", "f").compute(
                MetricInput.generation("the cat sat", "the cat sat")), 1e-6);
    }

    @Test
    @DisplayName("ROUGE-1：半重叠 → p=r=0.5, f≈0.5")
    void rouge1PartialOverlap() {
        // 去重 ngram：hyp={the,cat}(2)，ref={the,dog}(2)，overlap={the}(1)
        // p=r=1/2 → f = 2·0.25/(1+1e-8) ≈ 0.499999995
        assertEquals(0.5, new RougeMetric(true, "rouge-1", "f").compute(
                MetricInput.generation("the cat", "the dog")), 1e-6);
    }

    @Test
    @DisplayName("ROUGE-L：完全一致 → f≈1；半重叠 → 0.5")
    void rougeLMatchAndPartial() {
        assertEquals(1.0, new RougeMetric(true, "rouge-l", "f").compute(
                MetricInput.generation("the cat sat", "the cat sat")), 1e-6);
        assertEquals(0.5, new RougeMetric(true, "rouge-l", "f").compute(
                MetricInput.generation("the cat", "the dog")), 1e-6);
    }

    @Test
    @DisplayName("ROUGE-2：空生成文本 → 无 bigram → 0")
    void rouge2EmptyCandidate() {
        assertEquals(0.0, new RougeMetric(true, "rouge-2", "f").compute(
                MetricInput.generation("", "the cat sat")), 0.0);
    }
}
