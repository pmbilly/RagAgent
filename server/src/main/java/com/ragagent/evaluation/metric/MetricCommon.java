package com.ragagent.evaluation.metric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对照 Go metric/common.go：句切（{@code splitSentences}）、词切（{@code splitIntoWords}）、
 * 计数与集合辅助（sum/min/max/abs/ToSet/Hit）。
 *
 * <p><b>句切的 Java 差异（实现层，非行为）</b>：Go 用 {@code re.Split(text, -1)}，捕获组随
 * 切分结果返回；Java 的 {@code Pattern.split} 不返回捕获组，故手工仿真为
 * 「文本段/分隔符」交替序列，再走 Go 的奇偶累积逻辑（分隔符本身丢弃）。</p>
 */
final class MetricCommon {

    /** 对照 {@code ([。.])}：中文句号或英文句点。 */
    private static final Pattern SENTENCE_DELIM = Pattern.compile("([。.])");

    /** 对照 {@code ([\p{Han}]+)|([a-zA-Z0-9_.,!?]+)|(\p{P})}。 */
    private static final Pattern WORD_BLOCK =
            Pattern.compile("(\\p{IsHan}+)|([a-zA-Z0-9_.,!?]+)|(\\p{P})");

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private MetricCommon() {
    }

    static int sum(Map<?, Integer> m) {
        int s = 0;
        for (Integer v : m.values()) {
            s += v;
        }
        return s;
    }

    static int min(int a, int b) {
        return Math.min(a, b);
    }

    static int abs(int a) {
        return Math.abs(a);
    }

    static int max(int a, int b) {
        return Math.max(a, b);
    }

    /**
     * 对照 splitSentences：按 {@code 。}/{@code .} 切句，分隔符丢弃，段内 strip 后
     * 非空才成句（Go strings.TrimSpace → Java String.strip，均为 Unicode 空白语义）。
     */
    static List<String> splitSentences(String text) {
        // 仿真 Go re.Split 的「捕获组随切分返回」：段/分隔符交替
        Matcher m = SENTENCE_DELIM.matcher(text);
        List<String> parts = new ArrayList<>();
        int last = 0;
        while (m.find()) {
            parts.add(text.substring(last, m.start()));
            parts.add(m.group());
            last = m.end();
        }
        parts.add(text.substring(last));

        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String s = parts.get(i);
            if (i % 2 == 0) {
                current.append(s);
            } else {
                if (current.length() > 0) {
                    String sentence = current.toString().strip();
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                    current.setLength(0);
                }
            }
        }
        String remaining = current.toString().strip();
        if (!remaining.isEmpty()) {
            sentences.add(remaining);
        }
        return sentences;
    }

    /** 对照 splitIntoWords：Han 块走分词器、英文块按空白切（fields）、标点成 token。 */
    static List<String> splitIntoWords(List<String> sentences) {
        List<String> tokens = new ArrayList<>();
        for (String text : sentences) {
            Matcher m = WORD_BLOCK.matcher(text);
            while (m.find()) {
                String chineseBlock = m.group(1);
                String englishBlock = m.group(2);
                String punctuation = m.group(3);
                if (chineseBlock != null) {
                    tokens.addAll(MetricSegmenter.cut(chineseBlock));
                } else if (englishBlock != null) {
                    tokens.addAll(fields(englishBlock));
                } else if (punctuation != null) {
                    tokens.add(punctuation);
                }
            }
        }
        return tokens;
    }

    /** 对照 strings.Fields：按 Unicode 空白切分并丢弃空段。 */
    private static List<String> fields(String s) {
        List<String> out = new ArrayList<>();
        for (String part : WHITESPACE.split(s, -1)) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    static <T> Set<T> toSet(List<T> li) {
        return new HashSet<>(li);
    }

    static <T> int hit(List<T> li, Set<T> set) {
        int count = 0;
        for (T v : li) {
            if (set.contains(v)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 对照 Go {@code math.Log2}（frexp 归一 + {@code Log(frac)*(1/Ln2)} 缩放，
     * 2 的整数幂走精确路径）——避免 {@code log(x)/log(2)} 在整数幂上的末位抖动。
     * libm 实现差异仍可能造成末位分叉（无法消除，备案）。
     */
    static double log2(double x) {
        if (x == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        // Java: x = m × 2^e（m ∈ [1,2)）；Go Frexp: x = frac × 2^exp（frac ∈ [0.5,1)）
        int e = Math.getExponent(x);
        double m = Math.scalb(x, -e);
        double frac = m / 2.0;
        int exp = e + 1;
        if (frac == 0.5) {
            return (double) (exp - 1);
        }
        return (double) exp + Math.log(frac) * (1.0 / Math.log(2.0));
    }
}
