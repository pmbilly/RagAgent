package com.ragagent.wiki.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code PUT /wiki/pages/*slug} 的部分更新载荷（对照 Go types.WikiPageUpdateRequest，
 * internal/types/wiki_page.go L379-390）。
 *
 * <p>所有内容字段都是可选指针——缺席的字段保留库中值，于是客户端可以只改正文，
 * 而不必重发（也就不会误覆盖）title / status / aliases。</p>
 *
 * <p>Java 用 record + 可空包装类型表达 Go 的指针语义：{@code null} = 字段缺席。
 * 注意 {@code aliases} 是 {@code List} 而非元素可空——Go 的 {@code *StringArray}
 * 只有整体缺席/提供两种状态。</p>
 *
 * @param version 乐观锁护栏：&gt; 0 时若库中版本不同则以冲突拒绝本次更新
 *                （客户端加载后有人改过）；0 跳过校验（兼容旧客户端）
 */
@JsonPropertyOrder({"title", "content", "summary", "page_type", "status", "aliases", "version"})
public record WikiPageUpdateRequest(
        @JsonProperty("title") String title,
        @JsonProperty("content") String content,
        @JsonProperty("summary") String summary,
        @JsonProperty("page_type") String pageType,
        @JsonProperty("status") String status,
        @JsonProperty("aliases") List<String> aliases,
        @JsonProperty("version") int version) {
}
