package com.ragagent.searchutil;

import java.util.Set;
import java.util.regex.Pattern;

import com.ragagent.knowledge.service.ChunkSearchUtil;

/**
 * 对 {@code knowledge.service.ChunkSearchUtil}（波 2 落地的 searchutil 前半部分）的
 * 复用桥。Go 里这些函数与两条图片正则同属 {@code internal/searchutil}；Java 侧该批
 * 落在 knowledge.service，迁移涉及外部调用面，本批以桥接复用不重复实现（报告已列明）。
 */
final class ChunkSearchUtilBridge {

    static final Pattern MARKDOWN_IMAGE_REGEX = ChunkSearchUtil.MARKDOWN_IMAGE_REGEX;
    static final Pattern HTML_IMAGE_SRC_REGEX = ChunkSearchUtil.HTML_IMAGE_SRC_REGEX;

    private ChunkSearchUtilBridge() {
    }

    static Set<String> imageURLsInContent(String content) {
        return ChunkSearchUtil.imageURLsInContent(content);
    }

    static Set<String> imageURLsFromInfo(String imageInfoJson) {
        return ChunkSearchUtil.imageURLsFromInfo(imageInfoJson);
    }
}
