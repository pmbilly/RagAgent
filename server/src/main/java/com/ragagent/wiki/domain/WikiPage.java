package com.ragagent.wiki.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * wiki_pages 表实体（对照 Go types.WikiPage，internal/types/wiki_page.go L191-279）。
 *
 * <p><b>表结构以迁移为准</b>（migrations/versioned/000037_wiki_and_indexing.up.sql、
 * 000061_wiki_page_hierarchy.up.sql、000075_wiki_page_revisions.up.sql）。</p>
 *
 * <p>GORM 隐式行为清单（约定 §3）：</p>
 * <ol>
 *   <li><b>软删除</b>：{@code gorm.DeletedAt} 会给每条查询/更新自动加
 *       {@code deleted_at IS NULL}——Java 侧<b>每条 SQL 显式写出</b>（§9：不用 @TableLogic）。</li>
 *   <li><b>钩子</b>：Go 无 BeforeCreate/AfterFind。ID 由 service 生成（Go 侧亦然），
 *       实体无自动 UUID。</li>
 *   <li><b>自动时间戳</b>：GORM 按约定把 CreatedAt / UpdatedAt 当自动时间戳字段
 *       （Create 时填当前时间，Update 时刷新 UpdatedAt）。Java 侧由
 *       {@code WikiPageRepository} 在写入前对 null 值补当前时间（<b>不覆盖</b>已赋值，
 *       与 GORM 只填零值一致）。</li>
 *   <li><b>默认值 tag</b>：{@code status default:'published'}、{@code version default:1}、
 *       {@code depth default:0}、{@code sort_order default:0}。GORM 在 Create 时会把
 *       <b>零值字段替换为该默认值并回写内存</b>（即 Go 无法用本 struct 建出 status='' 的行）。
 *       Java 侧<b>不复刻这个回写</b>：字段保持调用方给的值（Java 零值见下），
 *       由 service 显式赋值；须知道这条差异的调用点见 §9 同类记录（MCP enabled default:true）。</li>
 *   <li><b>零值语义</b>（Go 非指针字段）：所有 String 字段零值为 {@code ""}，DB 各列
 *       NOT NULL DEFAULT ''，因此 Java 侧 getter 永不返回 null；计数器
 *       （depth/sort_order/version）用<b>原始 int</b>，避免插入 NULL。</li>
 *   <li><b>唯一索引</b>：Go tag 写的是 {@code uniqueIndex:idx_kb_slug}，但迁移 000037
 *       建的是<b>部分唯一索引</b> {@code (knowledge_base_id, slug) WHERE deleted_at IS NULL}
 *       ——GORM tag 与 SQL 不一致，<b>以 SQL 为准</b>。</li>
 *   <li><b>jsonb 列</b>：aliases / category_path / source_refs / chunk_refs / in_links /
 *       out_links / page_metadata。前六者是字符串数组（{@link WikiStringListTypeHandler}，
 *       对照 Go StringArray 的 Value/Scan），page_metadata 是任意 JSON
 *       （{@link PgJsonTypeHandler}，对照 Go types.JSON）。</li>
 *   <li><b>无关联预加载</b>：Go 侧本实体没有任何 Preload。</li>
 * </ol>
 *
 * <p>JSON 输出（handler 直接序列化实体，字段序 = Go struct 声明序）：
 * {@code @JsonPropertyOrder} 必须与 Go 一致；omitempty 由字段级
 * {@code @JsonInclude} 复刻（string 空→省略用 NON_EMPTY，int 0→省略用 NON_DEFAULT，
 * slice 空→省略用 NON_EMPTY）。</p>
 */
@TableName(value = "wiki_pages", autoResultMap = true)
@JsonPropertyOrder({
        "id", "tenant_id", "knowledge_base_id", "slug", "title", "page_type", "status",
        "content", "summary", "aliases", "parent_slug", "folder_id", "category_path",
        "wiki_path", "depth", "sort_order", "source_refs", "chunk_refs", "in_links",
        "out_links", "page_metadata", "version", "last_edit_source", "last_editor_id",
        "created_at", "updated_at", "deleted_at"
})
public class WikiPage {

    /** 唯一标识（UUID） */
    @TableId(type = IdType.INPUT)
    @JsonProperty("id")
    private String id;

    /** 工作空间 ID（多租户隔离；Go uint64 → Long） */
    @JsonProperty("tenant_id")
    private Long tenantId;

    /** 所属知识库 */
    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId = "";

    /** URL 友好标识，如 "entity/acme-corp"、"concept/rag"；库内唯一 */
    @JsonProperty("slug")
    private String slug = "";

    /** 人类可读标题 */
    @JsonProperty("title")
    private String title = "";

    /** 页面类型：summary / entity / concept / index / synthesis / comparison */
    @JsonProperty("page_type")
    private String pageType = "";

    /** 页面状态：draft / published / archived（Go tag default:'published'，见类注释第 3 条） */
    @JsonProperty("status")
    private String status = "";

    /** 完整 markdown 正文 */
    @JsonProperty("content")
    private String content = "";

    /** 索引列表用一句话摘要 */
    @JsonProperty("summary")
    private String summary = "";

    /** 别名、缩写、首字母缩略语或译名 */
    @TableField(typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("aliases")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> aliases = new ArrayList<>();

    /** 语义父页面 slug（可为空）；页面仅按 FolderID 归组时留空 */
    @TableField(value = "parent_slug")
    @JsonProperty("parent_slug")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String parentSlug = "";

    /**
     * 页面在目录树中的位置<b>唯一真相来源</b>——指向 wiki_folders.id（"" = wiki 根）。
     * 下面的 categoryPath / wikiPath / depth 是从该文件夹链派生的<b>反规范化缓存</b>，
     * 每次写入时重算，好让 list/index/search 查询不必 join wiki_folders。
     */
    @TableField(value = "folder_id")
    @JsonProperty("folder_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String folderId = "";

    /** 目录面包屑，如 ["AI", "LLM 应用", "RAG"]；FolderID 标识的文件夹链的派生缓存 */
    @TableField(value = "category_path", typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("category_path")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> categoryPath = new ArrayList<>();

    /** 由 page_type + category_path + title 派生的可排序规范化路径，让大目录排序廉价 */
    @TableField(value = "wiki_path")
    @JsonProperty("wiki_path")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String wikiPath = "";

    /** = categoryPath.size()，缓存用于过滤/展示（Go int → 原始 int） */
    @JsonProperty("depth")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int depth;

    /** 排序权重，让生成或手工编辑的页面能在 title 之前控制同级顺序 */
    @JsonProperty("sort_order")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int sortOrder;

    /**
     * 贡献过本页面的源 knowledge id。格式沿用 ingest 管道全程使用的
     * {@code "<knowledge_id>|<doc_title>"} 约定，retract / 展示代码按 {@code |} 切分即可
     * 取回标题。文档级粒度。
     */
    @TableField(value = "source_refs", typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("source_refs")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> sourceRefs = new ArrayList<>();

    /**
     * 本页面由哪些具体的源文档 chunk 构建而来（每条一个 UUID）。ingest 的
     * chunk-citation pass 写入，页面每次重物化时整体刷新。summary 页为空
     * （它们是文档级梗概，不带 chunk 级引用）。
     */
    @TableField(value = "chunk_refs", typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("chunk_refs")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> chunkRefs = new ArrayList<>();

    /** 链接<b>到</b>本页面的页面 slug（反向链接） */
    @TableField(value = "in_links", typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("in_links")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> inLinks = new ArrayList<>();

    /** 本页面链接<b>出去</b>的页面 slug（出链） */
    @TableField(value = "out_links", typeHandler = WikiStringListTypeHandler.class)
    @JsonProperty("out_links")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = EmptyListAsNullSerializer.class)
    private List<String> outLinks = new ArrayList<>();

    /** 任意元数据（标签、分类、日期等）；Go 类型是 types.JSON（原始 JSON） */
    // insertStrategy=ALWAYS：MyBatis-Plus 默认对 null 字段省略该列，会落到 DB 默认值 '{}'，
    // 而 Go 显式写 NULL（nil JSON → driver 返回 nil）——golden 里该键是 null，故必须总是插入。
    @TableField(value = "page_metadata", typeHandler = PgJsonTypeHandler.class,
            insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    @JsonProperty("page_metadata")
    private JsonNode pageMetadata;

    /**
     * 版本号。仅在用户可见的内容字段（title/content/summary/page_type/status）
     * 真的变化时递增；纯记账写入（链接维护、同内容重摄取、后台任务同步状态）
     * 保持不动，使其可作为"页面被编辑过"的真实信号。
     */
    @JsonProperty("version")
    private int version;

    /**
     * 记录<b>当前</b>版本由谁撰写：pipeline | agent | user | revert。
     * 历史行留空（按 pipeline 处理）。版本被取代时该值随快照进入
     * wiki_page_revisions，因此每个历史版本各自保留作者类型。
     */
    @TableField(value = "last_edit_source")
    @JsonProperty("last_edit_source")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String lastEditSource = "";

    /** 产生当前版本的调用方用户 id（后台管道写入留空） */
    @TableField(value = "last_editor_id")
    @JsonProperty("last_editor_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String lastEditorId = "";

    /** 创建时间 */
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    /** 最后更新时间 */
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    /** 软删除标记；Go 的 gorm.DeletedAt 未删除时 JSON 输出 null（恒输出该键） */
    @JsonProperty("deleted_at")
    private OffsetDateTime deletedAt;

    // ── Go 方法对照（⚠️ 全部 @JsonIgnore：派生访问器会被 Jackson 当属性序列化，
    //    阶段 3/4.1 各踩过一次——约定 §9「复发率最高的坑」） ──

    /**
     * 对照 Go WikiPage.SourceKnowledgeIDs（L837-848）：本页面构建自哪些文档 id。
     *
     * <p>⚠️ {@code @JsonIgnore}：Go 里它是<b>方法</b>不是字段，JSON 输出里没有这个键；
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
     * 对照 Go WikiPage.BuiltFrom（L851-861）：本页面的任一来源是否落在给定集合里。
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
