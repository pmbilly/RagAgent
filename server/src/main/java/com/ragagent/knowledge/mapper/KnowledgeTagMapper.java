package com.ragagent.knowledge.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.ragagent.knowledge.domain.KnowledgeTag;
import org.apache.ibatis.annotations.Param;

/**
 * knowledge_tags + knowledge_tag_relations 访问。
 * <p>自定义 @Select 不套实体 typeHandler（本项目无此列），但
 * <b>连接查投影的列名蛇形 → 属性驼峰</b>依赖 MP 的 map-underscore 全局开关，
 * 与 ChunkRepository 的投影行同款写法。</p>
 */
@Mapper
public interface KnowledgeTagMapper {

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

    @Delete("DELETE FROM knowledge_tag_relations WHERE knowledge_id = #{knowledgeId}")
    int deleteRelations(String knowledgeId);

    /**
     * 标签关联的
     */
    @Select("""
            <script>
            SELECT DISTINCT k.id
            FROM knowledges k
            JOIN knowledge_tag_relations ktr ON k.id = ktr.knowledge_id
            WHERE k.tenant_id = #{tenantId}
              AND k.knowledge_base_id = #{kbId}
              AND k.deleted_at IS NULL
              AND ktr.tag_id IN
            <foreach collection="tagIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            </script>
            """)
    List<String> selectKnowledgeIdsByTagIds(long tenantId, String kbId, List<String> tagIds);

    @Insert("INSERT INTO knowledge_tag_relations (knowledge_id, tag_id) "
            + "VALUES (#{knowledgeId}, #{tagId})")
    int insertRelation(String knowledgeId, String tagId);

    // ── FAQ 模块需要的 tag 读取/创建 ────────────

    /**
     * 由 FAQ service 决定 404/错误文案。
     */
    @Select("SELECT id, seq_id AS seqId, tenant_id AS tenantId, "
            + "knowledge_base_id AS knowledgeBaseId, name, color, "
            + "sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM knowledge_tags WHERE tenant_id = #{tenantId} AND seq_id = #{seqId} "
            + "LIMIT 1")
    KnowledgeTag selectByTenantAndSeqId(long tenantId, long seqId);

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

    @Select("SELECT id, seq_id AS seqId, tenant_id AS tenantId, "
            + "knowledge_base_id AS knowledgeBaseId, name, color, "
            + "sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM knowledge_tags WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId} "
            + "AND name = #{name} LIMIT 1")
    KnowledgeTag selectByTenantKbAndName(long tenantId, String kbId, String name);

    /**
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
    int insertTag(@Param("t") KnowledgeTag t);

    /** PG 专用插入：seq_id 由列默认序列分配。 */
    @Insert("INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, name, color, "
            + "sort_order, created_at, updated_at) "
            + "VALUES (#{t.id}, NEXTVAL('knowledge_tags_seq_id_seq'), #{t.tenantId}, "
            + "#{t.knowledgeBaseId}, #{t.name}, #{t.color}, #{t.sortOrder}, #{t.createdAt}, #{t.updatedAt})")
    int insertTagPg(@Param("t") KnowledgeTag t);

    @Select("SELECT COALESCE(MAX(seq_id), 0) FROM knowledge_tags")
    long maxSeqId();

    @Select("SELECT seq_id FROM knowledge_tags WHERE id = #{id}")
    Long selectSeqIdById(String id);

    /**
     * （活跃 knowledge）也无 chunk 引用的标签，返回删除行数。
     */
    @Delete("DELETE FROM knowledge_tags WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId} "
            + "AND id NOT IN (SELECT DISTINCT ktr.tag_id FROM knowledge_tag_relations ktr "
            + "JOIN knowledges k ON ktr.knowledge_id = k.id AND k.deleted_at IS NULL "
            + "AND k.tenant_id = #{tenantId} AND k.knowledge_base_id = #{kbId}) "
            + "AND id NOT IN (SELECT DISTINCT tag_id FROM chunks WHERE tenant_id = #{tenantId} "
            + "AND knowledge_base_id = #{kbId} AND tag_id IS NOT NULL AND tag_id != '' AND deleted_at IS NULL)")
    int deleteUnusedTags(long tenantId, String kbId);

    // ── KB 标签 CRUD ─────────────────────

    /**
     * struct **没有** 软删列 deleted_at 字段（deleted_at 列恒 NULL、从不写）→
     * Delete 是**硬删**、查询不带软删过滤（selectByTenantAndIds 的
     * deleted_at IS NULL 是无害 no-op，保留兼容）。
     */
    @Select("SELECT id, seq_id AS seqId, tenant_id AS tenantId, "
            + "knowledge_base_id AS knowledgeBaseId, name, color, "
            + "sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM knowledge_tags WHERE tenant_id = #{tenantId} AND id = #{id} LIMIT 1")
    KnowledgeTag selectByTenantAndId(long tenantId, String id);

    @Select("""
            <script>
            SELECT id, seq_id AS seqId, tenant_id AS tenantId,
                   knowledge_base_id AS knowledgeBaseId, name, color,
                   sort_order AS sortOrder, created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_tags
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId}
            <if test="escapedKeyword != null and escapedKeyword != ''">
              AND name LIKE CONCAT('%', #{escapedKeyword}, '%')
            </if>
            ORDER BY sort_order ASC, created_at DESC, seq_id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<KnowledgeTag> listByKBKeyword(long tenantId, String kbId,
                                       @Param("escapedKeyword") String escapedKeyword,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM knowledge_tags
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId}
            <if test="escapedKeyword != null and escapedKeyword != ''">
              AND name LIKE CONCAT('%', #{escapedKeyword}, '%')
            </if>
            </script>
            """)
    long countByKB(long tenantId, String kbId,
                   @Param("escapedKeyword") String escapedKeyword);

    /** 按主键整行覆写（含零值）。 */
    @Update("UPDATE knowledge_tags SET seq_id = #{t.seqId}, tenant_id = #{t.tenantId}, "
            + "knowledge_base_id = #{t.knowledgeBaseId}, name = #{t.name}, color = #{t.color}, "
            + "sort_order = #{t.sortOrder}, created_at = #{t.createdAt}, updated_at = #{t.updatedAt} "
            + "WHERE id = #{t.id}")
    int updateTag(@Param("t") KnowledgeTag t);

    @Delete("DELETE FROM knowledge_tags WHERE tenant_id = #{tenantId} AND id = #{id}")
    int deleteByTenantAndId(long tenantId, String id);

    @Select("SELECT COUNT(*) FROM knowledge_tag_relations ktr "
            + "JOIN knowledges k ON ktr.knowledge_id = k.id AND k.deleted_at IS NULL "
            + "AND k.tenant_id = #{tenantId} AND k.knowledge_base_id = #{kbId} "
            + "WHERE ktr.tag_id = #{tagId}")
    long countKnowledgeRefs(long tenantId, String kbId, String tagId);

    @Select("SELECT COUNT(*) FROM chunks WHERE tenant_id = #{tenantId} "
            + "AND knowledge_base_id = #{kbId} AND tag_id = #{tagId} AND deleted_at IS NULL")
    long countChunkRefs(long tenantId, String kbId, String tagId);

    @Select("""
            <script>
            SELECT ktr.tag_id AS tagId, COUNT(*) AS cnt
            FROM knowledge_tag_relations ktr
            JOIN knowledges k ON ktr.knowledge_id = k.id AND k.deleted_at IS NULL
              AND k.tenant_id = #{tenantId} AND k.knowledge_base_id = #{kbId}
            WHERE ktr.tag_id IN
            <foreach collection="tagIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            GROUP BY ktr.tag_id
            </script>
            """)
    List<TagCountRow> batchCountKnowledgeRefs(long tenantId, String kbId,
                                              @Param("tagIds") List<String> tagIds);

    @Select("""
            <script>
            SELECT tag_id AS tagId, COUNT(*) AS cnt FROM chunks
            WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId}
              AND deleted_at IS NULL AND tag_id IN
            <foreach collection="tagIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            GROUP BY tag_id
            </script>
            """)
    List<TagCountRow> batchCountChunkRefs(long tenantId, String kbId,
                                          @Param("tagIds") List<String> tagIds);

    /** 分组计数投影行（列别名经 map-underscore → 属性）。 */
    class TagCountRow {
        private String tagId;
        private long cnt;
        public String getTagId() { return tagId; }
        public void setTagId(String v) { tagId = v; }
        public long getCnt() { return cnt; }
        public void setCnt(long v) { cnt = v; }
    }
}
