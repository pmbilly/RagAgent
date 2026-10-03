package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code "text"} 型富文本的内容。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。
 * 注意 {@link #link} 被解析出来却**从未被读**（链接的渲染走的是 {@code href}），
 * 保留形状只为对齐 JSON 结构。</p>
 */
public final class NotionTextContent {

    @JsonProperty("content")
    public String content;

    @JsonProperty("link")
    public Link link;

    public String content() {
        return content == null ? "" : content;
    }

    /** 只带 {@code url} 的链接引用。 */
    public static final class Link {
        @JsonProperty("url")
        public String url;
    }
}
