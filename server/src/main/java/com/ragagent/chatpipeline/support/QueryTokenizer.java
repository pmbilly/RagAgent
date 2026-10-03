package com.ragagent.chatpipeline.support;

import java.util.List;

import com.ragagent.retrieval.support.SearchTextUtil;

/**
 * 查询扩展的分词接缝。
 *
 * <p>{@link SearchTextUtil} 的分词器实例是包私有静态字段，没有公开的
 * cutForSearch 出口；chatpipeline 不为其加 getter，在本包立一个同款接缝：
 * 默认实现 = {@code SearchTextUtil.UnavailableSegmenter}（二字滑窗，已知降级，
 * 分词边界与 jieba 版分词有差异），测试/装配可注入真实分词器恢复。</p>
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

    public static List<String> cutForSearch(String text) {
        return segmenter.cutForSearch(text);
    }
}
