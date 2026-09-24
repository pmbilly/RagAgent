package com.ragagent.evaluation.metric;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文分词接缝（对照 Go {@code types.Jieba.Cut(block, true)}，internal/types/evaluation.go:8-26
 * 的全局 jieba 实例，默认模式 + HMM）。
 *
 * <p>Go 的 gojieba 绑定 C++ 词典分词；Java 侧无真实 jieba（同 searchutil.SearchTextUtil
 * 的既有降级），故默认实现为<b>二字滑窗近似</b>——与 Go 的分词边界会分叉：
 * 受影响的只有 BLEU/ROUGE（生成类指标），<b>已知降级（备案）</b>；检索类指标
 * （Precision/Recall/NDCG/MRR/MAP）不经过分词，恒与 Go 逐值一致。
 * 单测/联调可经 {@link #setSegmenter} 注入真实分词器恢复。</p>
 *
 * <p>纪律同 chatpipeline.QueryTokenizer：不在 searchutil 上加出口，在本包立同款接缝。</p>
 */
public final class MetricSegmenter {

    private MetricSegmenter() {
    }

    /** 分词接口：对照 Jieba.Cut(text, true)。 */
    public interface Segmenter {
        List<String> cut(String text);
    }

    /** 默认实现：jieba 不可用 → 二字滑窗（滑窗语义与 SearchTextUtil.UnavailableSegmenter 一致）。 */
    public static final class UnavailableSegmenter implements Segmenter {
        @Override
        public List<String> cut(String text) {
            List<String> out = new ArrayList<>();
            int[] runes = text.codePoints().toArray();
            int i = 0;
            while (i < runes.length) {
                if (isHan(runes[i])) {
                    // CJK 连段：二字滑窗（末尾单字也算一段）
                    int j = i;
                    while (j < runes.length && isHan(runes[j])) {
                        j++;
                    }
                    for (int k = i; k < j; k++) {
                        StringBuilder b = new StringBuilder();
                        b.appendCodePoint(runes[k]);
                        if (k + 1 < j) {
                            b.appendCodePoint(runes[k + 1]);
                        }
                        out.add(b.toString());
                    }
                    i = j;
                } else {
                    out.add(new String(runes, i, 1));
                    i++;
                }
            }
            return out;
        }
    }

    private static volatile Segmenter segmenter = new UnavailableSegmenter();

    public static void setSegmenter(Segmenter s) {
        if (s != null) {
            segmenter = s;
        }
    }

    static List<String> cut(String text) {
        return segmenter.cut(text);
    }

    /** CJK 区块判定（Basic + 扩展 A + 兼容 + 扩展 B），同 searchutil.SearchTextUtil.isHan。 */
    private static boolean isHan(int r) {
        return (r >= 0x3400 && r <= 0x4DBF)
                || (r >= 0x4E00 && r <= 0x9FFF)
                || (r >= 0xF900 && r <= 0xFAFF)
                || (r >= 0x20000 && r <= 0x2FA1F);
    }
}
