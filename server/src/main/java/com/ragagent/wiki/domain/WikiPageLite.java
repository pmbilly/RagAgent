package com.ragagent.wiki.domain;

import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * WikiPage 的瘦投影。
 *
 * <p>只带 ingest 管道在 Map / Reduce 阶段真正会碰的字段，好让每批的取数查询不必为
 * 了拿一个标题或出链就加载整个可能几 MB 的 content 列。</p>
 *
 * <p>用途：</p>
 * <ul>
 *   <li>SlugTitleFetcher：slug → title，做交叉链接注入；</li>
 *   <li>cleanDeadLinks：只读出链 + 状态，不拉正文；</li>
 *   <li>去重预筛：title + aliases + page_type 做 trgm / 表层相似度比较。</li>
 * </ul>
 *
 * <p>aliases 要带上，因为去重与交叉链接注入都把别名表层形式当一等匹配目标；
 * out_links 要带上，这样死链清理能判断哪些页面引用了某个失效 slug 而无须二次查询。</p>
 *
 * <p>本类不是实体，仅作查询投影的 POJO；
 * jsonb 列需在 Mapper 的 {@code @Results} 里显式挂 {@link WikiStringListTypeHandler}。</p>
 */
@JsonPropertyOrder({"slug", "title", "page_type", "status", "aliases", "out_links"})
public class WikiPageLite {

    @JsonProperty("slug")
    private String slug = "";

    @JsonProperty("title")
    private String title = "";

    @TableField(value = "page_type")
    @JsonProperty("page_type")
    private String pageType = "";

    @JsonProperty("status")
    private String status = "";

    @TableField(value = "aliases", typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("aliases")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> aliases = new ArrayList<>();

    @TableField(value = "out_links", typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("out_links")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> outLinks = new ArrayList<>();

    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v == null ? "" : v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v == null ? "" : v; }

    public String getPageType() { return pageType; }
    public void setPageType(String v) { this.pageType = v == null ? "" : v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v == null ? "" : v; }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> v) { this.aliases = v == null ? new ArrayList<>() : v; }

    public List<String> getOutLinks() { return outLinks; }
    public void setOutLinks(List<String> v) { this.outLinks = v == null ? new ArrayList<>() : v; }
}
