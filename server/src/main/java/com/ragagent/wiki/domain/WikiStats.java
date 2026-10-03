package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * wiki 的聚合统计。JSON 键为 snake（前端按此解析）。
 *
 * <p>统计口径：按类型计数与孤儿页计数都带 {@code status <> 'archived'} 过滤，
 * 即<b>排除归档页</b>。</p>
 */
public class WikiStats {

    private long totalPages;

    /** 键为 page_type，值为计数；用 LinkedHashMap 保持键序稳定 */
    // 出口契约要求 map 键按字节序稳定输出；Jackson 默认不排——不挂 SortedMapSerializer，
    // 多键 map 的键序会随构造顺序漂移

    private Map<String, Long> pagesByType = new LinkedHashMap<>();

    private long totalLinks;

    /** 没有任何入链的页面数 */
    private long orphanCount;

    /** 最近更新的 N 个页面 */
    private List<WikiPage> recentUpdates = new ArrayList<>();

    /** 等待摄取入库的文档数 */
    private long pendingTasks;

    /** 待处理的 wiki 问题数 */
    private long pendingIssues;

    /** wiki 摄取当前是否在运行 */
    private boolean active;

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

    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
}
