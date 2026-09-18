package com.ragagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.knowledge.domain.Chunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChunkMapper extends BaseMapper<Chunk> {

    /**
     * 全字段 UPDATE（对照 GORM {@code Omit("SeqID").Save(chunk)}——Save 对有主键的行
     * 是 {@code Select("*")} 的全字段 UPDATE，零值也写；seq_id 被排除）。
     *
     * <p>写成显式 SQL 而不是 wrapper，是因为 wrapper 的 {@code set()} 不套实体上的
     * {@code typeHandler}（三个 json 列必须 3 参 set 或显式注解，见约定 §9）。
     * 三条 json 列挂 {@code PgJsonTypeHandler, jdbcType=OTHER}（null 也走
     * {@code setNull(OTHER)}，与 Go 的 {@code types.JSON} nil → SQL NULL 一致）；
     * 可空时间列显式 {@code jdbcType=TIMESTAMP_WITH_TIMEZONE}（memory 模块同款先例）。</p>
     *
     * <p>WHERE 带 {@code deleted_at IS NULL}：GORM 的软删不只过滤 SELECT，
     * UpdateClauses 也给 UPDATE 加同款条件（gorm.io/gorm soft_delete.go
     * SoftDeleteUpdateClause），照抄。</p>
     *
     * <p><b>与 Go 的已知差异</b>：GORM Save 在 UPDATE 影响行数为 0 时会回退成 INSERT
     * （把整行再插回去）；service 层总是先查后存，HTTP 面不可达该分支，Java 未复刻
     * （已记入任务报告的已知差异）。</p>
     *
     * @return 影响行数（0 = 行不存在或已被软删——GORM 同样返回 0）
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
}
