package com.ragagent.wiki.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * wiki_page_revisions 表实体（对照 Go types.WikiPageRevision，internal/types/wiki_page.go
 * L317-341；表结构以 migrations/versioned/000075_wiki_page_revisions.up.sql 为准）。
 *
 * <p>被取代的页面版本的一份不可变快照。当前版本只存在于 wiki_pages；当一次编辑替换
 * 版本 V 时，编辑前的状态在<b>同一事务</b>里以 (page_id, V) 插入本表，于是每个历史版本
 * 都可 diff、可回滚。行按 {@link WikiRevisionPruneRequest} 剪枝以约束热点页的存储。</p>
 *
 * <p>GORM 隐式行为清单（约定 §3）：</p>
 * <ol>
 *   <li><b>无软删除</b>：Go 结构体没有 DeletedAt → 删除是硬删（{@code PruneRevisions} /
 *       {@code DeleteRevisionsByPage} 直接 DELETE）。</li>
 *   <li><b>无钩子</b>：ID 由调用方生成。</li>
 *   <li><b>默认值 tag</b>：{@code edit_source default:''}、{@code editor_id default:''}
 *       （与 SQL 的 NOT NULL DEFAULT '' 一致）；SQL 里 title/page_type/status/content/
 *       summary 也有默认，但 Go tag 未写 default，故 GORM 省略零值列让 DB 兜底——
 *       Java 显式写 ""，结果相同。</li>
 *   <li><b>唯一索引</b>：{@code (page_id, version)} UNIQUE（Go tag 的
 *       uniqueIndex:idx_wiki_page_revisions_page_version 与迁移 000075 一致，
 *       这是少见的 tag/SQL 对齐情况）。写入依赖它做
 *       {@code ON CONFLICT DO NOTHING} 幂等。</li>
 *   <li><b>jsonb 列</b>：aliases（字符串数组）。</li>
 *   <li><b>列表投影</b>：{@code ListRevisions} 刻意不 SELECT content（可能是几百 KB）。</li>
 * </ol>
 */
@TableName(value = "wiki_page_revisions", autoResultMap = true)
@JsonPropertyOrder({
        "id", "tenant_id", "knowledge_base_id", "page_id", "slug", "version", "title",
        "page_type", "status", "content", "summary", "aliases", "edit_source", "editor_id",
        "edited_at", "created_at"
})
public class WikiPageRevision {

    @TableId(type = IdType.INPUT)
    @JsonProperty("id")
    private String id;

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId = "";

    @JsonProperty("page_id")
    private String pageId = "";

    @JsonProperty("slug")
    private String slug = "";

    @JsonProperty("version")
    private int version;

    @JsonProperty("title")
    private String title = "";

    @JsonProperty("page_type")
    private String pageType = "";

    @JsonProperty("status")
    private String status = "";

    /** 正文快照；Go tag 是 {@code json:"content,omitempty"}（列表模式恒省略） */
    @JsonProperty("content")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String content = "";

    @JsonProperty("summary")
    private String summary = "";

    @TableField(typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("aliases")
    private List<String> aliases = new ArrayList<>();

    /** <b>本版本</b>的作者类型（语义同 WikiPage.LastEditSource） */
    @TableField(value = "edit_source")
    @JsonProperty("edit_source")
    private String editSource = "";

    @TableField(value = "editor_id")
    @JsonProperty("editor_id")
    private String editorId = "";

    /** 本版本的撰写时间（= 它还是当前版本时的 wiki_pages.updated_at） */
    @TableField(value = "edited_at")
    @JsonProperty("edited_at")
    private OffsetDateTime editedAt;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    // ── 访问器 ──

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v == null ? "" : v; }

    public String getPageId() { return pageId; }
    public void setPageId(String v) { this.pageId = v == null ? "" : v; }

    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v == null ? "" : v; }

    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v == null ? "" : v; }

    public String getPageType() { return pageType; }
    public void setPageType(String v) { this.pageType = v == null ? "" : v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v == null ? "" : v; }

    public String getContent() { return content; }
    public void setContent(String v) { this.content = v == null ? "" : v; }

    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v == null ? "" : v; }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> v) { this.aliases = v == null ? new ArrayList<>() : v; }

    public String getEditSource() { return editSource; }
    public void setEditSource(String v) { this.editSource = v == null ? "" : v; }

    public String getEditorId() { return editorId; }
    public void setEditorId(String v) { this.editorId = v == null ? "" : v; }

    public OffsetDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(OffsetDateTime v) { this.editedAt = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
