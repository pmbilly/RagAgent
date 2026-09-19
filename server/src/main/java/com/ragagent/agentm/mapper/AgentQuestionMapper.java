package com.ragagent.agentm.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * suggested-questions 的候选 chunk 查询（对照 Go repository/chunk.go 两个推荐方法）。
 *
 * <p>Go 用 GORM {@code Order("RANDOM()")} 取池；Java 侧同语义（H2 RAND() /
 * PG RANDOM()）。golden 只录确定性场景（单元素池），随机序不进契约。</p>
 */
public interface AgentQuestionMapper {

    /**
     * 对照 ListRecommendedFAQChunks：flags &amp; 1 = ChunkFlagRecommended，
     * status IN (default=0, indexed=2)，kb/knowledge/tag 三个范围 OR 组合。
     */
    @Select("<script>"
            + "SELECT id, knowledge_id AS \"knowledgeId\", knowledge_base_id AS \"knowledgeBaseId\", "
            + "CAST(metadata AS VARCHAR(1048576)) AS \"metadata\" FROM chunks "
            + "WHERE tenant_id = #{tenantId} AND chunk_type = 'faq' "
            + "AND status IN (0, 2) AND is_enabled = TRUE AND MOD(flags, 2) &lt;&gt; 0 "
            + "<if test='hasKb or hasKnowledge or hasTag'> AND ("
            + "<trim prefixOverrides='OR '>"
            + "<if test='hasKb'>knowledge_base_id IN "
            + "<foreach item='i' collection='kbIds' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</if>"
            + "<if test='hasKnowledge'><if test=\"hasKb\"> OR </if>knowledge_id IN "
            + "<foreach item='i' collection='knowledgeIds' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</if>"
            + "<if test='hasTag'><if test=\"hasKb or hasKnowledge\"> OR </if>tag_id IN "
            + "<foreach item='i' collection='tagIds' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</if>"
            + "</trim>)</if>"
            + " ORDER BY RANDOM() LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> listRecommendedFaqChunks(@Param("tenantId") long tenantId,
            @Param("kbIds") List<String> kbIds, @Param("hasKb") boolean hasKb,
            @Param("knowledgeIds") List<String> knowledgeIds, @Param("hasKnowledge") boolean hasKnowledge,
            @Param("tagIds") List<String> tagIds, @Param("hasTag") boolean hasTag,
            @Param("limit") int limit);

    /**
     * 对照 ListRecentDocumentChunksWithQuestions：metadata 带非空 generated_questions
     * 的 text chunk。PG 分支的 jsonb_array_length 判断在两侧统一改写成
     * CAST + LIKE 近似（键名必然出现在 jsonb 原文里；false positive 由 Java 侧
     * 解析兜底），单语句通吃 PG/H2。
     */
    @Select("<script>"
            + "SELECT id, knowledge_id AS \"knowledgeId\", knowledge_base_id AS \"knowledgeBaseId\", "
            + "CAST(metadata AS VARCHAR(1048576)) AS \"metadata\" FROM chunks "
            + "WHERE tenant_id = #{tenantId} AND chunk_type = 'text' "
            + "AND status IN (0, 2) AND is_enabled = TRUE "
            + "AND metadata IS NOT NULL "
            + "AND CAST(metadata AS VARCHAR(1048576)) LIKE '%generated_questions%' "
            + "<if test='hasKb and hasKnowledge'> AND (knowledge_base_id IN "
            + "<foreach item='i' collection='kbIds' open='(' separator=',' close=')'>#{i}</foreach>"
            + " OR knowledge_id IN "
            + "<foreach item='i' collection='knowledgeIds' open='(' separator=',' close=')'>#{i}</foreach>)"
            + "</if>"
            + "<if test='!hasKb and hasKnowledge'> AND knowledge_id IN "
            + "<foreach item='i' collection='knowledgeIds' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</if>"
            + "<if test='hasKb and !hasKnowledge'> AND knowledge_base_id IN "
            + "<foreach item='i' collection='kbIds' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</if>"
            + " ORDER BY RANDOM() LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> listRecentDocumentChunksWithQuestions(@Param("tenantId") long tenantId,
            @Param("kbIds") List<String> kbIds, @Param("hasKb") boolean hasKb,
            @Param("knowledgeIds") List<String> knowledgeIds, @Param("hasKnowledge") boolean hasKnowledge,
            @Param("limit") int limit);

    /** 知识库行是否存在（groupKBIDsByEffectiveTenant 的 GetKnowledgeBasesByIDsOnly 等价物）。 */
    @Select("<script>SELECT id AS \"id\", tenant_id AS \"tenantId\" FROM knowledge_bases "
            + "WHERE deleted_at IS NULL AND id IN "
            + "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<Map<String, Object>> findKbs(@Param("ids") List<String> ids);

    /** 对照 knowledge_tag repo GetByIDs：按 id + tenant 取 tag 行。 */
    @Select("<script>SELECT id AS \"id\", knowledge_base_id AS \"knowledgeBaseId\" FROM knowledge_tags "
            + "WHERE tenant_id = #{tenantId} AND id IN "
            + "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<Map<String, Object>> findTags(@Param("tenantId") long tenantId, @Param("ids") List<String> ids);

    /** 对照 knowledge repo ListIDsByTagIDs（OR 语义 distinct）。 */
    @Select("<script>SELECT DISTINCT k.id FROM knowledges k "
            + "JOIN knowledge_tag_relations ktr ON k.id = ktr.knowledge_id "
            + "WHERE k.tenant_id = #{tenantId} AND k.knowledge_base_id = #{kbId} AND ktr.tag_id IN "
            + "<foreach item='i' collection='tagIds' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<String> listKnowledgeIdsByTagIds(@Param("tenantId") long tenantId,
            @Param("kbId") String kbId, @Param("tagIds") List<String> tagIds);
}
