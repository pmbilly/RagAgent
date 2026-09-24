package com.ragagent.evaluation.metric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对照 Go metric/rouge_score.go（Google rouge metric 的 Go 移植）：
 * Ngrams 集合、LCS 重建、rouge-N 与 rouge-L 的 summary-level 计算。
 *
 * <p>照抄的 Go 语义要点：{@code Ngrams.Len()} 是<b>去重键数</b>（非总出现次数）；
 * {@code reconLcs} 的贡献也按去重词数计；exclusive=true 时 {@code Add} 只置存在性。</p>
 */
public final class RougeScore {

    private RougeScore() {
    }

    /** ROUGE 指标函数（对照 AvailableMetrics 的函数签名）。 */
    public interface RougeFn {
        Map<String, Double> apply(List<String> hyp, List<String> ref, boolean exclusive);
    }

    /** 对照 AvailableMetrics：rouge-1..5 + rouge-l（计算侧只用 1/2/l）。 */
    public static final Map<String, RougeFn> AVAILABLE_METRICS = Map.of(
            "rouge-1", (hyp, ref, exclusive) -> rougeN(hyp, ref, 1, false, exclusive),
            "rouge-2", (hyp, ref, exclusive) -> rougeN(hyp, ref, 2, false, exclusive),
            "rouge-3", (hyp, ref, exclusive) -> rougeN(hyp, ref, 3, false, exclusive),
            "rouge-4", (hyp, ref, exclusive) -> rougeN(hyp, ref, 4, false, exclusive),
            "rouge-5", (hyp, ref, exclusive) -> rougeN(hyp, ref, 5, false, exclusive),
            "rouge-l", (hyp, ref, exclusive) -> rougeLSummaryLevel(hyp, ref, false, exclusive));

    /** 对照 Ngrams（exclusive 时 Add 置 1；Len = 去重键数）。 */
    static final class Ngrams {

        private final Map<String, Integer> ngrams = new HashMap<>();
        private final boolean exclusive;

        Ngrams(boolean exclusive) {
            this.exclusive = exclusive;
        }

        void add(String o) {
            if (exclusive) {
                ngrams.put(o, 1);
            } else {
                ngrams.merge(o, 1, Integer::sum);
            }
        }

        int length() {
            return ngrams.size();
        }

        Ngrams intersection(Ngrams o) {
            Ngrams intersection = new Ngrams(exclusive);
            for (String k : ngrams.keySet()) {
                if (o.ngrams.containsKey(k)) {
                    intersection.add(k);
                }
            }
            return intersection;
        }

        void batchAdd(List<String> o) {
            for (String v : o) {
                add(v);
            }
        }

        Ngrams union(Ngrams... others) {
            Ngrams union = new Ngrams(exclusive);
            for (String k : ngrams.keySet()) {
                union.add(k);
            }
            for (Ngrams other : others) {
                for (String k : other.ngrams.keySet()) {
                    union.add(k);
                }
            }
            return union;
        }
    }

    /** 对照 getNgrams：n-gram（以空格连接）集合。 */
    static Ngrams getNgrams(int n, List<String> text, boolean exclusive) {
        Ngrams ngramSet = new Ngrams(exclusive);
        for (int i = 0; i <= text.size() - n; i++) {
            ngramSet.add(String.join(" ", text.subList(i, i + n)));
        }
        return ngramSet;
    }

    /** 对照 getWordNgrams：先 splitIntoWords 再取 n-gram。 */
    static Ngrams getWordNgrams(int n, List<String> sentences, boolean exclusive) {
        List<String> words = MetricCommon.splitIntoWords(sentences);
        return getNgrams(n, words, exclusive);
    }

    /** 对照 lcs：DP 表。 */
    static int[][] lcs(List<String> x, List<String> y) {
        int n = x.size();
        int m = y.size();
        int[][] table = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (x.get(i - 1).equals(y.get(j - 1))) {
                    table[i][j] = table[i - 1][j - 1] + 1;
                } else {
                    table[i][j] = Math.max(table[i - 1][j], table[i][j - 1]);
                }
            }
        }
        return table;
    }

    /** 对照 reconLcs：LCS 回溯 → 去重词集合（贡献按去重词数计，同 Go）。 */
    static Ngrams reconLcs(List<String> x, List<String> y, boolean exclusive) {
        int[][] table = lcs(x, y);
        List<String> reconList = reconFunc(x, y, table, x.size(), y.size());
        Ngrams ngramList = new Ngrams(exclusive);
        for (String word : reconList) {
            ngramList.add(word);
        }
        return ngramList;
    }

    private static List<String> reconFunc(List<String> x, List<String> y, int[][] table, int i, int j) {
        if (i == 0 || j == 0) {
            return new ArrayList<>();
        } else if (x.get(i - 1).equals(y.get(j - 1))) {
            List<String> out = reconFunc(x, y, table, i - 1, j - 1);
            out.add(x.get(i - 1));
            return out;
        } else if (table[i - 1][j] > table[i][j - 1]) {
            return reconFunc(x, y, table, i - 1, j);
        } else {
            return reconFunc(x, y, table, i, j - 1);
        }
    }

    /** 对照 rougeN（rawResults=false 分支为计算侧唯一路径）。 */
    static Map<String, Double> rougeN(List<String> evaluatedSentences, List<String> referenceSentences,
                                      int n, boolean rawResults, boolean exclusive) {
        Ngrams evaluatedNgrams = getWordNgrams(n, evaluatedSentences, exclusive);
        Ngrams referenceNgrams = getWordNgrams(n, referenceSentences, exclusive);
        int referenceCount = referenceNgrams.length();
        int evaluatedCount = evaluatedNgrams.length();

        Ngrams overlappingNgrams = evaluatedNgrams.intersection(referenceNgrams);
        int overlappingCount = overlappingNgrams.length();

        Map<String, Double> results = new HashMap<>();
        if (rawResults) {
            results.put("hyp", (double) evaluatedCount);
            results.put("ref", (double) referenceCount);
            results.put("overlap", (double) overlappingCount);
            return results;
        }
        return calculateRougeN(evaluatedCount, referenceCount, overlappingCount);
    }

    /** 对照 calculateRougeN（f 里的 +1e-8 防零除照抄）。 */
    static Map<String, Double> calculateRougeN(int evaluatedCount, int referenceCount,
                                               int overlappingCount) {
        Map<String, Double> results = new HashMap<>();
        if (evaluatedCount == 0) {
            results.put("p", 0.0);
        } else {
            results.put("p", (double) overlappingCount / (double) evaluatedCount);
        }
        if (referenceCount == 0) {
            results.put("r", 0.0);
        } else {
            results.put("r", (double) overlappingCount / (double) referenceCount);
        }
        results.put("f", 2.0 * ((results.get("p") * results.get("r"))
                / (results.get("p") + results.get("r") + 1e-8)));
        return results;
    }

    /** unionLcs 的返回对（对照 Go 的 (int, *Ngrams)）。 */
    record UnionLcs(int newLcsCount, Ngrams union) {
    }

    /**
     * 对照 unionLcs：逐句对参考句做 LCS 重建并入并集，增量 = 并集去重词数差。
     * （Go 里 combinedLcsLength 累加后未使用——照抄时省去死计算。）
     */
    static UnionLcs unionLcs(List<String> evaluatedSentences, String referenceSentence,
                             Ngrams prevUnion, boolean exclusive) {
        Ngrams lcsUnion = prevUnion == null ? new Ngrams(exclusive) : prevUnion;
        int prevCount = lcsUnion.length();
        List<String> referenceWords = MetricCommon.splitIntoWords(List.of(referenceSentence));

        for (String evalS : evaluatedSentences) {
            List<String> evaluatedWords = MetricCommon.splitIntoWords(List.of(evalS));
            Ngrams lcs = reconLcs(referenceWords, evaluatedWords, exclusive);
            lcsUnion = lcsUnion.union(lcs);
        }

        return new UnionLcs(lcsUnion.length() - prevCount, lcsUnion);
    }

    /** 对照 rougeLSummaryLevel（rawResults=false 分支为计算侧唯一路径）。 */
    static Map<String, Double> rougeLSummaryLevel(List<String> evaluatedSentences,
                                                  List<String> referenceSentences,
                                                  boolean rawResults, boolean exclusive) {
        Ngrams referenceNgrams = new Ngrams(exclusive);
        referenceNgrams.batchAdd(MetricCommon.splitIntoWords(referenceSentences));
        int m = referenceNgrams.length();

        Ngrams evaluatedNgrams = new Ngrams(exclusive);
        evaluatedNgrams.batchAdd(MetricCommon.splitIntoWords(evaluatedSentences));
        int n = evaluatedNgrams.length();

        int unionLcsSumAcrossAllReferences = 0;
        Ngrams union = new Ngrams(exclusive);
        for (String refS : referenceSentences) {
            UnionLcs result = unionLcs(evaluatedSentences, refS, union, exclusive);
            union = result.union();
            unionLcsSumAcrossAllReferences += result.newLcsCount();
        }
        int llcs = unionLcsSumAcrossAllReferences;

        double rLcs;
        if (m == 0) {
            rLcs = 0.0;
        } else {
            rLcs = (double) llcs / (double) m;
        }
        double pLcs;
        if (n == 0) {
            pLcs = 0.0;
        } else {
            pLcs = (double) llcs / (double) n;
        }

        double fLcs = 2.0 * ((pLcs * rLcs) / (pLcs + rLcs + 1e-8));

        Map<String, Double> results = new HashMap<>();
        if (rawResults) {
            results.put("hyp", (double) n);
            results.put("ref", (double) m);
            results.put("overlap", (double) llcs);
            return results;
        }
        results.put("f", fLcs);
        results.put("p", pLcs);
        results.put("r", rLcs);
        return results;
    }
}
