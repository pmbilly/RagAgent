package com.ragagent.retrieval.domain;

import java.time.OffsetDateTime;


/**
 * 网络搜索结果条目（对照 Go {@code types.WebSearchResult}，
 * internal/types/web_search.go L84-96）。
 *
 * <p>字段序 = Go struct 声明序；{@code age} / {@code published_at} 带 omitempty
 * （空时省略整键）。不落库、不作响应体的内部承载类型——注解形状按契约保留，
 * 供后续检索/agent 波次直接复用。</p>
 */
public class WebSearchResult {

    private String title = "";

    private String url = "";

    private String snippet = "";

    private String content = "";

    private String source = "";

    /** Provider 报告的相对年龄，不臆造精确发布时间（omitempty：空串省略）。 */
    private String age = "";

        private OffsetDateTime publishedAt;

    public String getTitle() { return title == null ? "" : title; }
    public void setTitle(String v) { title = v == null ? "" : v; }
    public String getUrl() { return url == null ? "" : url; }
    public void setUrl(String v) { url = v == null ? "" : v; }
    public String getSnippet() { return snippet == null ? "" : snippet; }
    public void setSnippet(String v) { snippet = v == null ? "" : v; }
    public String getContent() { return content == null ? "" : content; }
    public void setContent(String v) { content = v == null ? "" : v; }
    public String getSource() { return source == null ? "" : source; }
    public void setSource(String v) { source = v == null ? "" : v; }
    public String getAge() { return age == null ? "" : age; }
    public void setAge(String v) { age = v == null ? "" : v; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime v) { publishedAt = v; }
}
