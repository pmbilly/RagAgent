package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code "text"} 型富文本的内容（对照 Go {@code notionTextContent}，types.go L177-182）。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。
 * 注意 {@link #link} 是 Go 的匿名结构体指针（只在 {@code richTextToString} 里
 * 被解出来却**从未被读**——链接的渲染走的是 {@code rt.Href}），
 * Java 侧保留同样的形状只为对齐 JSON 结构。</p>
 */
public final class NotionTextContent {

    @JsonProperty("content")
    public String content;

    @JsonProperty("link")
    public Link link;

    public String content() {
        return content == null ? "" : content;
    }

    /** 对照 Go 的匿名结构体 {@code *struct{ URL string `json:"url"` }}。 */
    public static final class Link {
        @JsonProperty("url")
        public String url;
    }
}
