package com.ragagent.chatpipeline;

import java.util.List;

import com.ragagent.searchutil.SearchTextUtil;

/**
 * 查询扩展的分词接缝（对照 Go {@code types.Jieba.CutForSearch(s, true)}，
 * internal/types/evaluation.go:8-26 的全局 jieba 实例）。
 *
 * <p>searchutil.SearchTextUtil 的分词器实例是包私有静态字段，没有公开的
 * CutForSearch 出口；chatpipeline 不能为加一个 getter 改 searchutil（纪律 #9），
 * 故在本包立一个同款接缝：默认实现=SearchTextUtil.UnavailableSegmenter（二字滑窗，
 * 4.4 的既有降级），测试/4.6d 装配可注入真实分词器。与 Go 的 jieba 分词边界
 * 会分叉（已知降级，同 4.4 备案）；expansion 实录组的分词语料由注入恢复。</p>
 */
public final class QueryTokenizer {

    private static volatile SearchTextUtil.Segmenter segmenter =
            new SearchTextUtil.UnavailableSegmenter();

    private QueryTokenizer() {}

    public static void setSegmenter(SearchTextUtil.Segmenter s) {
        if (s != null) {
            segmenter = s;
        }
    }

    /** 对照 Jieba.CutForSearch(text, true)。 */
    public static List<String> cutForSearch(String text) {
        return segmenter.cutForSearch(text);
    }
}
