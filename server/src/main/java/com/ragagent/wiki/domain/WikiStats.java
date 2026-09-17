package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * wiki 的聚合统计（对照 Go types.WikiStats，internal/types/wiki_page.go L728-737）。
 *
 * <p>统计口径：Go repository 的 CountByType / CountOrphans 都带
 * {@code status <> 'archived'}，即<b>排除归档页</b>。</p>
 */
@JsonPropertyOrder({"total_pages", "pages_by_type", "total_links", "orphan_count",
        "recent_updates", "pending_tasks", "pending_issues", "is_active"})
public class WikiStats {

    @JsonProperty("total_pages")
    private long totalPages;

    /** 键为 page_type，值为计数；用 LinkedHashMap 保持 Go map 的键序可控 */
    @JsonProperty("pages_by_type")
    private Map<String, Long> pagesByType = new LinkedHashMap<>();

    @JsonProperty("total_links")
    private long totalLinks;

    /** 没有任何入链的页面数 */
    @JsonProperty("orphan_count")
    private long orphanCount;

    /** 最近更新的 N 个页面 */
    @JsonProperty("recent_updates")
    private List<WikiPage> recentUpdates = new ArrayList<>();

    /** 等待摄取入库的文档数 */
    @JsonProperty("pending_tasks")
    private long pendingTasks;

    /** 待处理的 wiki 问题数 */
    @JsonProperty("pending_issues")
    private long pendingIssues;

    /** wiki 摄取当前是否在运行 */
    @JsonProperty("is_active")
    private boolean isActive;

    public long getTotalPages() { return totalPages; }
    public void setTotalPages(long v) { this.totalPages = v; }

    public Map<String, Long> getPagesByType() { return pagesByType; }
    public void setPagesByType(Map<String, Long> v) {
        this.pagesByType = v == null ? new LinkedHashMap<>() : v;
    }

    public long getTotalLinks() { return totalLinks; }
    public void setTotalLinks(long v) { this.totalLinks = v; }

    public long getOrphanCount() { return orphanCount; }
    public void setOrphanCount(long v) { this.orphanCount = v; }

    public List<WikiPage> getRecentUpdates() { return recentUpdates; }
    public void setRecentUpdates(List<WikiPage> v) {
        this.recentUpdates = v == null ? new ArrayList<>() : v;
    }

    public long getPendingTasks() { return pendingTasks; }
    public void setPendingTasks(long v) { this.pendingTasks = v; }

    public long getPendingIssues() { return pendingIssues; }
    public void setPendingIssues(long v) { this.pendingIssues = v; }

    /** ⚠️ 字段名是 isActive，Go tag 是 {@code is_active}；读取器名保持 isActive()，
     *  Jackson 默认会输出 "active"，故必须显式 @JsonProperty("is_active")。 */
    @JsonProperty("is_active")
    public boolean isActive() { return isActive; }
    public void setActive(boolean v) { this.isActive = v; }
}
