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

    @Insert("INSERT INTO knowledge_tag_relations (knowledge_id, tag_id) "
            + "VALUES (#{knowledgeId}, #{tagId})")
    int insertRelation(String knowledgeId, String tagId);

    // ── FAQ 模块需要的 tag 读取/创建（对照 Go repository/tag.go） ────────────

    /**
     * 对照 {@code GetBySeqID}（tag.go L58-67）：tenant + seq_id，软删行不可见
     * （GORM DeletedAt）。Go 的 First 找不到返回 ErrRecordNotFound；这里返回 null
     * 由 FAQ service 决定 404/错误文案。
     */
    @Select("SELECT id, seq_id AS seqId, tenant_id AS tenantId, "
            + "knowledge_base_id AS knowledgeBaseId, name, color, "
            + "sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM knowledge_tags WHERE tenant_id = #{tenantId} AND seq_id = #{seqId} "
            + "LIMIT 1")
    KnowledgeTag selectByTenantAndSeqId(long tenantId, long seqId);

    /** 对照 {@code GetBySeqIDs}（tag.go L69-81）。 */
    @Select("""
            <script>
            SELECT id, seq_id AS seqId, tenant_id AS tenantId,
                   knowledge_base_id AS knowledgeBaseId, name, color,
                   sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_tags WHERE tenant_id = #{tenantId} AND seq_id IN
            <foreach collection="seqIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    List<KnowledgeTag> selectByTenantAndSeqIds(long tenantId, List<Long> seqIds);

    /** 对照 {@code GetByName}（tag.go L83-92）：tenant + kb + name（无 deleted_at 条件——照抄 Go）。 */
    @Select("SELECT id, seq_id AS seqId, tenant_id AS tenantId, "
            + "knowledge_base_id AS knowledgeBaseId, name, color, "
            + "sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM knowledge_tags WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId} "
            + "AND name = #{name} LIMIT 1")
    KnowledgeTag selectByTenantKbAndName(long tenantId, String kbId, String name);

    /**
     * 对照 {@code ListByKB}（tag.go L94-137）的 FAQ 消费面（buildTagMap，keyword 恒 ""）：
     * 排序 {@code sort_order ASC, created_at DESC, seq_id DESC}（seq_id 决胜 OFFSET 翻页）。
     * keyword 的 LIKE 过滤是 tag 管理面（ListTags）的分支，FAQ 流程不可达，未带。
     */
    @Select("SELECT id, seq_id AS seqId, tenant_id AS tenantId, "
            + "knowledge_base_id AS knowledgeBaseId, name, color, "
            + "sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM knowledge_tags WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId} "
            + "ORDER BY sort_order ASC, created_at DESC, seq_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<KnowledgeTag> listByKB(long tenantId, String kbId, int limit, int offset);

    /** 插入（显式 seq_id——方言差异由 KnowledgeTagRepository 决定：PG 用序列、H2 用 max+1）。 */
    @Insert("INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, name, color, "
            + "sort_order, created_at, updated_at) "
            + "VALUES (#{t.id}, #{t.seqId}, #{t.tenantId}, "
            + "#{t.knowledgeBaseId}, #{t.name}, #{t.color}, #{t.sortOrder}, #{t.createdAt}, #{t.updatedAt})")
    int insertTag(@org.apache.ibatis.annotations.Param("t") KnowledgeTag t);

    /** PG 专用插入：seq_id 由列默认序列分配（对照 Go 的 autoIncrement tag）。 */
    @Insert("INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, name, color, "
            + "sort_order, created_at, updated_at) "
            + "VALUES (#{t.id}, NEXTVAL('knowledge_tags_seq_id_seq'), #{t.tenantId}, "
            + "#{t.knowledgeBaseId}, #{t.name}, #{t.color}, #{t.sortOrder}, #{t.createdAt}, #{t.updatedAt})")
    int insertTagPg(@org.apache.ibatis.annotations.Param("t") KnowledgeTag t);

    /** 当前最大 seq_id（H2 的 max+1，对照 Go KnowledgeTag.BeforeCreate 的 sqlite 分支）。 */
    @Select("SELECT COALESCE(MAX(seq_id), 0) FROM knowledge_tags")
    long maxSeqId();

    /**
     * 对照 {@code DeleteUnusedTags}（tag.go L229-240）：删掉既无 knowledge 关联
     * （活跃 knowledge）也无 chunk 引用的标签，返回删除行数。
     */
    @Delete("DELETE FROM knowledge_tags WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId} "
            + "AND id NOT IN (SELECT DISTINCT ktr.tag_id FROM knowledge_tag_relations ktr "
            + "JOIN knowledges k ON ktr.knowledge_id = k.id AND k.deleted_at IS NULL "
            + "AND k.tenant_id = #{tenantId} AND k.knowledge_base_id = #{kbId}) "
            + "AND id NOT IN (SELECT DISTINCT tag_id FROM chunks WHERE tenant_id = #{tenantId} "
            + "AND knowledge_base_id = #{kbId} AND tag_id IS NOT NULL AND tag_id != '' AND deleted_at IS NULL)")
    int deleteUnusedTags(long tenantId, String kbId);
}
