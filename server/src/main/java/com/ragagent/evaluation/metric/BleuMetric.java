package com.ragagent.evaluation.metric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对照 Go metric.BLEUMetric（bleu.go 全文，算法源自 NLTK align/bleu）
 * 与 metric_hook.go 里的三档权重用法（BLEU-1/2/4，smoothing=true）。
 *
 * <p>ngram 键：Go 用 {@code phrase.String()}（JSON 数组字符串）；Java 用
 * {@code List<String>}（值等价键，HashMap 语义等价）。</p>
 */
public final class BleuMetric implements Metrics {

    /** 对照 BLEU1Gram/BLEU2Gram/BLEU3Gram/BLEU4Gram 权重表。 */
    public static final double[] BLEU1_GRAM = {1.0, 0.0, 0.0, 0.0};

    public static final double[] BLEU2_GRAM = {0.5, 0.5, 0.0, 0.0};

    public static final double[] BLEU3_GRAM = {0.33, 0.33, 0.33, 0.0};

    public static final double[] BLEU4_GRAM = {0.25, 0.25, 0.25, 0.25};

    private final boolean smoothing;
    private final double[] weights;

    public BleuMetric(boolean smoothing, double[] weights) {
        this.smoothing = smoothing;
        this.weights = weights;
    }

    @Override
    public double compute(MetricInput input) {
        List<String> candidate = lower(MetricCommon.splitIntoWords(
                MetricCommon.splitSentences(input.generatedTexts)));
        List<List<String>> references = new ArrayList<>();
        references.add(lower(MetricCommon.splitIntoWords(
                MetricCommon.splitSentences(input.generatedGT))));

        double[] ps = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            ps[i] = modifiedPrecision(candidate, references, i + 1);
        }

        double s = 0.0;
        int overlap = 0;
        for (int i = 0; i < weights.length; i++) {
            double w = weights[i];
            double pn = ps[i];
            if (pn > 0.0) {
                overlap++;
                s += w * Math.log(pn);
            }
        }

        if (overlap == 0) {
            return 0;
        }

        double bp = brevityPenalty(candidate, references);
        return bp * Math.exp(s);
    }

    private static List<String> lower(List<String> tokens) {
        List<String> out = new ArrayList<>(tokens.size());
        for (String t : tokens) {
            out.add(t.toLowerCase());
        }
        return out;
    }

    /** 对照 getNphrase：全部 n-gram（保序、含重复）。 */
    private static List<List<String>> getNphrase(List<String> s, int n) {
        List<List<String>> nphrase = new ArrayList<>();
        for (int i = 0; i < s.size() - n + 1; i++) {
            nphrase.add(List.copyOf(s.subList(i, i + n)));
        }
        return nphrase;
    }

    /** 对照 countNphrase：ngram → 出现次数。 */
    private static Map<List<String>, Integer> countNphrase(List<List<String>> nphrase) {
        Map<List<String>, Integer> counts = new HashMap<>();
        for (List<String> gram : nphrase) {
            counts.merge(gram, 1, Integer::sum);
        }
        return counts;
    }

    private double modifiedPrecision(List<String> candidate, List<List<String>> references, int n) {
        List<List<String>> nphrase = getNphrase(candidate, n);
        if (nphrase.isEmpty()) {
            return 0.0;
        }

        Map<List<String>, Integer> counts = countNphrase(nphrase);
        if (counts.isEmpty()) {
            return 0.0;
        }

        Map<List<String>, Integer> maxCounts = new HashMap<>();
        for (List<String> reference : references) {
            Map<List<String>, Integer> referenceCounts = countNphrase(getNphrase(reference, n));
            for (List<String> ngram : counts.keySet()) {
                Integer existing = maxCounts.get(ngram);
                int refCount = referenceCounts.getOrDefault(ngram, 0);
                if (existing == null) {
                    maxCounts.put(ngram, refCount);
                } else if (existing < refCount) {
                    maxCounts.put(ngram, refCount);
                }
            }
        }

        Map<List<String>, Integer> clippedCounts = new HashMap<>();
        for (Map.Entry<List<String>, Integer> e : counts.entrySet()) {
            clippedCounts.put(e.getKey(),
                    MetricCommon.min(e.getValue(), maxCounts.getOrDefault(e.getKey(), 0)));
        }

        double smoothingFactor = smoothing ? 1.0 : 0.0;
        return (MetricCommon.sum(clippedCounts) + smoothingFactor)
                / (MetricCommon.sum(counts) + smoothingFactor);
    }

    private static double brevityPenalty(List<String> candidate, List<List<String>> references) {
        int c = candidate.size();
        List<Integer> refLens = new ArrayList<>();
        for (List<String> reference : references) {
            refLens.add(reference.size());
        }
        int minDiffInd = 0;
        int minDiff = -1;
        for (int i = 0; i < refLens.size(); i++) {
            if (minDiff == -1 || MetricCommon.abs(refLens.get(i) - c) < minDiff) {
                minDiffInd = i;
                minDiff = MetricCommon.abs(refLens.get(i) - c);
            }
        }
        int r = refLens.get(minDiffInd);
        if (c > r) {
            return 1;
        }
        return Math.exp(1 - (double) r / (double) c);
    }
}
