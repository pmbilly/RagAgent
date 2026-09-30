package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 结构化 wiki 索引响应里的一行。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>只携带渲染一条可点击目录项所需的列——后端投影 {@code SELECT slug, title, summary}，
 * 这样 4 万页的知识库每次打开索引都不必为 TEXT 正文付出传输代价。</p>
 */
public class WikiIndexEntry {

    private String slug = "";

    private String title = "";

    private String summary = "";

    @TableField(value = "parent_slug")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String parentSlug = "";

    @TableField(value = "category_path", typeHandler = WikiStringListTypeHandler.class)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> categoryPath = new ArrayList<>();

    @TableField(value = "wiki_path")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String wikiPath = "";

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int depth;

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int sortOrder;

    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v == null ? "" : v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v == null ? "" : v; }

    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v == null ? "" : v; }

    public String getParentSlug() { return parentSlug; }
    public void setParentSlug(String v) { this.parentSlug = v == null ? "" : v; }

    public List<String> getCategoryPath() { return categoryPath; }
    public void setCategoryPath(List<String> v) { this.categoryPath = v == null ? new ArrayList<>() : v; }

    public String getWikiPath() { return wikiPath; }
    public void setWikiPath(String v) { this.wikiPath = v == null ? "" : v; }

    public int getDepth() { return depth; }
    public void setDepth(int v) { this.depth = v; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }
}
