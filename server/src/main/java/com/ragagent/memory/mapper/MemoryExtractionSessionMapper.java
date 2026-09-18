package com.ragagent.memory.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.memory.domain.MemoryExtractionSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code memory_extraction_sessions} 的仓储（对照 Go internal/application/repository/memory_extraction.go
 * 全部 230 行）。
 *
 * <h2>⚠️ 复合主键，没有任何 {@code selectById}/{@code updateById} 路径</h2>
 * <p>主键是 {@code (tenant_id, subject_id, session_id)}。实体上只把
 * {@code sessionId} 标成 {@code @TableId}（语义上最接近业务键），
 * 但这个接口里**一条 MP 的按主键方法都不用**——全部是带完整 scope 的显式 SQL。</p>
 *
 * <h2>⚠️ 列名与 {@code MemoryMessageCursor} 的对应</h2>
 * <p>Go 的 {@code gorm:"embedded;embeddedPrefix:cursor_"} 展开成 {@code cursor_at}/{@code cursor_id}
 * 两列，{@code failed_from_} / {@code failed_to_} 同理。实体里是平列字段，
 * JSON 里才是嵌套对象——所以这里的 SQL 一律用平列名。</p>
 *
 * <h2>⚠️ {@code pending} 是 H2 的保留字吗</h2>
 * <p>不是（H2 与 PG 都接受裸的 {@code pending} 作为列名），DDL 与 Go 的迁移一致。</p>
 *
 * <p><b>裸 {@code @Select} 显式写 {@code @Results}</b>：MyBatis 的隐式驼峰映射
 * 由全局配置决定，项目里既有裸 SQL 的投影行都显式声明（§9）。</p>
 */
@Mapper
public interface MemoryExtractionSessionMapper extends BaseMapper<MemoryExtractionSession> {

    /** {@code MemoryExtractionSession} 的列清单（含平铺的游标列），供三条裸查询共用。 */
    String COLUMNS = "tenant_id, subject_id, session_id, revision, cursor_at, cursor_id, pending, "
            + "failure_count, failure_code, failed_from_at, failed_from_id, failed_to_at, failed_to_id, "
            + "failed_at, updated_at";

    /**
     * 对照 {@code enqueueExtractionSession(id, bump=false)}：{@code DoNothing}，
     * 即"已经推进过的行不许被重复入队重新激活"。
     *
     * <p>插入的 {@code revision} 恒为 **1**、{@code pending} 恒为 **true**、
     * {@code cursor_at} 从主体的 {@code extract_cursor} 继承（升级边界，冻结不再推进）。</p>
     *
     * @return 1 = 真的插入了；0 = 已存在
     */
    @Insert("INSERT INTO memory_extraction_sessions "
            + "(tenant_id, subject_id, session_id, revision, cursor_at, cursor_id, pending, "
            + " failure_count, failure_code, failed_from_at, failed_from_id, failed_to_at, failed_to_id, "
            + " updated_at) "
            + "VALUES (#{r.tenantId}, #{r.subjectId}, #{r.sessionId}, #{r.revision}, #{r.cursorAt}, "
            + "        #{r.cursorId}, #{r.pending}, #{r.failureCount}, #{r.failureCode}, "
            + "        #{r.failedFromAt}, #{r.failedFromId}, #{r.failedToAt}, #{r.failedToId}, "
            + "        #{r.updatedAt}) "
            + "ON CONFLICT (tenant_id, subject_id, session_id) DO NOTHING")
    int insertIfAbsentPostgres(@Param("r") MemoryExtractionSession row);

    /** H2 没有 {@code ON CONFLICT}——等价的条件插入。 */
    @Insert("INSERT INTO memory_extraction_sessions "
            + "(tenant_id, subject_id, session_id, revision, cursor_at, cursor_id, pending, "
            + " failure_count, failure_code, failed_from_at, failed_from_id, failed_to_at, failed_to_id, "
            + " updated_at) "
            + "SELECT #{r.tenantId}, #{r.subjectId}, #{r.sessionId}, #{r.revision}, #{r.cursorAt}, "
            + "       #{r.cursorId}, #{r.pending}, #{r.failureCount}, #{r.failureCode}, "
            + "       #{r.failedFromAt}, #{r.failedFromId}, #{r.failedToAt}, #{r.failedToId}, "
            + "       #{r.updatedAt} "
            + "WHERE NOT EXISTS (SELECT 1 FROM memory_extraction_sessions "
            + "  WHERE tenant_id = #{r.tenantId} AND subject_id = #{r.subjectId} "
            + "  AND session_id = #{r.sessionId})")
    int insertIfAbsentOther(@Param("r") MemoryExtractionSession row);

    /**
     * 对照 {@code enqueueExtractionSession(id, bump=true)}：
     * {@code DoUpdates: revision = revision + 1, pending = true, updated_at = now}。
     *
     * <p>表名限定 {@code memory_extraction_sessions.revision}——PG 的
     * {@code ON CONFLICT DO UPDATE} 右侧不加限定会歧义。</p>
     */
    @Insert("INSERT INTO memory_extraction_sessions "
            + "(tenant_id, subject_id, session_id, revision, cursor_at, cursor_id, pending, "
            + " failure_count, failure_code, failed_from_at, failed_from_id, failed_to_at, failed_to_id, "
            + " updated_at) "
            + "VALUES (#{r.tenantId}, #{r.subjectId}, #{r.sessionId}, #{r.revision}, #{r.cursorAt}, "
            + "        #{r.cursorId}, #{r.pending}, #{r.failureCount}, #{r.failureCode}, "
            + "        #{r.failedFromAt}, #{r.failedFromId}, #{r.failedToAt}, #{r.failedToId}, "
            + "        #{r.updatedAt}) "
            + "ON CONFLICT (tenant_id, subject_id, session_id) DO UPDATE SET "
            + "revision = memory_extraction_sessions.revision + 1, pending = TRUE, "
            + "updated_at = #{r.updatedAt}")
    int upsertBumpPostgres(@Param("r") MemoryExtractionSession row);

    /** H2：先尝试自增，没命中再插入（等价于 {@code ON CONFLICT DO UPDATE} 的净效果）。 */
    @Update("UPDATE memory_extraction_sessions SET revision = revision + 1, pending = TRUE, "
            + "updated_at = #{r.updatedAt} "
            + "WHERE tenant_id = #{r.tenantId} AND subject_id = #{r.subjectId} AND session_id = #{r.sessionId}")
    int bumpExisting(@Param("r") MemoryExtractionSession row);

    /**
     * 对照 {@code extractionRows(tx, scope).Where("pending = ?", true)
     * .Order("updated_at ASC, session_id ASC").Limit(MaxMemoryPendingSessions).Find()}.
     *
     * <p><b>{@code updated_at ASC}</b> 是关键：它让"最早没推完的会话"先被处理，
     * 而不是让一个刚来的会话插队。</p>
     */
    @Select("SELECT " + COLUMNS + " FROM memory_extraction_sessions "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND pending = TRUE "
            + "ORDER BY updated_at ASC, session_id ASC LIMIT #{limit}")
    @Results({
            @Result(column = "tenant_id", property = "tenantId", id = true),
            @Result(column = "subject_id", property = "subjectId", id = true),
            @Result(column = "session_id", property = "sessionId", id = true),
            @Result(column = "revision", property = "revision"),
            @Result(column = "cursor_at", property = "cursorAt"),
            @Result(column = "cursor_id", property = "cursorId"),
            @Result(column = "pending", property = "pending"),
            @Result(column = "failure_count", property = "failureCount"),
            @Result(column = "failure_code", property = "failureCode"),
            @Result(column = "failed_from_at", property = "failedFromAt"),
            @Result(column = "failed_from_id", property = "failedFromId"),
            @Result(column = "failed_to_at", property = "failedToAt"),
            @Result(column = "failed_to_id", property = "failedToId"),
            @Result(column = "failed_at", property = "failedAt"),
            @Result(column = "updated_at", property = "updatedAt"),
    })
    List<MemoryExtractionSession> listPending(@Param("tenantId") long tenantId,
                                              @Param("subjectId") String subjectId,
                                              @Param("limit") int limit);

    /**
     * 对照 {@code hasPendingExtraction}：{@code Select("session_id").Where("pending = ?", true).Limit(1)}。
     *
     * <p>Go 判断的是"拿到的行数 > 0"，所以返回 {@code COUNT} 即可
     * （上限 1 行，语义等价且不用拉回一行实体）。</p>
     */
    @Select("SELECT COUNT(*) FROM memory_extraction_sessions "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND pending = TRUE")
    long countPendingProbe(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId);

    /** 对照 {@code CheckpointExtraction} 与 {@code RecordExtractionFailure} 里按 session_id 取行。 */
    @Select("SELECT " + COLUMNS + " FROM memory_extraction_sessions "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND session_id = #{sessionId}")
    @Results({
            @Result(column = "tenant_id", property = "tenantId", id = true),
            @Result(column = "subject_id", property = "subjectId", id = true),
            @Result(column = "session_id", property = "sessionId", id = true),
            @Result(column = "revision", property = "revision"),
            @Result(column = "cursor_at", property = "cursorAt"),
            @Result(column = "cursor_id", property = "cursorId"),
            @Result(column = "pending", property = "pending"),
            @Result(column = "failure_count", property = "failureCount"),
            @Result(column = "failure_code", property = "failureCode"),
            @Result(column = "failed_from_at", property = "failedFromAt"),
            @Result(column = "failed_from_id", property = "failedFromId"),
            @Result(column = "failed_to_at", property = "failedToAt"),
            @Result(column = "failed_to_id", property = "failedToId"),
            @Result(column = "failed_at", property = "failedAt"),
            @Result(column = "updated_at", property = "updatedAt"),
    })
    MemoryExtractionSession selectBySessionId(@Param("tenantId") long tenantId,
                                              @Param("subjectId") String subjectId,
                                              @Param("sessionId") String sessionId);

    /**
     * 对照 {@code CheckpointExtraction}：只更新这一行的游标与 pending，
     * "转动未完成的工作而不重写主体的整个历史"。
     *
     * <p>{@code failure_count}/{@code failure_code} 只在 {@code FailedAt == nil} 时才被重置
     * ——由调用方决定是否传这两个参数。</p>
     */
    @Update("<script>"
            + "UPDATE memory_extraction_sessions SET cursor_at = #{cursorAt}, cursor_id = #{cursorId}, "
            + "pending = #{pending}, updated_at = #{now}"
            + "<if test='resetFailures'>"
            + "  , failure_count = 0, failure_code = ''"
            + "</if>"
            + " WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND session_id = #{sessionId}"
            + "</script>")
    int checkpoint(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                   @Param("sessionId") String sessionId,
                   @Param("cursorAt") OffsetDateTime cursorAt, @Param("cursorId") String cursorId,
                   @Param("pending") boolean pending, @Param("resetFailures") boolean resetFailures,
                   @Param("now") OffsetDateTime now);

    /**
     * 对照 {@code RecordExtractionFailure} 的那一条 UPDATE。
     *
     * <p><b>{@code failed_at} 是条件写的</b>：只有"重试预算耗尽"（{@code skip=true}）时
     * 才落时间戳，否则写 SQL NULL——Go 的 map 里 {@code "failed_at": nil} 与
     * {@code "failed_at": time.Now()} 正是这两支。</p>
     */
    @Update("UPDATE memory_extraction_sessions SET failure_count = #{failureCount}, "
            + "failure_code = #{failureCode}, updated_at = #{now}, "
            + "failed_at = #{failedAt,jdbcType=TIMESTAMP_WITH_TIMEZONE}, "
            + "failed_from_at = #{failedFromAt}, failed_from_id = #{failedFromId}, "
            + "failed_to_at = #{failedToAt}, failed_to_id = #{failedToId} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} AND session_id = #{sessionId}")
    int recordFailure(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                      @Param("sessionId") String sessionId,
                      @Param("failureCount") int failureCount, @Param("failureCode") String failureCode,
                      @Param("failedAt") OffsetDateTime failedAt,
                      @Param("failedFromAt") OffsetDateTime failedFromAt,
                      @Param("failedFromId") String failedFromId,
                      @Param("failedToAt") OffsetDateTime failedToAt,
                      @Param("failedToId") String failedToId,
                      @Param("now") OffsetDateTime now);
}
