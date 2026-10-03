package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;


/**
 * 列出某文件夹直接子节点的响应。JSON 键为 snake（前端按此解析）；
 * {@code parent_id = ""} 即根层级。
 */
public class WikiFolderListResponse {

    private String parentId = "";

    private List<WikiFolderNode> folders = new ArrayList<>();

    public String getParentId() { return parentId; }
    public void setParentId(String v) { this.parentId = v == null ? "" : v; }

    public List<WikiFolderNode> getFolders() { return folders; }
    public void setFolders(List<WikiFolderNode> v) { this.folders = v == null ? new ArrayList<>() : v; }
}
