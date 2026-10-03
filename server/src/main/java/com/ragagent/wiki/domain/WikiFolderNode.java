package com.ragagent.wiki.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 返回给浏览器的目录节点。JSON 键为 snake（前端按此解析）。
 *
 * <p>JSON 是扁平结构：{@code folder} 字段经 {@link JsonUnwrapped} 铺开，
 * WikiFolder 的字段直接出现在对象顶层（与 pageCount/hasChildren 同级），
 * 键名沿用 WikiFolder 的 {@code @JsonProperty}。</p>
 *
 * <p>额外带两个字段，让 UI 不必二次请求就能渲染展开箭头：<b>直接</b>位于本文件夹下的
 * 活跃页面数，以及是否有子文件夹。</p>
 */
public class WikiFolderNode {

    @JsonUnwrapped
    private WikiFolder folder;

    private long pageCount;

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
