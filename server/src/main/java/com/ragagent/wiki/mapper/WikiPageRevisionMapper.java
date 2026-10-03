package com.ragagent.wiki.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiStringListTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * wiki_page_revisions 仓储语句。
 *
 * <p>落库隐式行为清单：</p>
 * <ul>
 *   <li><b>无软删除</b>：本表没有 deleted_at 列，删除都是硬删。</li>
 *   <li><b>列表投影</b>：{@link #listRevisions} 刻意不 SELECT content
 *       （见 {@link #LIST_COLUMNS}）。</li>
 *   <li><b>ON CONFLICT DO NOTHING</b>：让"同 (page_id, version) 已存在"成为静默 no-op。
 *       PG 原生支持；非 PG 方言（H2）退化为 {@code MERGE INTO ... KEY (page_id, version)}——
 *       重复时的净效果相同（历史里只留一份），因为并发写者写的是<b>同一份</b>快照。</li>
 * </ul>
 */
@Mapper
public interface WikiPageRevisionMapper extends BaseMapper<WikiPageRevision> {

    /** 列表投影列：除 content 外的全部列 */
    String LIST_COLUMNS = "id, tenant_id, knowledge_base_id, page_id, slug, version, "
            + "title, page_type, status, summary, aliases, edit_source, editor_id, edited_at, created_at";

    @Results(id = "wikiPageRevisionResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "knowledge_base_id", property = "knowledgeBaseId"),
            @Result(column = "page_id", property = "pageId"),
            @Result(column = "slug", property = "slug"),
            @Result(column = "version", property = "version"),
            @Result(column = "title", property = "title"),
            @Result(column = "page_type", property = "pageType"),
            @Result(column = "status", property = "status"),
            @Result(column = "content", property = "content"),
            @Result(column = "summary", property = "summary"),
            @Result(column = "aliases", property = "aliases",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "edit_source", property = "editSource"),
            @Result(column = "editor_id", property = "editorId"),
            @Result(column = "edited_at", property = "editedAt"),
            @Result(column = "created_at", property = "createdAt"),
    })
    @Select("SELECT * FROM wiki_page_revisions WHERE knowledge_base_id = #{kbId} "
            + "AND page_id = #{pageId} AND version = #{version} LIMIT 1")
    WikiPageRevision selectRevision(@Param("kbId") String kbId,
                                    @Param("pageId") String pageId,
                                    @Param("version") int version);

    /**
     * 插入已被取代的版本快照，重复的 (page_id, version) 是静默 no-op。
     */
    @Insert("<script><choose>"
            + "<when test='postgres'>"
            + "INSERT INTO wiki_page_revisions "
            + "(id, tenant_id, knowledge_base_id, page_id, slug, version, title, page_type, status, "
            + " content, summary, aliases, edit_source, editor_id, edited_at, created_at) VALUES ("
            + "#{rev.id}, #{rev.tenantId}, #{rev.knowledgeBaseId}, #{rev.pageId}, #{rev.slug}, "
            + "#{rev.version}, #{rev.title}, #{rev.pageType}, #{rev.status}, #{rev.content}, "
            + "#{rev.summary}, "
            + "#{rev.aliases, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "#{rev.editSource}, #{rev.editorId}, #{rev.editedAt}, #{rev.createdAt}) "
            + "ON CONFLICT (page_id, version) DO NOTHING"
            + "</when>"
            + "<otherwise>"
            + "MERGE INTO wiki_page_revisions "
            + "(id, tenant_id, knowledge_base_id, page_id, slug, version, title, page_type, status, "
            + " content, summary, aliases, edit_source, editor_id, edited_at, created_at) "
            + "KEY (page_id, version) VALUES ("
            + "#{rev.id}, #{rev.tenantId}, #{rev.knowledgeBaseId}, #{rev.pageId}, #{rev.slug}, "
            + "#{rev.version}, #{rev.title}, #{rev.pageType}, #{rev.status}, #{rev.content}, "
            + "#{rev.summary}, "
            + "#{rev.aliases, typeHandler=com.ragagent.wiki.domain.WikiStringListTypeHandler}, "
            + "#{rev.editSource}, #{rev.editorId}, #{rev.editedAt}, #{rev.createdAt})"
            + "</otherwise>"
            + "</choose></script>")
    int insertSnapshotDoNothing(@Param("rev") WikiPageRevision rev,
                                @Param("postgres") boolean postgres);

    /** 修订总数（配合 {@link #listRevisions}） */
    @Select("SELECT COUNT(*) FROM wiki_page_revisions WHERE knowledge_base_id = #{kbId} "
            + "AND page_id = #{pageId}")
    long countRevisions(@Param("kbId") String kbId, @Param("pageId") String pageId);

    /** 修订列表：最新在前，content 省略 */
    @ResultMap("wikiPageRevisionResult")
    @Select("SELECT " + LIST_COLUMNS + " FROM wiki_page_revisions "
            + "WHERE knowledge_base_id = #{kbId} AND page_id = #{pageId} "
            + "ORDER BY version DESC LIMIT #{limit} OFFSET #{offset}")
    List<WikiPageRevision> listRevisions(@Param("kbId") String kbId,
                                         @Param("pageId") String pageId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    /** 软上限分支：只删可剪枝来源的旧快照 */
    @Update("<script>DELETE FROM wiki_page_revisions WHERE page_id = #{pageId} "
            + "AND version &lt; #{keepFromVersion} AND edit_source IN "
            + "<foreach collection='prunableSources' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</script>")
    int pruneBySources(@Param("pageId") String pageId,
                       @Param("keepFromVersion") int keepFromVersion,
                       @Param("prunableSources") List<String> prunableSources);

    /** 硬上限分支：无视作者 */
    @Update("DELETE FROM wiki_page_revisions WHERE page_id = #{pageId} AND version < #{hardKeepFromVersion}")
    int pruneBelowVersion(@Param("pageId") String pageId,
                          @Param("hardKeepFromVersion") int hardKeepFromVersion);

    /**
     * 硬删整页快照历史。
     *
     * <p>页面本身是软删，但软删后的页面在任何读路径上都不可达，其快照就是死重量——
     * 而它们是 wiki 里最占空间的行。page_id 为空时由 repository 短路，
     * 避免变成全表删除。</p>
     */
    @Update("DELETE FROM wiki_page_revisions WHERE page_id = #{pageId}")
    int deleteByPageId(@Param("pageId") String pageId);
}
