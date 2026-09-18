package com.ragagent.knowledge.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.ragagent.knowledge.domain.KnowledgeTag;

/**
 * knowledge_tags + knowledge_tag_relations 访问（对照 Go
 * internal/application/repository/{tag,knowledge_tag}.go 的子集）。
 *
 * <p>自定义 @Select 不套实体 typeHandler（本项目无此列），但
 * <b>连接查投影的列名蛇形 → 属性驼峰</b>依赖 MP 的 map-underscore 全局开关，
 * 与 ChunkRepository 的投影行同款写法。</p>
 */
@Mapper
public interface KnowledgeTagMapper {

    /** 对照 GetKnowledgeTags 的连接查：关系 + 标签全列（无 ORDER BY，照抄 Go）。 */
    @Select("""
            <script>
            SELECT ktr.knowledge_id AS knowledgeId, kt.id, kt.seq_id AS seqId,
                   kt.tenant_id AS tenantId, kt.knowledge_base_id AS knowledgeBaseId,
                   kt.name, kt.color, kt.sort_order AS sortOrder,
                   kt.created_at AS createdAt, kt.updated_at AS updatedAt
            FROM knowledge_tag_relations ktr
            JOIN knowledge_tags kt ON ktr.tag_id = kt.id
            WHERE ktr.knowledge_id IN
            <foreach collection="knowledgeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<KnowledgeTag> selectTagsWithKnowledgeId(List<String> knowledgeIds);

    /** 对照 knowledgeTagRepository.GetByIDs（按租户取标签，去重由调用方负责）。 */
    @Select("""
            <script>
            SELECT id, seq_id AS seqId, tenant_id AS tenantId,
                   knowledge_base_id AS knowledgeBaseId, name, color,
                   sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_tags
            WHERE tenant_id = #{tenantId} AND deleted_at IS NULL AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<KnowledgeTag> selectByTenantAndIds(long tenantId, List<String> ids);

    /** 对照 SetKnowledgeTags 的关系替换（事务由调用方 @Transactional 保证）。 */
    @Delete("DELETE FROM knowledge_tag_relations WHERE knowledge_id = #{knowledgeId}")
    int deleteRelations(String knowledgeId);

    @Insert("INSERT INTO knowledge_tag_relations (knowledge_id, tag_id, created_at) "
            + "VALUES (#{knowledgeId}, #{tagId}, CURRENT_TIMESTAMP)")
    int insertRelation(String knowledgeId, String tagId);
}
