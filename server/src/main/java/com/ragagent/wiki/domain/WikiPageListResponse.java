package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 分页的 wiki 页面列表响应。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>{@code totalPages} 由 service 计算：{@code ceil(total / pageSize)}，
 * 页大小按 repository 归一化后的值（&lt;1 → 20）。</p>
 */
@JsonPropertyOrder({"pages", "total", "page", "page_size", "total_pages"})
public class WikiPageListResponse {

    @JsonProperty("pages")
    private List<WikiPage> pages = new ArrayList<>();

    @JsonProperty("total")
    private long total;

    @JsonProperty("page")
    private int page;

    @JsonProperty("page_size")
    private int pageSize;

    @JsonProperty("total_pages")
    private int totalPages;

    public List<WikiPage> getPages() { return pages; }
    public void setPages(List<WikiPage> v) { this.pages = v == null ? new ArrayList<>() : v; }

    public long getTotal() { return total; }
    public void setTotal(long v) { this.total = v; }

    public int getPage() { return page; }
    public void setPage(int v) { this.page = v; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int v) { this.pageSize = v; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int v) { this.totalPages = v; }
}
