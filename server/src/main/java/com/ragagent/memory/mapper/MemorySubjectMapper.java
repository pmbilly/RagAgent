package com.ragagent.memory.mapper;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.memory.domain.MemoryExtractionState;
import com.ragagent.memory.domain.MemorySubject;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code memory_subjects} 的基础仓储（对照 Go
 * internal/application/repository/memory.go 里所有 {@code Model(&types.MemorySubject{})} 的写路径）。
 *
 * <p>只有六条语句，但每一条都有必须显式写的理由（见约定 §3 与 §9）：</p>
 * <ul>
 *   <li><b>两个 jsonb 列</b>（{@code extraction_state} / {@code pending_sessions}）在
 *       {@code @Update} 里也必须带 {@code typeHandler}：MyBatis-Plus 的
 *       {@code UpdateWrapper.set()} 不套实体上的 {@code @TableField(typeHandler=…)}，
 *       会退化成 Java 序列化（落库时报 {@code 0xACED} 魔数，§9）。所以这里**不用**
 *       给 wrapper 传无名参数，而是把 SQL 写在注解里、参数带类型处理器。</li>
 *   <li><b>{@code ON CONFLICT DO NOTHING} 的方言分叉</b>：H2 不支持该子句，
 *       所以与 {@code MessageSuggestionMapper} 同款处理——PG 一条、H2 一条
 *       （H2 那条用条件插入，语义相同、只是测试环境下的原子性弱一些）。</li>
 *   <li><b>{@code extract_scheduled_at} 必须能写 NULL</b>：Go 的
 *       {@code Updates(map)} 对 {@code *time.Time} 为 nil 时写 SQL NULL。
 *       这里靠显式 {@code jdbcType} 让 H2 / PG 都接受 NULL。</li>
 * </ul>
 */
@Mapper
public interface MemorySubjectMapper extends BaseMapper<MemorySubject> {

    /**
     * 对照 {@code GetSubject}：{@code First} 未命中即"没有"，由仓储回 {@code null}。
     *
     * <p>⚠️ 自定义 {@code @Select} 的结果映射**不会**自动套实体上的
     * {@code @TableField(typeHandler=…)}（约定 §9）——那两个 jsonb 列必须在方法上
     * 再声明一次，否则 {@code extraction_state} / {@code pending_sessions} 会被
     * 当成裸字符串塞进对象，读回来是 {@code null} 或直接抛异常。
     * 这个坑只在"库里明明有值、读出来却是 null"时才暴露。</p>
     *
     * <p>其余列交给 MyBatis 的自动映射（{@code mapUnderscoreToCamelCase} 已开）。</p>
     */
    @Results({
            @Result(column = "extraction_state", property = "extractionState",
                    typeHandler = com.ragagent.memory.domain.MemoryExtractionStateTypeHandler.class),
            @Result(column = "pending_sessions", property = "pendingSessions",
                    typeHandler = com.ragagent.memory.domain.MemoryStringListTypeHandler.class),
    })
    @Select("SELECT * FROM memory_subjects WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    MemorySubject selectByScope(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId);

    /**
     * 对照 Go {@code withSubject} 里的 {@code Clauses(forUpdateClause()).First(&subject)}
     * ——{@code SELECT … FOR UPDATE}，把这一行锁到事务结束。
     *
     * <p>抽取租约的整套 CAS 语义都建立在它上面：没有行锁，两个 worker 会同时通过
     * "租约已过期"的判定然后各起一批。</p>
     *
     * <p>结果映射与 {@link #selectByScope} 同理，两个 jsonb 列要显式挂类型处理器。</p>
     */
    @Results({
            @Result(column = "extraction_state", property = "extractionState",
                    typeHandler = com.ragagent.memory.domain.MemoryExtractionStateTypeHandler.class),
            @Result(column = "pending_sessions", property = "pendingSessions",
                    typeHandler = com.ragagent.memory.domain.MemoryStringListTypeHandler.class),
    })
    @Select("SELECT * FROM memory_subjects WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId} FOR UPDATE")
    MemorySubject selectByScopeForUpdate(@Param("tenantId") long tenantId,
                                         @Param("subjectId") String subjectId);

    /**
     * 对照 {@code EnsureSubject} 的 {@code Clauses(OnConflict{DoNothing}).Create(subject)}。
     *
     * <p>唯一约束是 {@code idx_memory_subjects_scope (tenant_id, subject_id)}。</p>
     *
     * @return 1 = 真的插入了；0 = 已存在（唯一键冲突被吞掉）
     */
    @Insert("INSERT INTO memory_subjects "
            + "(id, tenant_id, subject_id, enabled, block_text, item_count, "
            + " extraction_state, pending_sessions, created_at, updated_at) "
            + "VALUES (#{s.id}, #{s.tenantId}, #{s.subjectId}, #{s.enabled}, #{s.blockText}, #{s.itemCount}, "
            + "        #{s.extractionState, typeHandler=com.ragagent.memory.domain.MemoryExtractionStateTypeHandler, "
            + "         jdbcType=OTHER}, "
            + "        #{s.pendingSessions, typeHandler=com.ragagent.memory.domain.MemoryStringListTypeHandler, "
            + "         jdbcType=OTHER}, "
            + "        #{s.createdAt}, #{s.updatedAt}) "
            + "ON CONFLICT (tenant_id, subject_id) DO NOTHING")
    int insertIfAbsentPostgres(@Param("s") MemorySubject subject);

    /** H2 没有 {@code ON CONFLICT}——等价的条件插入（见类注释）。 */
    @Insert("INSERT INTO memory_subjects "
            + "(id, tenant_id, subject_id, enabled, block_text, item_count, "
            + " extraction_state, pending_sessions, created_at, updated_at) "
            + "SELECT #{s.id}, #{s.tenantId}, #{s.subjectId}, #{s.enabled}, #{s.blockText}, #{s.itemCount}, "
            + "       #{s.extractionState, typeHandler=com.ragagent.memory.domain.MemoryExtractionStateTypeHandler, "
            + "        jdbcType=OTHER}, "
            + "       #{s.pendingSessions, typeHandler=com.ragagent.memory.domain.MemoryStringListTypeHandler, "
            + "        jdbcType=OTHER}, "
            + "       #{s.createdAt}, #{s.updatedAt} "
            + "WHERE NOT EXISTS (SELECT 1 FROM memory_subjects "
            + "  WHERE tenant_id = #{s.tenantId} AND subject_id = #{s.subjectId})")
    int insertIfAbsentOther(@Param("s") MemorySubject subject);

    /**
     * 对照 {@code UpdateSubjectEnabled}：Go 的 map 里只有
     * {@code enabled} 与 {@code updated_at} 两列（**不碰** block/item_count）。
     */
    @Update("UPDATE memory_subjects SET enabled = #{enabled}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int updateEnabled(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                      @Param("enabled") boolean enabled, @Param("now") OffsetDateTime now);

    /** 对照 {@code UpdateSubjectBlock}：写渲染好的常驻块与条目数。 */
    @Update("UPDATE memory_subjects SET block_text = #{block}, block_updated_at = #{now}, "
            + "item_count = #{itemCount}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int updateBlock(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                    @Param("block") String block, @Param("itemCount") int itemCount,
                    @Param("now") OffsetDateTime now);

    /**
     * 对照 Go 的 {@code saveExtractionState}（memory_extraction.go L24-29）——抽取状态的
     * 唯一写入口。
     *
     * <p>Go 用 map 更新四列，其中两个是 jsonb。Java 侧**必须**把类型处理器写在 SQL 里；
     * 用 wrapper 的 {@code .set()} 会退化成 Java 序列化（§9 的 0xACED 坑）。</p>
     *
     * <p>{@code pending_sessions} 与 {@code extraction_state} 都**从不**为 NULL
     * （Go 的 {@code Value()} 永远返回一段 JSON），{@code extract_scheduled_at} 则可以是 NULL。</p>
     */
    @Update("UPDATE memory_subjects SET "
            + "extraction_state = #{state, typeHandler=com.ragagent.memory.domain.MemoryExtractionStateTypeHandler, "
            + "jdbcType=OTHER}, "
            + "pending_sessions = #{pending, typeHandler=com.ragagent.memory.domain.MemoryStringListTypeHandler, "
            + "jdbcType=OTHER}, "
            + "extract_scheduled_at = #{scheduledAt,jdbcType=TIMESTAMP_WITH_TIMEZONE}, "
            + "updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int saveExtractionState(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                            @Param("state") MemoryExtractionState state,
                            @Param("pending") java.util.List<String> pending,
                            @Param("scheduledAt") OffsetDateTime scheduledAt,
                            @Param("now") OffsetDateTime now);

    /**
     * 对照 Go 的 {@code tx.Model(subject).Update("last_extracted_at", time.Now())}。
     *
     * <p>⚠️ Go 只写了一列，但 {@code Update(column, value)} 内部是
     * {@code map[string]interface{}{column: value}}，GORM 的 {@code ConvertToAssignments}
     * 会对缺 {@code updated_at} 的 map 自动补 {@code updated_at = now}——
     * 所以线上写的是**两列**。本模块共三处这类"GORM 偷偷补"的地方，
     * 另两处在 {@code MemoryItemMapper.touchUsed} 与 {@code supersedePendingReplacements}。</p>
     */
    @Update("UPDATE memory_subjects SET last_extracted_at = #{now}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int updateLastExtractedAt(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                              @Param("now") OffsetDateTime now);

    /** 对照 {@code MarkConsolidated}。 */
    @Update("UPDATE memory_subjects SET consolidated_at = #{now}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int markConsolidated(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                         @Param("now") OffsetDateTime now);

    /** 对照 {@code MarkForcedConsolidated}：**另一只钟**，与 {@code consolidated_at} 互不影响。 */
    @Update("UPDATE memory_subjects SET forced_consolidated_at = #{now}, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND subject_id = #{subjectId}")
    int markForcedConsolidated(@Param("tenantId") long tenantId, @Param("subjectId") String subjectId,
                               @Param("now") OffsetDateTime now);
}
