package com.ragagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.knowledge.domain.Chunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.ragagent.common.web.PgJsonTypeHandler;
import java.util.List;

@Mapper
/** {@code chunks} 表的 MyBatis-Plus mapper。 */
public interface ChunkMapper extends BaseMapper<Chunk> {

    @Select("SELECT id FROM chunks WHERE tenant_id = #{tenantId} "
            + "AND knowledge_base_id = #{kbId} AND tag_id = #{tagId} AND deleted_at IS NULL")
    List<String> selectIdsByTag(long tenantId, String kbId, String tagId);

    /**
     * 全字段 UPDATE。
     * <p>写成显式 SQL 而不是 wrapper，是因为 wrapper 的 {@code set()} 不套实体上的
     * {@code typeHandler}（三个 json 列必须 3 参 set 或显式注解，见本仓约定）。
     * 三条 json 列挂 {@code PgJsonTypeHandler, jdbcType=OTHER}（null 也走 typeHandler）；
     * 可空时间列显式 {@code jdbcType=TIMESTAMP_WITH_TIMEZONE}（memory 模块同款先例）。</p>
     * <p>影响行数为 0 时不回退把整行再插回去；service 层总是先查后存，HTTP 面不可达该分支。</p>
     */
    @Update("UPDATE chunks SET "
            + "tenant_id = #{c.tenantId}, "
            + "knowledge_id = #{c.knowledgeId}, "
            + "knowledge_base_id = #{c.knowledgeBaseId}, "
            + "tag_id = #{c.tagId}, "
            + "content = #{c.content}, "
            + "source_content = #{c.sourceContent}, "
            + "content_revision = #{c.contentRevision}, "
            + "index_status = #{c.indexStatus}, "
            + "last_editor_id = #{c.lastEditorId}, "
            + "chunk_index = #{c.chunkIndex}, "
            + "is_enabled = #{c.isEnabled}, "
            + "flags = #{c.flags}, "
            + "status = #{c.status}, "
            + "start_at = #{c.startAt}, "
            + "end_at = #{c.endAt}, "
            + "pre_chunk_id = #{c.preChunkId}, "
            + "next_chunk_id = #{c.nextChunkId}, "
            + "chunk_type = #{c.chunkType}, "
            + "parent_chunk_id = #{c.parentChunkId}, "
            + "relation_chunks = #{c.relationChunks, typeHandler=com.ragagent.common.web.PgJsonTypeHandler, "
            + "jdbcType=OTHER}, "
            + "indirect_relation_chunks = #{c.indirectRelationChunks, typeHandler=com.ragagent.common.web.PgJsonTypeHandler, "
            + "jdbcType=OTHER}, "
            + "metadata = #{c.metadata, typeHandler=com.ragagent.common.web.PgJsonTypeHandler, "
            + "jdbcType=OTHER}, "
            + "content_hash = #{c.contentHash}, "
            + "image_info = #{c.imageInfo}, "
            + "context_header = #{c.contextHeader}, "
            + "created_at = #{c.createdAt, jdbcType=TIMESTAMP_WITH_TIMEZONE}, "
            + "updated_at = #{c.updatedAt, jdbcType=TIMESTAMP_WITH_TIMEZONE}, "
            + "deleted_at = #{c.deletedAt, jdbcType=TIMESTAMP_WITH_TIMEZONE} "
            + "WHERE id = #{c.id} AND deleted_at IS NULL")
    int updateAllFieldsExceptSeqId(@Param("c") Chunk chunk);

    /**
     * （chunk）：standard_question IN / similar_questions 数组交集。
     * status ∈ {default 0, stored 1, indexed 2}（stored 的兄弟请求也算，防重试插重行），
     * {@code @Results}（自定义 @Select 不套实体 typeHandler，本仓约定）。
     */
    @Select("<script>"
            + "SELECT id, metadata FROM chunks "
            + "WHERE tenant_id = #{tenantId} AND knowledge_base_id = #{kbId} "
            + "AND chunk_type = 'faq' AND status IN (0, 1, 2) AND id != #{excludeChunkId} "
            + "AND deleted_at IS NULL "
            + "AND (metadata->>'standard_question' IN "
            + "<foreach collection='questions' item='q' open='(' close=')' separator=','>#{q}</foreach> "
            + "OR EXISTS (SELECT 1 FROM jsonb_array_elements_text("
            + "COALESCE(metadata->'similar_questions', '[]'::jsonb)) elem "
            + "WHERE elem.value IN "
            + "<foreach collection='questions' item='q' open='(' close=')' separator=','>#{q}</foreach>)) "
            + "LIMIT 1"
            + "</script>")
    @Results({
            @Result(column = "id", property = "id"),
            @Result(column = "metadata", property = "metadata",
                    typeHandler = PgJsonTypeHandler.class),
    })
    Chunk findFaqDuplicateChunk(@Param("tenantId") long tenantId,
                                @Param("kbId") String kbId,
                                @Param("excludeChunkId") String excludeChunkId,
                                @Param("questions") List<String> questions);

    /**
     * CASE 批量更新
     * content / is_enabled / tag_id / flags / status + updated_at=NOW()
     * （一条语句一个时刻，排序敏感）。metadata / content_hash 不在此更新
     */
    @Update("<script>"
            + "UPDATE chunks SET "
            + "content = CASE <foreach collection='chunks' item='c'>WHEN id = #{c.id} THEN #{c.content}</foreach> ELSE content END, "
            + "is_enabled = CASE <foreach collection='chunks' item='c'>WHEN id = #{c.id} THEN #{c.isEnabled}</foreach> ELSE is_enabled END, "
            + "tag_id = CASE <foreach collection='chunks' item='c'>WHEN id = #{c.id} THEN #{c.tagId}</foreach> ELSE tag_id END, "
            + "flags = CASE <foreach collection='chunks' item='c'>WHEN id = #{c.id} THEN #{c.flags}</foreach> ELSE flags END, "
            + "status = CASE <foreach collection='chunks' item='c'>WHEN id = #{c.id} THEN #{c.status}</foreach> ELSE status END, "
            + "updated_at = NOW() "
            + "WHERE id IN <foreach collection='chunks' item='c' open='(' close=')' separator=','>#{c.id}</foreach> "
            + "AND deleted_at IS NULL"
            + "</script>")
    int updateChunksCase(@Param("chunks") List<Chunk> chunks);
}
