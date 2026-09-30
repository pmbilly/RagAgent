package com.ragagent.wiki.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * wiki_folders 表实体（表结构以 migrations/versioned/000037_wiki_and_indexing.up.sql
 * 第 1b 段为准）。JSON 键为 snake（§11 登记边界，前端按此解析）。
 *
 * <p>wiki 浏览器里的一等目录节点。文件夹独立于页面存在——空文件夹会保留，
 * 用户可以先把骨架搭好、之后再往里归档页面。树是邻接表（parent_id，"" = 根）；
 * path 是物化的 "/" 连接名链，纯粹为了廉价展示/排序。页面的实际归属是
 * {@link WikiPage#getFolderId()}。</p>
 *
 * <p>落库行为约定：</p>
 * <ol>
 *   <li><b>软删除</b>：每条查询显式 {@code deleted_at IS NULL}。</li>
 *   <li><b>无钩子</b>：ID 由 service 生成。</li>
 *   <li><b>默认值</b>：SQL 中 tenant_id/depth/sort_order DEFAULT 0，parent_id/path
 *       DEFAULT ''。Java 零值即 0/""。</li>
 *   <li><b>唯一索引</b>：{@code (knowledge_base_id, parent_id, name) WHERE deleted_at IS NULL}
 *       （部分唯一索引，只约束未删除行）。服务层的
 *       CreateFolder 靠它兜住并发同名创建。</li>
 *   <li><b>子文件夹列表排序</b>：{@code ORDER BY sort_order ASC, name ASC}。</li>
 *   <li><b>全量文件夹列表排序</b>：{@code ORDER BY depth ASC, path ASC}。</li>
 * </ol>
 */
@TableName(value = "wiki_folders", autoResultMap = true)
@JsonPropertyOrder({
        "id", "tenant_id", "knowledge_base_id", "parent_id", "name", "path", "depth",
        "sort_order", "created_at", "updated_at", "deleted_at"
})
public class WikiFolder {

    @TableId(type = IdType.INPUT)
    @JsonProperty("id")
    private String id;

    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId = "";

    /** 父文件夹 id；{@link WikiConstants#FOLDER_ROOT_ID}（""）= 根 */
    @TableField(value = "parent_id")
    @JsonProperty("parent_id")
    private String parentId = "";

    @JsonProperty("name")
    private String name = "";

    /** 物化的 "/" 连接名链，如 "AI/LLM"；仅用于展示与排序 */
    @JsonProperty("path")
    private String path = "";

    @JsonProperty("depth")
    private int depth;

    @JsonProperty("sort_order")
    private int sortOrder;

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

    public String getParentId() { return parentId; }
    public void setParentId(String v) { this.parentId = v == null ? "" : v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v == null ? "" : v; }

    public String getPath() { return path; }
    public void setPath(String v) { this.path = v == null ? "" : v; }

    public int getDepth() { return depth; }
    public void setDepth(int v) { this.depth = v; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { this.deletedAt = v; }
}
