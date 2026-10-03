package com.ragagent.wiki.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * wiki_pages 表实体。JSON 键为 snake（前端按此解析）。
 *
 * <p><b>表结构以迁移为准</b>（migrations/versioned/V1__baseline.sql）。</p>
 *
 * <p>落库行为约定：</p>
 * <ol>
 *   <li><b>软删除</b>：每条查询/更新<b>显式写出</b> {@code deleted_at IS NULL}
 *       （刻意不用 @TableLogic）。</li>
 *   <li><b>无钩子</b>：ID 由 service 生成，实体无自动 UUID。</li>
 *   <li><b>自动时间戳</b>：由 {@code WikiPageRepository} 在写入前对 null 的
 *       createdAt/updatedAt 补当前时间（<b>不覆盖</b>已赋值）；更新时刷新 updatedAt。</li>
 *   <li><b>默认值</b>：SQL 列默认 {@code status 'published'}、{@code version 1}、
 *       {@code depth 0}、{@code sort_order 0}。Java 侧<b>不做默认值回写</b>：
 *       字段保持调用方给的值（Java 零值见下），须要默认值的字段由 service 显式赋值。</li>
 *   <li><b>零值语义</b>：所有 String 字段零值为 {@code ""}，DB 各列
 *       NOT NULL DEFAULT ''，因此 Java 侧 getter 永不返回 null；计数器
 *       （depth/sort_order/version）用<b>原始 int</b>，避免插入 NULL。</li>
 *   <li><b>唯一索引</b>：迁移 000037 建的是<b>部分唯一索引</b>
 *       {@code (knowledge_base_id, slug) WHERE deleted_at IS NULL}——重复判断只针对
 *       未删除行，已删除的 slug 可复用。</li>
 *   <li><b>jsonb 列</b>：aliases / category_path / source_refs / chunk_refs / in_links /
 *       out_links / page_metadata。前六者是字符串数组（{@link WikiStringListTypeHandler}），
 *       page_metadata 是任意 JSON（{@link PgJsonTypeHandler}）。</li>
 * </ol>
 *
 * <p>JSON 输出（handler 直接序列化实体，字段序 = {@code @JsonPropertyOrder} 声明序）：
 * 省略语义由字段级 {@code @JsonInclude} 控制（string 空→省略用 NON_EMPTY，
 * int 0→省略用 NON_DEFAULT，列表空→省略用 NON_EMPTY）。</p>
 */
@TableName(value = "wiki_pages", autoResultMap = true)
public class WikiPage {

    /** 唯一标识（UUID） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 工作空间 ID（多租户隔离） */
    private Long tenantId;

    /** 所属知识库 */
    private String knowledgeBaseId = "";

    /** URL 友好标识，如 "entity/acme-corp"、"concept/rag"；库内唯一 */
    private String slug = "";

    /** 人类可读标题 */
    private String title = "";

    /** 页面类型：summary / entity / concept / index / synthesis / comparison */
    private String pageType = "";

    /** 页面状态：draft / published / archived（SQL 默认 'published'，见类注释默认值条） */
    private String status = "";

    /** 完整 markdown 正文 */
    private String content = "";

    /** 索引列表用一句话摘要 */
    private String summary = "";

    /** 别名、缩写、首字母缩略语或译名 */
    @TableField(typeHandler = WikiStringListTypeHandler.class)
    @JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> aliases = new ArrayList<>();

    /** 语义父页面 slug（可为空）；页面仅按 FolderID 归组时留空 */
    @TableField(value = "parent_slug")
    private String parentSlug = "";

    /**
     * 页面在目录树中的位置<b>唯一真相来源</b>——指向 wiki_folders.id（"" = wiki 根）。
     * 下面的 categoryPath / wikiPath / depth 是从该文件夹链派生的<b>反规范化缓存</b>，
     * 每次写入时重算，好让 list/index/search 查询不必 join wiki_folders。
     */
    @TableField(value = "folder_id")
    private String folderId = "";

    /** 目录面包屑，如 ["AI", "LLM 应用", "RAG"]；FolderID 标识的文件夹链的派生缓存 */
    @TableField(value = "category_path", typeHandler = WikiStringListTypeHandler.class)
    private List<String> categoryPath = new ArrayList<>();

    /** 由 page_type + category_path + title 派生的可排序规范化路径，让大目录排序廉价 */
    @TableField(value = "wiki_path")
    private String wikiPath = "";

    /** = categoryPath.size()，缓存用于过滤/展示 */
    private int depth;

    /** 排序权重，让生成或手工编辑的页面能在 title 之前控制同级顺序 */
    private int sortOrder;

    /**
     * 贡献过本页面的源 knowledge id。格式沿用 ingest 管道全程使用的
     * {@code "<knowledge_id>|<doc_title>"} 约定，retract / 展示代码按 {@code |} 切分即可
     * 取回标题。文档级粒度。
     */
    @TableField(value = "source_refs", typeHandler = WikiStringListTypeHandler.class)
    @JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> sourceRefs = new ArrayList<>();

    /**
     * 本页面由哪些具体的源文档 chunk 构建而来（每条一个 UUID）。ingest 的
     * chunk-citation pass 写入，页面每次重物化时整体刷新。summary 页为空
     * （它们是文档级梗概，不带 chunk 级引用）。
     */
    @TableField(value = "chunk_refs", typeHandler = WikiStringListTypeHandler.class)
    @JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> chunkRefs = new ArrayList<>();

    /** 链接<b>到</b>本页面的页面 slug（反向链接） */
    @TableField(value = "in_links", typeHandler = WikiStringListTypeHandler.class)
    @JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> inLinks = new ArrayList<>();

    /** 本页面链接<b>出去</b>的页面 slug（出链） */
    @TableField(value = "out_links", typeHandler = WikiStringListTypeHandler.class)
    @JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> outLinks = new ArrayList<>();

    /** 任意元数据（标签、分类、日期等）；原始 JSON */
    // insertStrategy=ALWAYS：MyBatis-Plus 默认对 null 字段省略该列，会落到 DB 默认值 '{}'，
    // 而出口契约（golden fixture）里该键是 null——故必须总是插入（null 直写）。
    @TableField(value = "page_metadata", typeHandler = PgJsonTypeHandler.class,
            insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private JsonNode pageMetadata;

    /**
     * 版本号。仅在用户可见的内容字段（title/content/summary/page_type/status）
     * 真的变化时递增；纯记账写入（链接维护、同内容重摄取、后台任务同步状态）
     * 保持不动，使其可作为"页面被编辑过"的真实信号。
     */
    private int version;

    /**
     * 记录<b>当前</b>版本由谁撰写：pipeline | agent | user | revert。
     * 历史行留空（按 pipeline 处理）。版本被取代时该值随快照进入
     * wiki_page_revisions，因此每个历史版本各自保留作者类型。
     */
    @TableField(value = "last_edit_source")
    private String lastEditSource = "";

    /** 产生当前版本的调用方用户 id（后台管道写入留空） */
    @TableField(value = "last_editor_id")
    private String lastEditorId = "";

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 最后更新时间 */
    private OffsetDateTime updatedAt;

    /** 软删除标记；未删除时 JSON 输出 null（恒输出该键） */
    private OffsetDateTime deletedAt;

    // ── 派生访问器（⚠️ 全部 @JsonIgnore：派生访问器会被 Jackson 当属性序列化，
    //    是复发率最高的坑） ──

    /**
     * 本页面构建自哪些文档 id（从 {@link #sourceRefs} 的 "id|title" 条目提取）。
     *
     * <p>⚠️ {@code @JsonIgnore}：这是派生方法不是持久化字段，JSON 输出里没有这个键；
     * 不加注解会让它出现在每个响应里，回读 jsonb 时还会触发
     * UnrecognizedPropertyException（MyBatisSystemException: null）。</p>
     */
    @JsonIgnore
    public List<String> sourceKnowledgeIDs() {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(sourceRefs.size());
        for (String ref : sourceRefs) {
            String id = WikiCategoryPaths.sourceKnowledgeID(ref);
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * 本页面的任一来源是否落在给定集合里。
     *
     * <p>⚠️ {@code @JsonIgnore}，理由同上。</p>
     */
    @JsonIgnore
    public boolean builtFrom(java.util.Set<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return false;
        }
        for (String id : sourceKnowledgeIDs()) {
            if (knowledgeIds.contains(id)) {
                return true;
            }
        }
        return false;
    }

    // ── 访问器 ──

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v == null ? "" : v; }

    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v == null ? "" : v; }

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

    public String getParentSlug() { return parentSlug; }
    public void setParentSlug(String v) { this.parentSlug = v == null ? "" : v; }

    public String getFolderId() { return folderId; }
    public void setFolderId(String v) { this.folderId = v == null ? "" : v; }

    public List<String> getCategoryPath() { return categoryPath; }
    public void setCategoryPath(List<String> v) { this.categoryPath = v == null ? new ArrayList<>() : v; }

    public String getWikiPath() { return wikiPath; }
    public void setWikiPath(String v) { this.wikiPath = v == null ? "" : v; }

    public int getDepth() { return depth; }
    public void setDepth(int v) { this.depth = v; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }

    public List<String> getSourceRefs() { return sourceRefs; }
    public void setSourceRefs(List<String> v) { this.sourceRefs = v == null ? new ArrayList<>() : v; }

    public List<String> getChunkRefs() { return chunkRefs; }
    public void setChunkRefs(List<String> v) { this.chunkRefs = v == null ? new ArrayList<>() : v; }

    public List<String> getInLinks() { return inLinks; }
    public void setInLinks(List<String> v) { this.inLinks = v == null ? new ArrayList<>() : v; }

    public List<String> getOutLinks() { return outLinks; }
    public void setOutLinks(List<String> v) { this.outLinks = v == null ? new ArrayList<>() : v; }

    public JsonNode getPageMetadata() { return pageMetadata; }
    public void setPageMetadata(JsonNode v) { this.pageMetadata = v; }

    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }

    public String getLastEditSource() { return lastEditSource; }
    public void setLastEditSource(String v) { this.lastEditSource = v == null ? "" : v; }

    public String getLastEditorId() { return lastEditorId; }
    public void setLastEditorId(String v) { this.lastEditorId = v == null ? "" : v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { this.deletedAt = v; }
}
