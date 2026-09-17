package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 返回给浏览器的目录节点（对照 Go types.WikiFolderNode，internal/types/wiki_page.go
 * L441-445）。
 *
 * <p>Go 用<b>匿名嵌入</b> WikiFolder，因此 JSON 是扁平结构（folder 字段与
 * pageCount/hasChildren 同级）。Java 用 {@link JsonUnwrapped} 复刻同样的扁平化：
 * 序列化时 WikiFolder 的字段直接铺开在对象顶层，键名沿用 WikiFolder 的
 * {@code @JsonProperty}。</p>
 *
 * <p>额外带两个字段，让 UI 不必二次请求就能渲染展开箭头：<b>直接</b>位于本文件夹下的
 * 活跃页面数，以及是否有子文件夹。</p>
 */
@JsonPropertyOrder({"id", "tenant_id", "knowledge_base_id", "parent_id", "name", "path", "depth",
        "sort_order", "created_at", "updated_at", "deleted_at", "page_count", "has_children"})
public class WikiFolderNode {

    @JsonUnwrapped
    private WikiFolder folder;

    @JsonProperty("page_count")
    private long pageCount;

    @JsonProperty("has_children")
    private boolean hasChildren;

    public WikiFolderNode() {
        this(new WikiFolder(), 0L, false);
    }

    public WikiFolderNode(WikiFolder folder, long pageCount, boolean hasChildren) {
        this.folder = folder == null ? new WikiFolder() : folder;
        this.pageCount = pageCount;
        this.hasChildren = hasChildren;
    }

    public WikiFolder getFolder() { return folder; }
    public void setFolder(WikiFolder v) { this.folder = v == null ? new WikiFolder() : v; }

    public long getPageCount() { return pageCount; }
    public void setPageCount(long v) { this.pageCount = v; }

    /** ⚠️ 属性名必须是 hasChildren 而不是 isHasChildren（JSON 键 has_children 已由注解锁定） */
    public boolean isHasChildren() { return hasChildren; }
    public void setHasChildren(boolean v) { this.hasChildren = v; }
}
