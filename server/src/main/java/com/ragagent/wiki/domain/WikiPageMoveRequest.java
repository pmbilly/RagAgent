package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 把 slug 标识的页面移动到 folderID 的请求（对照 Go types.WikiPageMoveRequest，
 * internal/types/wiki_page.go L472-475）。folderID 为 "" 表示根。</p>
 *
 * <p>slug 走请求体而非路径：wiki slug 是层级化的（"entity/acme"），
 * 会和 gin 的 catch-all 路由冲突。</p>
 */
@JsonPropertyOrder({"slug", "folder_id"})
public record WikiPageMoveRequest(
        @JsonProperty("slug") String slug,
        @JsonProperty("folder_id") String folderId) {
}
