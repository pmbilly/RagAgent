package com.ragagent.memory.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.memory.domain.MemoryTombstone;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code memory_tombstones} 的仓储。
 *
 * <p>唯一约束 {@code idx_mem_tomb_fp (tenant_id, subject_id, fingerprint)} 是
 * {@code AddTombstone} 的 ON CONFLICT 靶子——它保证"同一句话被忘两次"不会插出两行。</p>
 */
@Mapper
public interface MemoryTombstoneMapper extends BaseMapper<MemoryTombstone> {

    /**
     * 对照 {@code AddTombstone}：冲突时什么都不做。
     *
     * <p>{@code created_at} 语义上是自动时间戳（插入时写 {@code now}）；
     * 而这里**必须显式给 {@code created_at}**，否则 H2 会落到 DDL 的
     * {@code DEFAULT CURRENT_TIMESTAMP}（values 一致，但 Java 侧不依赖 DB 默认值，
     * 免得 PG/H2 出现毫秒差）。</p>
     *
     * @return 1 = 真的插入了；0 = 已存在（唯一键冲突被吞掉）
     */
    @Insert("INSERT INTO memory_tombstones "
            + "(id, tenant_id, subject_id, topic, fingerprint, source_message_id, created_at) "
            + "VALUES (#{t.id}, #{t.tenantId}, #{t.subjectId}, #{t.topic}, #{t.fingerprint}, "
            + "        #{t.sourceMessageId}, #{t.createdAt}) "
            + "ON CONFLICT (tenant_id, subject_id, fingerprint) DO NOTHING")
    int insertIfAbsentPostgres(@Param("t") MemoryTombstone tombstone);

    /** H2 没有 {@code ON CONFLICT}——等价的条件插入。 */
    @Insert("INSERT INTO memory_tombstones "
            + "(id, tenant_id, subject_id, topic, fingerprint, source_message_id, created_at) "
            + "SELECT #{t.id}, #{t.tenantId}, #{t.subjectId}, #{t.topic}, #{t.fingerprint}, "
            + "       #{t.sourceMessageId}, #{t.createdAt} "
            + "WHERE NOT EXISTS (SELECT 1 FROM memory_tombstones "
            + "  WHERE tenant_id = #{t.tenantId} AND subject_id = #{t.subjectId} "
            + "  AND fingerprint = #{t.fingerprint})")
    int insertIfAbsentOther(@Param("t") MemoryTombstone tombstone);

    /** 对照 {@code trimTombstones} 的 {@code Pluck("id", &keep)}：保留最近的 N 条。 */
    @Select("SELECT id FROM memory_tombstones WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "ORDER BY created_at DESC LIMIT #{limit}")
    List<String> selectNewestIds(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                                 @Param("limit") int limit);

    /** 对照 {@code trimTombstones} 的删除（{@code id NOT IN ?}）。 */
    @Delete("<script>"
            + "DELETE FROM memory_tombstones WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "AND id NOT IN <foreach collection='keep' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    int deleteExcept(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                     @Param("keep") List<String> keep);

    /** 对照 {@code ListTombstones}：最近的拒绝，{@code created_at DESC}。 */
    @Select("<script>"
            + "SELECT * FROM memory_tombstones WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} "
            + "ORDER BY created_at DESC"
            + "<if test='limit &gt; 0'> LIMIT #{limit}</if>"
            + "</script>")
    List<MemoryTombstone> listTombstones(@Param("tenantId") long tenantId,
                                         @Param("subjectId") String subjectId,
                                         @Param("limit") int limit);

    /** 对照 {@code HasTombstone}：这个指纹是否已经被忘过。 */
    @Select("SELECT COUNT(*) FROM memory_tombstones WHERE tenant_id = #{tenantId} "
            + "AND subject_id = #{subjectId} AND fingerprint = #{fingerprint}")
    long countByFingerprint(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                            @Param("fingerprint") String fingerprint);

    /**
     * 对照 {@code HasTombstoneForMessage}：{@code within > 0} 时才加时间窗。
     *
     * <p>窗口是有意义的：这条规则是为了拦住"一个 debounce 之后的重推"，
     * 不是把一条消息永久封禁。</p>
     */
    @Select("<script>"
            + "SELECT COUNT(*) FROM memory_tombstones WHERE tenant_id = #{tenantId} "
            + "AND subject_id = #{subjectId} AND source_message_id = #{sourceMessageId}"
            + "<if test='sinceCutoff != null'> AND created_at &gt; #{sinceCutoff}</if>"
            + "</script>")
    long countBySourceMessage(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                              @Param("sourceMessageId") String sourceMessageId,
                              @Param("sinceCutoff") OffsetDateTime sinceCutoff);
}
