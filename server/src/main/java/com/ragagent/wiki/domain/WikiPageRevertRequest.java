package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 把页面回滚到某个已存修订的请求（对照 Go types.WikiPageRevertRequest，
 * internal/types/wiki_page.go L405-408）。
 *
 * <p>slug 走请求体而非路径，理由同 {@link WikiPageMoveRequest}：
 * 层级化 slug（"entity/acme"）会和 gin 的 catch-all 路由冲突。</p>
 *
 * <p>Go 的 {@code binding:"required"} 对应 Jakarta Validation 的 {@code @NotNull}；
 * 本类刻意不加校验注解，由 controller 层按 Go 的绑定行为决定。</p>
 */
@JsonPropertyOrder({"slug", "version"})
public record WikiPageRevertRequest(
        @JsonProperty("slug") String slug,
        @JsonProperty("version") int version) {
}
