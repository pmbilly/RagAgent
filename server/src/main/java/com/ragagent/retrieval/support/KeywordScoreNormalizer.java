package com.ragagent.retrieval.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词分数的稳健归一化（对照 Go {@code internal/searchutil/normalize.go} 全文）。
 *
 * <p>泛型签名用函数式取/设分数复刻（Go 的 {@code isKeyword/getScore/setScore} 三
 * 回调 + {@code KeywordScoreCallbacks}）。规则逐条对照：单条置 1；无方差全置 1 并
 * 触发 OnNoVariance；≥10 条时用 p5/p95 百分位界；百分位把区间压塌时全置 1。</p>
 */
public final class KeywordScoreNormalizer {

    private KeywordScoreNormalizer() {
    }

    /** 对照 KeywordScoreCallbacks（可空回调）。 */
    public interface Callbacks {
        default void onNoVariance(int count, double score) {
        }

        default void onNormalized(int count, double rawMin, double rawMax,
                                  double normalizeMin, double normalizeMax) {
        }
    }

    public static <T> void normalizeKeywordScores(List<T> results,
                                                  java.util.function.Predicate<T> isKeyword,
                                                  java.util.function.ToDoubleFunction<T> getScore,
                                                  java.util.function.ObjDoubleConsumer<T> setScore,
                                                  Callbacks callbacks) {
        List<T> keywordResults = new ArrayList<>(results.size());
        for (T result : results) {
            if (isKeyword.test(result)) {
                keywordResults.add(result);
            }
        }
        if (keywordResults.isEmpty()) {
            return;
        }
        if (keywordResults.size() == 1) {
            setScore.accept(keywordResults.get(0), 1.0);
            return;
        }

        double minS = getScore.applyAsDouble(keywordResults.get(0));
        double maxS = minS;
        for (T r : keywordResults.subList(1, keywordResults.size())) {
            double score = getScore.applyAsDouble(r);
            if (score < minS) {
                minS = score;
            }
            if (score > maxS) {
                maxS = score;
            }
        }

        if (maxS <= minS) {
            for (T r : keywordResults) {
                setScore.accept(r, 1.0);
            }
            if (callbacks != null) {
                callbacks.onNoVariance(keywordResults.size(), minS);
            }
            return;
        }

        double normalizeMin = minS;
        double normalizeMax = maxS;

        if (keywordResults.size() >= 10) {
            double[] scores = new double[keywordResults.size()];
            for (int i = 0; i < keywordResults.size(); i++) {
                scores[i] = getScore.applyAsDouble(keywordResults.get(i));
            }
            java.util.Arrays.sort(scores);
            int p5Idx = scores.length * 5 / 100;
            int p95Idx = scores.length * 95 / 100;
            if (p5Idx < scores.length) {
                normalizeMin = scores[p5Idx];
            }
            if (p95Idx < scores.length) {
                normalizeMax = scores[p95Idx];
            }
        }

        double rangeSize = normalizeMax - normalizeMin;
        if (rangeSize > 0) {
            for (T r : keywordResults) {
                double clamped = getScore.applyAsDouble(r);
                if (clamped < normalizeMin) {
                    clamped = normalizeMin;
                } else if (clamped > normalizeMax) {
                    clamped = normalizeMax;
                }
                double ns = (clamped - normalizeMin) / rangeSize;
                if (ns < 0) {
                    ns = 0;
                } else if (ns > 1) {
                    ns = 1;
                }
                setScore.accept(r, ns);
            }
            if (callbacks != null) {
                callbacks.onNormalized(keywordResults.size(), minS, maxS, normalizeMin, normalizeMax);
            }
            return;
        }

        // 百分位过滤把区间压塌时的回退
        for (T r : keywordResults) {
            setScore.accept(r, 1.0);
        }
    }
}
