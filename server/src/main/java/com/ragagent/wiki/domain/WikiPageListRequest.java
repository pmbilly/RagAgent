package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 列出 wiki 页面的过滤 / 分页请求。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p><b>可空字段语义</b>：包装类型字段用 {@code null} 表达<b>未提供</b>，与
 * <b>提供了零值</b>（{} = 根目录、0 = 根层级）是两种不同语义。
 * 这是本类唯一需要小心的地方——{@code folderId = ""} 与 {@code folderId = null}
 * 不可混淆。</p>
 */
public class WikiPageListRequest {

    private String knowledgeBaseId = "";

    /** 按类型过滤；可带逗号分隔的多类型（"entity,concept"），按逗号切分 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String pageType = "";

    /** 按状态过滤 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String status = "";

    /** 全文检索词 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String query = "";

    /** 精确的文件夹归属（"" = 根）；<b>null = 不过滤</b> */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String folderId;

    /** 精确的目录路径（按 {@link WikiCategoryPaths#trimFolderSegments} 归一化后比较） */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> categoryPath = new ArrayList<>();

    /** 精确的目录层级深度，含 0（根）；<b>null = 不过滤</b> */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer categoryDepth;

    /** 分页页码（1 起）；&lt;1 时按 1 处理 */
    private int page;

    /** 分页大小；&lt;1 时按 20 处理 */
    private int pageSize;

    /** "updated_at" | "created_at" | "title" | "page_type" | "wiki_path" | "sort_order" | "depth" */
    private String sortBy = "";

    /** "asc" 或 "desc"；非 "asc" 一律按 DESC */
    private String sortOrder = "";

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v == null ? "" : v; }

    public String getPageType() { return pageType; }
    public void setPageType(String v) { this.pageType = v == null ? "" : v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v == null ? "" : v; }

    public String getQuery() { return query; }
    public void setQuery(String v) { this.query = v == null ? "" : v; }

    public String getFolderId() { return folderId; }
    public void setFolderId(String v) { this.folderId = v; }

    public List<String> getCategoryPath() { return categoryPath; }
    public void setCategoryPath(List<String> v) { this.categoryPath = v == null ? new ArrayList<>() : v; }

    public Integer getCategoryDepth() { return categoryDepth; }
    public void setCategoryDepth(Integer v) { this.categoryDepth = v; }

    public int getPage() { return page; }
    public void setPage(int v) { this.page = v; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int v) { this.pageSize = v; }

    public String getSortBy() { return sortBy; }
    public void setSortBy(String v) { this.sortBy = v == null ? "" : v; }

    public String getSortOrder() { return sortOrder; }
    public void setSortOrder(String v) { this.sortOrder = v == null ? "" : v; }
}
