package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 在 parentID 下新建（初始为空）文件夹的请求（对照 Go types.WikiFolderCreateRequest，
 * internal/types/wiki_page.go L455-458）。
 */
@JsonPropertyOrder({"parent_id", "name"})
public record WikiFolderCreateRequest(
        @JsonProperty("parent_id") String parentId,
        @JsonProperty("name") String name) {
}
