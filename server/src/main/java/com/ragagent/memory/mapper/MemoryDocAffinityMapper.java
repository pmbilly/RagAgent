package com.ragagent.memory.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.memory.domain.MemoryDocAffinity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code memory_doc_affinity} 的仓储（对照 Go internal/application/repository/memory.go
 * L838-976 的 {@code BumpDocAffinity} / {@code DocAffinity} / {@code TopDocAffinity} /
 * {@code DocAffinityByID} / {@code ListFamiliarDocs} / {@code DeleteDocAffinity} /
 * {@code DeleteAllDocAffinity}）。
 *
 * <p>唯一约束 {@code idx_mem_affinity_scope (tenant_id, subject_id, knowledge_id)}。</p>
 *
 * <p><b>一处刻意的照抄</b>：{@code BumpDocAffinity} 的 UPDATE 里
 * {@code title} 与 {@code knowledge_base_id} 是**条件写**——只有传进来的值非空才覆盖。
 * 这是为了不让一次没带标题的引用把已有标题冲成空串。</p>
 */
@Mapper
public interface MemoryDocAffinityMapper extends BaseMapper<MemoryDocAffinity> {

    /** 对照 {@code BumpDocAffinity} 的 insert-or-ignore（{@code Hits: 0}，真值来自自增）。 */
    @Insert("INSERT INTO memory_doc_affinity "
            + "(id, tenant_id, subject_id, knowledge_id, knowledge_base_id, title, hits, "
            + " last_used_at, created_at, updated_at) "
            + "VALUES (#{r.id}, #{r.tenantId}, #{r.subjectId}, #{r.knowledgeId}, #{r.knowledgeBaseId}, "
            + "        #{r.title}, #{r.hits}, #{r.lastUsedAt}, #{r.createdAt}, #{r.updatedAt}) "
            + "ON CONFLICT (tenant_id, subject_id, knowledge_id) DO NOTHING")
    int insertIfAbsentPostgres(@Param("r") MemoryDocAffinity row);

    /** H2 没有 {@code ON CONFLICT}——等价的条件插入。 */
    @Insert("INSERT INTO memory_doc_affinity "
            + "(id, tenant_id, subject_id, knowledge_id, knowledge_base_id, title, hits, "
            + " last_used_at, created_at, updated_at) "
            + "SELECT #{r.id}, #{r.tenantId}, #{r.subjectId}, #{r.knowledgeId}, #{r.knowledgeBaseId}, "
            + "       #{r.title}, #{r.hits}, #{r.lastUsedAt}, #{r.createdAt}, #{r.updatedAt} "
            + "WHERE NOT EXISTS (SELECT 1 FROM memory_doc_affinity "
            + "  WHERE tenant_id = #{r.tenantId} AND subject_id = #{r.subjectId} "
            + "  AND knowledge_id = #{r.knowledgeId})")
    int insertIfAbsentOther(@Param("r") MemoryDocAffinity row);

    /**
     * 对照 {@code BumpDocAffinity} 的 UPDATE。
     *
     * <p>{@code title} / {@code knowledge_base_id} 走 {@code <if>}：
     * Go 只在非空时才把它们放进 map，空值就保持原样。</p>
     */
    @Update("<script>"
            + "UPDATE memory_doc_affinity SET hits = hits + 1, last_used_at = #{now}, updated_at = #{now}"
            + "<if test='title != null and title != \"\"'>, title = #{title}</if>"
            + "<if test='knowledgeBaseId != null and knowledgeBaseId != \"\"'>, knowledge_base_id = #{knowledgeBaseId}</if>"
            + " WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND knowledge_id = #{knowledgeId}"
            + "</script>")
    int bump(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
             @Param("knowledgeId") String knowledgeId, @Param("title") String title,
             @Param("knowledgeBaseId") String knowledgeBaseId, @Param("now") OffsetDateTime now);

    /** 对照 {@code DocAffinity}：按 knowledge_id 批量取命中数。 */
    @Select("<script>"
            + "SELECT * FROM memory_doc_affinity WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND knowledge_id IN <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<MemoryDocAffinity> selectByKnowledgeIds(@Param("tenantId") long tenantId,
                                                 @Param("subjectId") String subjectId,
                                                 @Param("ids") List<String> knowledgeIds);

    /** 对照 {@code TopDocAffinity}：{@code hits DESC, last_used_at DESC}。 */
    @Select("<script>"
            + "SELECT * FROM memory_doc_affinity WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "ORDER BY hits DESC, last_used_at DESC"
            + "<if test='limit &gt; 0'> LIMIT #{limit}</if>"
            + "</script>")
    List<MemoryDocAffinity> topAffinity(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                        @Param("limit") int limit);

    /** 对照 {@code DocAffinityByID}。 */
    @Select("SELECT * FROM memory_doc_affinity WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id = #{id}")
    MemoryDocAffinity selectScopedById(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                       @Param("id") String id);

    /** 对照 {@code ListFamiliarDocs} 的计数（{@code hits >= minHits}）。 */
    @Select("SELECT COUNT(*) FROM memory_doc_affinity WHERE tenant_id = #{tenantId} "
            + "AND subject_id = #{subjectId} AND hits >= #{minHits}")
    long countFamiliar(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                       @Param("minHits") int minHits);

    /** 对照 {@code ListFamiliarDocs} 的数据页。 */
    @Select("SELECT * FROM memory_doc_affinity WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND hits >= #{minHits} ORDER BY hits DESC, last_used_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<MemoryDocAffinity> listFamiliar(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                         @Param("minHits") int minHits,
                                         @Param("limit") int limit, @Param("offset") int offset);

    /**
     * 对照 {@code DeleteDocAffinity}：带 scope 的物理删。
     *
     * <p>⚠️ 不能用 {@code deleteById}——只按主键会删掉别的主体的行。</p>
     */
    @Delete("DELETE FROM memory_doc_affinity WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id = #{id}")
    int deleteScoped(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                     @Param("id") String id);

    /** 对照 {@code DeleteAllDocAffinity}：整 scope 的物理删。 */
    @Delete("DELETE FROM memory_doc_affinity WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int deleteAllInScope(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId);
}
