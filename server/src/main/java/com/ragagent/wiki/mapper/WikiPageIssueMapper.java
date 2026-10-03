package com.ragagent.wiki.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.domain.WikiStringListTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * wiki_page_issues 仓储语句。
 *
 * <p>落库隐式行为清单：</p>
 * <ul>
 *   <li><b>软删除</b>：查询显式 {@code deleted_at IS NULL}。</li>
 *   <li><b>排序</b>：{@code listIssues → created_at DESC}。</li>
 *   <li><b>updateIssueStatus</b>：单列更新
 *       （不做存在性检查，不报 not found）。</li>
 * </ul>
 */
@Mapper
public interface WikiPageIssueMapper extends BaseMapper<WikiPageIssue> {

    @Results(id = "wikiPageIssueResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "knowledge_base_id", property = "knowledgeBaseId"),
            @Result(column = "slug", property = "slug"),
            @Result(column = "issue_type", property = "issueType"),
            @Result(column = "description", property = "description"),
            @Result(column = "suspected_knowledge_ids", property = "suspectedKnowledgeIds",
                    typeHandler = WikiStringListTypeHandler.class),
            @Result(column = "status", property = "status"),
            @Result(column = "reported_by", property = "reportedBy"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "deleted_at", property = "deletedAt"),
    })
    @Select("<script>SELECT * FROM wiki_page_issues WHERE knowledge_base_id = #{kbId} "
            + "AND deleted_at IS NULL "
            + "<if test=\"slug != null and slug != ''\"> AND slug = #{slug}</if>"
            + "<if test=\"status != null and status != ''\"> AND status = #{status}</if>"
            + "ORDER BY created_at DESC"
            + "</script>")
    List<WikiPageIssue> listIssues(@Param("kbId") String kbId,
                                   @Param("slug") String slug,
                                   @Param("status") String status);

    /** 单列更新，无 not-found 语义 */
    @Update("UPDATE wiki_page_issues SET status = #{status} "
            + "WHERE id = #{issueId} AND deleted_at IS NULL")
    int updateIssueStatus(@Param("issueId") String issueId, @Param("status") String status);

    /** 供 service 读取单条（列表查询之外唯一的读取口；保留最小入口） */
    @ResultMap("wikiPageIssueResult")
    @Select("SELECT * FROM wiki_page_issues WHERE id = #{id} AND deleted_at IS NULL LIMIT 1")
    WikiPageIssue selectIssueById(@Param("id") String id);
}
