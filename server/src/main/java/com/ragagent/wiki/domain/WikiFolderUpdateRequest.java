package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 重命名 / 移动文件夹的请求。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>{@code parentId} <b>只在 moveParent 为 true 时生效</b>，这样纯重命名不必重发
 * （可能是根 "" 的）父 id，避免意外的移动。</p>
 */
@JsonPropertyOrder({"name", "parent_id", "move_parent"})
public record WikiFolderUpdateRequest(
        @JsonProperty("name") String name,
        @JsonProperty("parent_id") String parentId,
        @JsonProperty("move_parent") boolean moveParent) {
}
