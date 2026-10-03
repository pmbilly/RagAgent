package com.ragagent.wiki.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.wiki.domain.SourceRefNeedle;
import com.ragagent.wiki.domain.WikiIndexEntry;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.domain.WikiStringListTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * wiki_pages 仓储语句（{@link WikiPageRepository} 的配套 SQL）。
 *
 * <p>落库隐式行为清单：</p>
 * <ul>
 *   <li><b>软删除</b>：本接口每条读写 SQL <b>显式写出</b> {@code deleted_at IS NULL}
 *       过滤（不用 @TableLogic）。</li>
 *   <li><b>默认排序</b>：{@code listByType → updated_at DESC}、
 *       {@code listAll → page_type ASC, title ASC}、
 *       {@code listPagesCursor → id ASC}、{@code listRevisions → version DESC}、
 *       {@code listIssues → created_at DESC}。全部显式出现在 SQL 里。</li>
 *   <li><b>零值省略</b>：更新走「显式列 SET」绕开零值省略
 *       ——见 {@link #updateWithVersion} / {@link #updateMeta} /
 *       {@link #updateAutoLinkedContent}。</li>
 *   <li><b>jsonb 列</b>：字符串数组走 {@link WikiStringListTypeHandler}，
 *       page_metadata 走 {@link PgJsonTypeHandler}。</li>
 * </ul>
 *
 * <p><b>方言分支</b>（方言探测 / 目录优先排序 / 空入链判定 / 规范化标题表达式）：</p>
 * <ul>
 *   <li>排序用的"有目录的排前面"表达式在 PG 是 {@code jsonb_array_length}——
 *       测试库 H2 由 {@code TestSchema} 注册同名 ALIAS，因此<b>同一条 SQL 两边都能跑</b>；
 *       见 {@link #categoryRankOrder}。</li>
 *   <li>category_path 相等比较、source_refs 包含、全文检索三处用 MyBatis 的
 *       {@code <choose>} 按 {@code postgres} 参数分叉（不做多方法复制，避免谓词漂移）。</li>
 * </ul>
 */
@Mapper
public interface WikiPageMapper extends BaseMapper<WikiPage> {

    /**
     * 目录优先排序表达式：有目录的页面（
     * category_path 非空）排前，散落的根页面排后。侧边栏是 IDE 式树，分页发生在
     * 数据库侧，因此必须让"带目录的"先返回，否则前端根本不知道该目录藏在后面几页。
     *
     * <p>PG 用 {@code jsonb_array_length}；H2 在测试期注册同名 ALIAS
     * （{@code TestSchema}），故这里是单一表达式。测试库用 VARCHAR 承载 jsonb，
     * ALIAS 直接解析文本。</p>
     */
    String categoryRankOrder =
            "CASE WHEN COALESCE(jsonb_array_length(category_path), 0) > 0 THEN 0 ELSE 1 END ASC";

    // ── 结果映射 ──

    @Results(id = "wikiPageResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "knowledge_base_id", property = "knowledgeBaseId"),
            @Result(column = "slug", property = "slug"),
            @Result(column = "title", property = "title"),
            @Result(column = "page_type", property = "pageType"),
            @Result(column = "status", property = "status"),
            @Result(column = "content", property = "content"),
            @Result(column = "summary", property = "summary"),
            @Result(column = "aliases", property = "aliases",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "parent_slug", property = "parentSlug"),
            @Result(column = "folder_id", property = "folderId"),
            @Result(column = "category_path", property = "categoryPath",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "wiki_path", property = "wikiPath"),
            @Result(column = "depth", property = "depth"),
            @Result(column = "sort_order", property = "sortOrder"),
            @Result(column = "source_refs", property = "sourceRefs",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "chunk_refs", property = "chunkRefs",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "in_links", property = "inLinks",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "out_links", property = "outLinks",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "page_metadata", property = "pageMetadata",
                    typeHandler = PgJsonTypeHandler.class),
            @Result(column = "version", property = "version"),
            @Result(column = "last_edit_source", property = "lastEditSource"),
            @Result(column = "last_editor_id", property = "lastEditorId"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "deleted_at", property = "deletedAt"),
    })
    @Select("SELECT * FROM wiki_pages WHERE id = #{id} AND deleted_at IS NULL LIMIT 1")
    WikiPage selectLiveById(@Param("id") String id);

    /** 按 (kb, slug) 取活跃页面。 */
    @ResultMap("wikiPageResult")
    @Select("SELECT * FROM wiki_pages WHERE knowledge_base_id = #{kbId} AND slug = #{slug} "
            + "AND deleted_at IS NULL LIMIT 1")
    WikiPage selectLiveBySlug(@Param("kbId") String kbId, @Param("slug") String slug);

    /**
     * 带乐观锁更新的写入列集。
     *
     * <p>刻意走显式列映射（而非按实体零值省略字段的更新）以便清空字段也能落库：
     * 零值字段会被跳过，"清空 summary" 曾经静默不生效。列集覆盖
     * UpdatePage 会改的每一列，因此不需要补一次 UpdateMeta。</p>
     *
     * <p>失败（0 行）时调用方负责把 version 还原，保证调用方看不到"没落库却涨了版本"。</p>
     */
    @Update("UPDATE wiki_pages SET "
            + "title = #{page.title}, content = #{page.content}, summary = #{page.summary}, "
            + "page_type = #{page.pageType}, status = #{page.status}, "
            + "aliases = #{page.aliases, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "out_links = #{page.outLinks, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "source_refs = #{page.sourceRefs, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "chunk_refs = #{page.chunkRefs, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "page_metadata = #{page.pageMetadata, typeHandler=com.ragagent.common.web.PgJsonTypeHandler}, "
            + "parent_slug = #{page.parentSlug}, folder_id = #{page.folderId}, "
            + "category_path = #{page.categoryPath, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "wiki_path = #{page.wikiPath}, depth = #{page.depth}, sort_order = #{page.sortOrder}, "
            + "last_edit_source = #{page.lastEditSource}, last_editor_id = #{page.lastEditorId}, "
            + "version = #{page.version}, updated_at = #{page.updatedAt} "
            + "WHERE id = #{page.id} AND version = #{expectedVersion} AND deleted_at IS NULL")
    int updateWithVersion(@Param("page") WikiPage page, @Param("expectedVersion") int expectedVersion);

    /** 活跃行计数：区分"不存在"与"版本冲突" */
    @Select("SELECT COUNT(*) FROM wiki_pages WHERE id = #{id} AND deleted_at IS NULL")
    long countLiveById(@Param("id") String id);

    /**
     * 只重写 content / out_links /
     * updated_at，<b>不碰 version</b>。用于机器侧链接标记（交叉链接注入、死链清理），
     * 否则首次摄取完成的页面会因为后处理加了个 {@code [[...]]} 包装就跳到 v2。
     */
    @Update("UPDATE wiki_pages SET "
            + "content = #{content}, "
            + "out_links = #{outLinks, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int updateAutoLinkedContent(WikiPage page);

    /**
     * 刷新记账 / 溯源字段，<b>不递增 version</b>。
     * 版本语义上的"内容"是用户可见正文（title/content/summary/page_type/status）；
     * 其余（链接、来源引用、chunk 引用、page_metadata）都算记账。
     */
    @Update("UPDATE wiki_pages SET "
            + "in_links = #{inLinks, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "out_links = #{outLinks, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "aliases = #{aliases, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "status = #{status}, "
            + "source_refs = #{sourceRefs, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "chunk_refs = #{chunkRefs, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "page_metadata = #{pageMetadata, typeHandler=com.ragagent.common.web.PgJsonTypeHandler}, "
            + "parent_slug = #{parentSlug}, folder_id = #{folderId}, "
            + "category_path = #{categoryPath, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "wiki_path = #{wikiPath}, depth = #{depth}, sort_order = #{sortOrder}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int updateMeta(WikiPage page);

    /**
     * 页面列表查询。过滤条件与 {@link #countList} <b>必须逐条保持同步</b>
     * （这里拆成两条语句，改一处必须改两处）。
     *
     * @param pageTypes          已切分好的类型列表（空 = 不过滤）
     * @param categoryPathEncoded 非空时按目录路径精确过滤；编码格式 = Jackson 紧凑 JSON 数组
     * @param sortColumn         已白名单化的排序列名（见 WikiPageRepository.localizeSortColumn）
     * @param sortDirection      "ASC" / "DESC"
     * @param wikiPathSort       是否走 wiki_path 专用排序（目录优先 + sort_order + title 兜底）
     */
    @ResultMap("wikiPageResult")
    @Select("<script>"
            + "SELECT * FROM wiki_pages WHERE knowledge_base_id = #{req.knowledgeBaseId} "
            + "AND deleted_at IS NULL "
            + "<if test='pageTypes != null and pageTypes.size() > 0'>"
            + "  AND page_type IN <foreach collection='pageTypes' item='pt' open='(' separator=',' close=')'>#{pt}</foreach>"
            + "</if>"
            + "<if test=\"req.status != null and req.status != ''\"> AND status = #{req.status}</if>"
            + "<if test=\"req.query != null and req.query != ''\">"
            + "  AND <choose>"
            + "    <when test='postgres'>"
            + "      (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, '')) "
            + "        @@ plainto_tsquery('simple', #{req.query}) "
            + "       OR CAST(aliases AS VARCHAR) ILIKE #{queryLike})"
            + "    </when>"
            + "    <otherwise>"
            + "      (LOWER(coalesce(title, '') || ' ' || coalesce(content, '')) LIKE LOWER(#{queryLike}) "
            + "       OR LOWER(CAST(aliases AS VARCHAR)) LIKE LOWER(#{queryLike}))"
            + "    </otherwise>"
            + "  </choose>"
            + "</if>"
            + "<if test='req.folderId != null'> AND folder_id = #{req.folderId}</if>"
            + "<if test='req.categoryDepth != null'> AND depth = #{req.categoryDepth}</if>"
            + "<if test='categoryPathEncoded != null'>"
            + "  AND <choose>"
            + "    <when test='postgres'>category_path = CAST(#{categoryPathEncoded} AS jsonb)</when>"
            + "    <otherwise>REPLACE(CAST(category_path AS VARCHAR), ', ', ',') = #{categoryPathEncoded}</otherwise>"
            + "  </choose>"
            + "</if>"
            + "ORDER BY "
            + "<if test='wikiPathSort'>" + categoryRankOrder + ",</if>"
            + "${sortColumn} ${sortDirection}"
            + "<if test='wikiPathSort'>, sort_order ASC, title ASC</if>"
            + " LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<WikiPage> list(@Param("req") com.ragagent.wiki.domain.WikiPageListRequest req,
                        @Param("pageTypes") List<String> pageTypes,
                        @Param("categoryPathEncoded") String categoryPathEncoded,
                        @Param("queryLike") String queryLike,
                        @Param("postgres") boolean postgres,
                        @Param("sortColumn") String sortColumn,
                        @Param("sortDirection") String sortDirection,
                        @Param("wikiPathSort") boolean wikiPathSort,
                        @Param("limit") int limit,
                        @Param("offset") int offset);

    /** 与 {@link #list} 完全相同的谓词（改一处必须改两处），只换成 COUNT。 */
    @Select("<script>"
            + "SELECT COUNT(*) FROM wiki_pages WHERE knowledge_base_id = #{req.knowledgeBaseId} "
            + "AND deleted_at IS NULL "
            + "<if test='pageTypes != null and pageTypes.size() > 0'>"
            + "  AND page_type IN <foreach collection='pageTypes' item='pt' open='(' separator=',' close=')'>#{pt}</foreach>"
            + "</if>"
            + "<if test=\"req.status != null and req.status != ''\"> AND status = #{req.status}</if>"
            + "<if test=\"req.query != null and req.query != ''\">"
            + "  AND <choose>"
            + "    <when test='postgres'>"
            + "      (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, '')) "
            + "        @@ plainto_tsquery('simple', #{req.query}) "
            + "       OR CAST(aliases AS VARCHAR) ILIKE #{queryLike})"
            + "    </when>"
            + "    <otherwise>"
            + "      (LOWER(coalesce(title, '') || ' ' || coalesce(content, '')) LIKE LOWER(#{queryLike}) "
            + "       OR LOWER(CAST(aliases AS VARCHAR)) LIKE LOWER(#{queryLike}))"
            + "    </otherwise>"
            + "  </choose>"
            + "</if>"
            + "<if test='req.folderId != null'> AND folder_id = #{req.folderId}</if>"
            + "<if test='req.categoryDepth != null'> AND depth = #{req.categoryDepth}</if>"
            + "<if test='categoryPathEncoded != null'>"
            + "  AND <choose>"
            + "    <when test='postgres'>category_path = CAST(#{categoryPathEncoded} AS jsonb)</when>"
            + "    <otherwise>REPLACE(CAST(category_path AS VARCHAR), ', ', ',') = #{categoryPathEncoded}</otherwise>"
            + "  </choose>"
            + "</if>"
            + "</script>")
    long countList(@Param("req") com.ragagent.wiki.domain.WikiPageListRequest req,
                   @Param("pageTypes") List<String> pageTypes,
                   @Param("categoryPathEncoded") String categoryPathEncoded,
                   @Param("queryLike") String queryLike,
                   @Param("postgres") boolean postgres);

    /** 库内该类型全部页面，updated_at DESC */
    @ResultMap("wikiPageResult")
    @Select("SELECT * FROM wiki_pages WHERE knowledge_base_id = #{kbId} AND page_type = #{pageType} "
            + "AND deleted_at IS NULL ORDER BY updated_at DESC")
    List<WikiPage> listByType(@Param("kbId") String kbId, @Param("pageType") String pageType);

    // ── WikiIndexEntry 投影 ──

    @Results(id = "wikiIndexEntryResult", value = {
            @Result(column = "slug", property = "slug"),
            @Result(column = "title", property = "title"),
            @Result(column = "summary", property = "summary"),
            @Result(column = "parent_slug", property = "parentSlug"),
            @Result(column = "category_path", property = "categoryPath",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "wiki_path", property = "wikiPath"),
            @Result(column = "depth", property = "depth"),
            @Result(column = "sort_order", property = "sortOrder"),
    })
    @Select("SELECT slug, title, summary, parent_slug, category_path, wiki_path, depth, sort_order "
            + "FROM wiki_pages WHERE knowledge_base_id = #{kbId} AND page_type = #{pageType} "
            + "AND status <> #{archived} AND deleted_at IS NULL "
            + "ORDER BY " + categoryRankOrder + ", wiki_path ASC, sort_order ASC, title ASC "
            + "LIMIT #{limit} OFFSET #{offset}")
    List<WikiIndexEntry> listByTypeLight(@Param("kbId") String kbId,
                                         @Param("pageType") String pageType,
                                         @Param("archived") String archived,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    /** {@link #listByTypeLight} 的计数分支，谓词同上 */
    @Select("SELECT COUNT(*) FROM wiki_pages WHERE knowledge_base_id = #{kbId} "
            + "AND page_type = #{pageType} AND status <> #{archived} AND deleted_at IS NULL")
    long countByTypeLight(@Param("kbId") String kbId,
                          @Param("pageType") String pageType,
                          @Param("archived") String archived);

    /** 只投影 slug/title/summary，updated_at DESC */
    @ResultMap("wikiIndexEntryResult")
    @Select("SELECT slug, title, summary FROM wiki_pages WHERE knowledge_base_id = #{kbId} "
            + "AND page_type = #{pageType} AND status <> #{archived} AND deleted_at IS NULL "
            + "ORDER BY updated_at DESC LIMIT #{limit}")
    List<WikiIndexEntry> listByTypeRecent(@Param("kbId") String kbId,
                                          @Param("pageType") String pageType,
                                          @Param("archived") String archived,
                                          @Param("limit") int limit);

    // ── WikiPageLite 投影 ──

    @Results(id = "wikiPageLiteResult", value = {
            @Result(column = "slug", property = "slug"),
            @Result(column = "title", property = "title"),
            @Result(column = "page_type", property = "pageType"),
            @Result(column = "status", property = "status"),
            @Result(column = "aliases", property = "aliases",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "out_links", property = "outLinks",
                    typeHandler = WikiStringListTypeHandler.class),
    })
    @Select("<script>SELECT slug, title, page_type, status, aliases, out_links FROM wiki_pages "
            + "WHERE knowledge_base_id = #{kbId} AND deleted_at IS NULL "
            + "AND slug IN <foreach collection='slugs' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</script>")
    List<WikiPageLite> listLiteBySlugs(@Param("kbId") String kbId, @Param("slugs") List<String> slugs);

    /**
     * 按"去空白 + 小写"后的标题批量匹配。
     *
     * <p>{@code normExpr} 是方言化的"去空白 + 小写"表达式：PG 用 POSIX
     * {@code [[:space:]]}，
     * H2 用 Java 正则 {@code \s}（H2 的 REGEXP_REPLACE 走 JVM 正则，不认 POSIX 括号类）。
     * 该表达式是<b>编译期常量</b>，不含用户输入，故可安全注入。</p>
     */
    @ResultMap("wikiPageLiteResult")
    @Select("<script>SELECT slug, title, page_type, status, aliases, out_links FROM wiki_pages "
            + "WHERE knowledge_base_id = #{kbId} AND page_type = #{pageType} "
            + "AND status &lt;&gt; #{archived} AND deleted_at IS NULL "
            + "AND ${normExpr} IN <foreach collection='identities' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "ORDER BY slug ASC LIMIT #{limit}"
            + "</script>")
    List<WikiPageLite> findPagesByNormalizedTitles(@Param("kbId") String kbId,
                                                   @Param("pageType") String pageType,
                                                   @Param("archived") String archived,
                                                   @Param("normExpr") String normExpr,
                                                   @Param("identities") List<String> identities,
                                                   @Param("limit") int limit);

    // ── source_refs 相关（两种存储形式："id" 与 "id|title"） ──

    /**
     * 按 source_refs 包含匹配页面。
     *
     * <p>PG 分支走 {@code jsonb_path_ops} 的 GIN 包含索引（idx_wiki_pages_source_refs）；
     * 非 PG 分支（H2）没有 {@code @>} 运算符，退化为对 JSON 文本的 LIKE——两者对
     * "source_refs 里是否含该 id"的判定一致，代价是测试库上不走索引。</p>
     *
     * <p>legacy 前缀分支（"id|title"）两侧都用 LIKE。</p>
     */
    @ResultMap("wikiPageResult")
    @Select("<script>SELECT * FROM wiki_pages WHERE knowledge_base_id = #{kbId} AND deleted_at IS NULL "
            + "AND ("
            + "<choose>"
            + "  <when test='postgres'>source_refs @&gt; CAST(#{needle} AS jsonb)</when>"
            + "  <otherwise>CAST(source_refs AS VARCHAR) LIKE #{needleLike}</otherwise>"
            + "</choose>"
            + " OR CAST(source_refs AS VARCHAR) LIKE #{prefixLike})"
            + "</script>")
    List<WikiPage> listBySourceRef(@Param("kbId") String kbId,
                                   @Param("postgres") boolean postgres,
                                   @Param("needle") String needle,
                                   @Param("needleLike") String needleLike,
                                   @Param("prefixLike") String prefixLike);

    /** 同一谓词，投影到单列 slug */
    @Select("<script>SELECT slug FROM wiki_pages WHERE knowledge_base_id = #{kbId} AND deleted_at IS NULL "
            + "AND ("
            + "<choose>"
            + "  <when test='postgres'>source_refs @&gt; CAST(#{needle} AS jsonb)</when>"
            + "  <otherwise>CAST(source_refs AS VARCHAR) LIKE #{needleLike}</otherwise>"
            + "</choose>"
            + " OR CAST(source_refs AS VARCHAR) LIKE #{prefixLike})"
            + "</script>")
    List<String> listSlugsBySourceRef(@Param("kbId") String kbId,
                                      @Param("postgres") boolean postgres,
                                      @Param("needle") String needle,
                                      @Param("needleLike") String needleLike,
                                      @Param("prefixLike") String prefixLike);

    /**
     * 按 knowledge id 取摘要的 sql 行载体。
     * source_refs 走 TypeHandler 回读为字符串列表。
     */
    class SummaryRow {
        private String content = "";
        private List<String> sourceRefs = new java.util.ArrayList<>();

        public String getContent() { return content; }
        public void setContent(String v) { this.content = v == null ? "" : v; }

        public List<String> getSourceRefs() { return sourceRefs; }
        public void setSourceRefs(List<String> v) {
            this.sourceRefs = v == null ? new java.util.ArrayList<>() : v;
        }
    }

    @Results(id = "wikiSummaryRowResult", value = {
            @Result(column = "content", property = "content"),
            @Result(column = "source_refs", property = "sourceRefs",
                    typeHandler = WikiStringListTypeHandler.class),
    })
    @Select("<script>SELECT content, source_refs FROM wiki_pages "
            + "WHERE knowledge_base_id = #{kbId} AND page_type = #{summaryType} "
            + "AND status &lt;&gt; #{archived} AND deleted_at IS NULL AND ("
            + "<foreach collection='needles' item='n' separator=' OR '>"
            + "<choose>"
            + "  <when test='postgres'>source_refs @&gt; CAST(#{n.needle} AS jsonb)</when>"
            + "  <otherwise>CAST(source_refs AS VARCHAR) LIKE #{n.exactLike}</otherwise>"
            + "</choose>"
            + " OR CAST(source_refs AS VARCHAR) LIKE #{n.prefixLike}"
            + "</foreach>"
            + ")</script>")
    List<SummaryRow> listSummariesByKnowledgeIDs(
            @Param("kbId") String kbId,
            @Param("summaryType") String summaryType,
            @Param("archived") String archived,
            @Param("postgres") boolean postgres,
            @Param("needles") List<SourceRefNeedle> needles);

    // ── 存在性 / slug 集合 ──

    /** 存在性判定：只有"非归档的活跃 slug"才算 true */
    @Select("<script>SELECT slug FROM wiki_pages WHERE knowledge_base_id = #{kbId} "
            + "AND status &lt;&gt; #{archived} AND deleted_at IS NULL "
            + "AND slug IN <foreach collection='slugs' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</script>")
    List<String> selectLiveSlugs(@Param("kbId") String kbId,
                                 @Param("archived") String archived,
                                 @Param("slugs") List<String> slugs);

    /** 全部活跃 slug。 */
    @Select("SELECT slug FROM wiki_pages WHERE knowledge_base_id = #{kbId} "
            + "AND status <> #{archived} AND deleted_at IS NULL")
    List<String> selectAllLiveSlugs(@Param("kbId") String kbId, @Param("archived") String archived);

    /**
     * 按 id ASC 的游标分页，
     * <b>排除 archived</b>；limit 夹在 [1, 500] 由 repository 负责。
     */
    @ResultMap("wikiPageResult")
    @Select("<script>SELECT * FROM wiki_pages WHERE knowledge_base_id = #{kbId} "
            + "AND status &lt;&gt; #{archived} AND deleted_at IS NULL "
            + "<if test=\"cursor != null and cursor != ''\"> AND id &gt; #{cursor}</if> "
            + "ORDER BY id ASC LIMIT #{limit}"
            + "</script>")
    List<WikiPage> listPagesCursor(@Param("kbId") String kbId,
                                   @Param("archived") String archived,
                                   @Param("cursor") String cursor,
                                   @Param("limit") int limit);

    // ── 目录 / 统计 ──

    /** 全部非归档页面，page_type ASC, title ASC */
    @ResultMap("wikiPageResult")
    @Select("SELECT * FROM wiki_pages WHERE knowledge_base_id = #{kbId} "
            + "AND status <> #{archived} AND deleted_at IS NULL "
            + "ORDER BY page_type ASC, title ASC")
    List<WikiPage> listAll(@Param("kbId") String kbId, @Param("archived") String archived);

    /** 按 folder_id 集合取整页（用于子树重算） */
    @ResultMap("wikiPageResult")
    @Select("<script>SELECT * FROM wiki_pages WHERE knowledge_base_id = #{kbId} AND deleted_at IS NULL "
            + "AND folder_id IN <foreach collection='folderIds' item='f' open='(' separator=',' close=')'>#{f}</foreach>"
            + "</script>")
    List<WikiPage> listPagesByFolderIds(@Param("kbId") String kbId,
                                        @Param("folderIds") List<String> folderIds);

    /**
     * 跨知识库取最近更新的
     * 用户可见页面，用于给只有 wiki 的知识库生成兜底推荐问题。
     * <b>排除 index 页与归档页</b>，只取 published 且 title 非空。
     */
    @ResultMap("wikiPageResult")
    @Select("<script>SELECT * FROM wiki_pages WHERE tenant_id = #{tenantId} AND deleted_at IS NULL "
            + "AND knowledge_base_id IN <foreach collection='kbIds' item='kb' open='(' separator=',' close=')'>#{kb}</foreach> "
            + "AND page_type &lt;&gt; #{indexType} AND status = #{published} AND title &lt;&gt; '' "
            + "ORDER BY updated_at DESC LIMIT #{limit}"
            + "</script>")
    List<WikiPage> listRecentForSuggestions(@Param("tenantId") long tenantId,
                                            @Param("kbIds") List<String> kbIds,
                                            @Param("indexType") String indexType,
                                            @Param("published") String published,
                                            @Param("limit") int limit);

    /** 非归档，按 page_type 分组计数 */
    @Select("SELECT page_type AS pageType, COUNT(*) AS count FROM wiki_pages "
            + "WHERE knowledge_base_id = #{kbId} AND status <> #{archived} AND deleted_at IS NULL "
            + "GROUP BY page_type")
    List<TypeCount> countByType(@Param("kbId") String kbId, @Param("archived") String archived);

    /** CountByType 的行载体 */
    class TypeCount {
        private String pageType = "";
        private long count;

        public String getPageType() { return pageType; }
        public void setPageType(String v) { this.pageType = v == null ? "" : v; }

        public long getCount() { return count; }
        public void setCount(long v) { this.count = v; }
    }

    /**
     * 统计没有入链的页面，<b>排除归档页</b>，
     * 并排除 index 页（它天然是根页面）。
     *
     * <p>入链为空的判定统一写成
     * {@code (in_links IS NULL OR CAST(in_links AS VARCHAR) = '[]')}：
     * PG 下 jsonb → varchar 对空数组给出 {@code []}，与按 jsonb 相等判定的结果一致
     * （jsonb 字面量 {@code null} 不算空）；H2 下该 CAST 是恒等变换，
     * 而 {@link WikiStringListTypeHandler} 对空列表写出的正是 {@code []}。</p>
     */
    @Select("SELECT COUNT(*) FROM wiki_pages WHERE knowledge_base_id = #{kbId} "
            + "AND status <> #{archived} AND deleted_at IS NULL "
            + "AND (in_links IS NULL OR CAST(in_links AS VARCHAR) = '[]') "
            + "AND page_type <> #{indexType}")
    long countOrphans(@Param("kbId") String kbId,
                      @Param("archived") String archived,
                      @Param("indexType") String indexType);

    /** 某文件夹下直接挂的活跃页面数（排除归档） */
    @Select("SELECT COUNT(*) FROM wiki_pages WHERE knowledge_base_id = #{kbId} "
            + "AND folder_id = #{folderId} AND status <> #{archived} AND deleted_at IS NULL")
    long countPagesInFolder(@Param("kbId") String kbId,
                            @Param("folderId") String folderId,
                            @Param("archived") String archived);

    /** CountPagesByFolder 的行载体（folder_id 为空串表示 wiki 根） */
    class FolderCount {
        private String folderId = "";
        private long count;

        public String getFolderId() { return folderId; }
        public void setFolderId(String v) { this.folderId = v == null ? "" : v; }

        public long getCount() { return count; }
        public void setCount(long v) { this.count = v; }
    }

    /** 按 folder_id 分组的活跃页面数 */
    @Select("<script>SELECT folder_id AS folderId, COUNT(*) AS count FROM wiki_pages "
            + "WHERE knowledge_base_id = #{kbId} AND status &lt;&gt; #{archived} AND deleted_at IS NULL "
            + "<if test='pageTypes != null and pageTypes.size() > 0'>"
            + "AND page_type IN <foreach collection='pageTypes' item='pt' open='(' separator=',' close=')'>#{pt}</foreach>"
            + "</if>"
            + "GROUP BY folder_id"
            + "</script>")
    List<FolderCount> countPagesByFolder(@Param("kbId") String kbId,
                                         @Param("archived") String archived,
                                         @Param("pageTypes") List<String> pageTypes);

    // ── 全文检索 ──

    /**
     * 全文检索。按命中位置排序，相关度从高到低：
     * <pre>
     *   title 命中 → 4（用户打的就是页面名，最直白的意图）
     *   slug  命中 → 3（类 URL 标识，直接跳转）
     *   summary 命中 → 2（作者写的短摘要）
     *   content 命中 → 1（正文提及——常把只是顺带提到检索词的无关页面顶上来）
     * </pre>
     * 没有这个排序时，在 4 万页的 wiki 上搜"王新"会看到"华为"或"Index"排在真正的
     * 王新页面前面，只因为它们在正文里提到过且更新更近。updated_at 仍是同档内的
     * 稳定 tiebreaker。
     *
     * <p>PG 用 {@code ~*}（大小写不敏感 POSIX 正则）；H2 没有该运算符，
     * 用 {@code REGEXP_LIKE(col, ?, 'i')} 表达同一语义
     * （H2 的 REGEXP_LIKE 走 JVM 正则，flags 'i' = 大小写不敏感）。</p>
     */
    @ResultMap("wikiPageResult")
    @Select("<script>SELECT *, "
            + "CASE "
            + "<choose><when test='postgres'>"
            + "WHEN title ~* #{query} THEN 4 WHEN slug ~* #{query} THEN 3 "
            + "WHEN summary ~* #{query} THEN 2 WHEN content ~* #{query} THEN 1 "
            + "</when><otherwise>"
            + "WHEN REGEXP_LIKE(title, #{query}, 'i') THEN 4 WHEN REGEXP_LIKE(slug, #{query}, 'i') THEN 3 "
            + "WHEN REGEXP_LIKE(summary, #{query}, 'i') THEN 2 WHEN REGEXP_LIKE(content, #{query}, 'i') THEN 1 "
            + "</otherwise></choose>"
            + "ELSE 0 END AS match_rank FROM wiki_pages "
            + "WHERE knowledge_base_id = #{kbId} AND deleted_at IS NULL "
            + "AND (<choose>"
            + "<when test='postgres'>"
            + "title ~* #{query} OR content ~* #{query} OR summary ~* #{query} OR slug ~* #{query}"
            + "</when><otherwise>"
            + "REGEXP_LIKE(title, #{query}, 'i') OR REGEXP_LIKE(content, #{query}, 'i') "
            + "OR REGEXP_LIKE(summary, #{query}, 'i') OR REGEXP_LIKE(slug, #{query}, 'i')"
            + "</otherwise></choose>) "
            + "AND status &lt;&gt; #{archived} "
            + "ORDER BY match_rank DESC, updated_at DESC LIMIT #{limit}"
            + "</script>")
    List<WikiPage> search(@Param("kbId") String kbId,
                          @Param("query") String query,
                          @Param("archived") String archived,
                          @Param("postgres") boolean postgres,
                          @Param("limit") int limit);

    /** 依赖 pg_trgm 三元组相似度；非 PG 方言下跑不起来 */
    @ResultMap("wikiPageLiteResult")
    @Select("<script>SELECT slug, title, page_type, status, aliases, out_links, "
            + "similarity(lower(title), #{query}) AS sim FROM wiki_pages "
            + "WHERE knowledge_base_id = #{kbId} AND deleted_at IS NULL AND status &lt;&gt; #{archived} "
            + "AND page_type IN <foreach collection='pageTypes' item='pt' open='(' separator=',' close=')'>#{pt}</foreach> "
            + "AND lower(title) % #{query} "
            + "ORDER BY sim DESC LIMIT #{limit}"
            + "</script>")
    List<WikiPageLite> findSimilarPages(@Param("kbId") String kbId,
                                        @Param("query") String query,
                                        @Param("archived") String archived,
                                        @Param("pageTypes") List<String> pageTypes,
                                        @Param("limit") int limit);

    // ── 软删除 ──

    /** 按 (kb, slug) 软删除，0 行 = not found */
    @Update("UPDATE wiki_pages SET deleted_at = #{deletedAt} "
            + "WHERE knowledge_base_id = #{kbId} AND slug = #{slug} AND deleted_at IS NULL")
    int softDeleteBySlug(@Param("kbId") String kbId, @Param("slug") String slug,
                         @Param("deletedAt") OffsetDateTime deletedAt);

    /** 按 id 软删除。 */
    @Update("UPDATE wiki_pages SET deleted_at = #{deletedAt} WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") String id, @Param("deletedAt") OffsetDateTime deletedAt);

}
