package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 列出某文件夹直接子节点的响应。JSON 键为 snake（§11 登记边界，前端按此解析）；
 * {@code parent_id = ""} 即根层级。
 */
@JsonPropertyOrder({"parent_id", "folders"})
public class WikiFolderListResponse {

    @JsonProperty("parent_id")
    private String parentId = "";

    @JsonProperty("folders")
    private List<WikiFolderNode> folders = new ArrayList<>();

    public String getParentId() { return parentId; }
    public void setParentId(String v) { this.parentId = v == null ? "" : v; }

    public List<WikiFolderNode> getFolders() { return folders; }
    public void setFolders(List<WikiFolderNode> v) { this.folders = v == null ? new ArrayList<>() : v; }
}
