package com.ragagent.wiki.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * wiki_page_issues 表实体（表结构以 migrations/versioned/000037_wiki_and_indexing.up.sql
 * 第 2 段为准）。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>针对某个 wiki 页面登记的问题记录，通常由 agent 或 linter 发现后落库待复核。</p>
 *
 * <p>落库行为约定：</p>
 * <ol>
 *   <li><b>软删除</b>：查询须显式 {@code deleted_at IS NULL}。</li>
 *   <li><b>无钩子</b>：ID 由调用方生成。</li>
 *   <li><b>默认值</b>：SQL 里 {@code status DEFAULT 'pending'}。
 *       Java 侧不复刻回写，由调用方显式赋值。</li>
 *   <li><b>问题列表排序</b>：{@code ORDER BY created_at DESC}。</li>
 *   <li><b>jsonb 列</b>：suspected_knowledge_ids（字符串数组）。注意 SQL 里该列
 *       <b>可空</b>（无 NOT NULL），读回空列表。</li>
 * </ol>
 */
@TableName(value = "wiki_page_issues", autoResultMap = true)
@JsonPropertyOrder({
        "id", "tenant_id", "knowledge_base_id", "slug", "issue_type", "description",
        "suspected_knowledge_ids", "status", "reported_by", "created_at", "updated_at",
        "deleted_at"
})
public class WikiPageIssue {

    @TableId(type = IdType.INPUT)
    @JsonProperty("id")
    private String id;

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId = "";

    @JsonProperty("slug")
    private String slug = "";

    @TableField(value = "issue_type")
    @JsonProperty("issue_type")
    private String issueType = "";

    @JsonProperty("description")
    private String description = "";

    @TableField(value = "suspected_knowledge_ids", typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("suspected_knowledge_ids")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> suspectedKnowledgeIds = new ArrayList<>();

    @JsonProperty("status")
    private String status = "";

    @TableField(value = "reported_by")
    @JsonProperty("reported_by")
    private String reportedBy = "";

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    @JsonProperty("deleted_at")
    private OffsetDateTime deletedAt;

    // ── 访问器 ──

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v == null ? "" : v; }

    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v == null ? "" : v; }

    public String getIssueType() { return issueType; }
    public void setIssueType(String v) { this.issueType = v == null ? "" : v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v == null ? "" : v; }

    public List<String> getSuspectedKnowledgeIds() { return suspectedKnowledgeIds; }
    public void setSuspectedKnowledgeIds(List<String> v) {
        this.suspectedKnowledgeIds = v == null ? new ArrayList<>() : v;
    }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v == null ? "" : v; }

    public String getReportedBy() { return reportedBy; }
    public void setReportedBy(String v) { this.reportedBy = v == null ? "" : v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { this.deletedAt = v; }
}
