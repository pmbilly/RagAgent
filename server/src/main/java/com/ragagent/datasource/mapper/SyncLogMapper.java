package com.ragagent.datasource.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.datasource.domain.SyncLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code sync_logs} 的基础仓储。
 *
 * <h2>与 {@link DataSourceMapper} 的三点不同</h2>
 * <ol>
 *   <li><b>没有软删除</b>：{@code sync_logs} 表里没有 {@code deleted_at}。
 *       {@code CleanupOldLogs} 是**物理 DELETE**，且是全局的（不带租户条件）。
 *       别给它加 {@code deleted_at IS NULL}。</li>
 *   <li><b>只有一个 jsonb 列</b>{@code result}——每个返回实体的 {@code @Select}
 *       仍需重复声明 {@code @Results}（自定义 SQL 的结果映射不会自动套
 *       实体上的 typeHandler）。</li>
 *   <li><b>分页与排序全显式</b>：{@code started_at DESC} 两处；
 *       {@code FindLatest} 是 {@code ORDER BY started_at DESC, id ASC}
 *       （用 {@code id} 破平局，见类 {@code SyncLog} 的清单第 4 条）。</li>
 * </ol>
 */
@Mapper
public interface SyncLogMapper extends BaseMapper<SyncLog> {

    /**
     * 按 id 取单条同步日志。
     *
     * @return 未命中回 {@code null}（仓储层转成 {@code "sync log not found"}）
     */
    @Results({
            @Result(column = "result", property = "result", typeHandler = PgJsonTypeHandler.class),
    })
    @Select("SELECT * FROM sync_logs WHERE id = #{id} ORDER BY id LIMIT 1")
    SyncLog selectByIdOrNull(@Param("id") String id);

    /**
     * 某数据源的同步日志分页，{@code started_at DESC}。
     *
     * <p>limit / offset 的钳制在仓储层（{@code limit <= 0 → 10}、{@code offset < 0 → 0}），
     * SQL 里不再判断。无行时返回空列表。</p>
     */
    @Results({
            @Result(column = "result", property = "result", typeHandler = PgJsonTypeHandler.class),
    })
    @Select("SELECT * FROM sync_logs WHERE data_source_id = #{dsId} "
            + "ORDER BY started_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<SyncLog> selectByDataSource(@Param("dsId") String dsId,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    /**
     * 取某数据源最近一条同步日志：
     * {@code ORDER BY started_at DESC, id ASC LIMIT 1}——
     * {@code id} 只在 {@code started_at} 完全并列时破平局。
     *
     * @return 未命中回 {@code null}——**注意**：这里是"未命中不算错"，
     *         与同文件其它读方法（未命中上抛）不同，别统一。
     */
    @Results({
            @Result(column = "result", property = "result", typeHandler = PgJsonTypeHandler.class),
    })
    @Select("SELECT * FROM sync_logs WHERE data_source_id = #{dsId} "
            + "ORDER BY started_at DESC, id ASC LIMIT 1")
    SyncLog selectLatest(@Param("dsId") String dsId);

    /**
     * 统计某数据源处于给定状态的日志条数。
     *
     * <p>仓储把它折成 {@code count > 0}。</p>
     */
    @Select("SELECT COUNT(*) FROM sync_logs WHERE data_source_id = #{dsId} AND status = #{status}")
    long countByStatus(@Param("dsId") String dsId, @Param("status") String status);

    /**
     * 同步结束后回写结果列。
     *
     * <p>⚠️ 列集里**必须**含 {@code updated_at}，也别多加——这是既定契约。</p>
     *
     * <p>{@code result} 是 jsonb，必须在 SQL 里挂 typeHandler：
     * MyBatis-Plus 的 {@code UpdateWrapper.set()} 拿不到实体上的
     * {@code @TableField(typeHandler=…)}，会退化成 Java 序列化并落库成
     * {@code 0xACED…} 魔数。</p>
     */
    @Update("UPDATE sync_logs SET status = #{log.status}, finished_at = #{log.finishedAt}, "
            + "items_total = #{log.itemsTotal}, items_created = #{log.itemsCreated}, "
            + "items_updated = #{log.itemsUpdated}, items_deleted = #{log.itemsDeleted}, "
            + "items_skipped = #{log.itemsSkipped}, items_failed = #{log.itemsFailed}, "
            + "error_message = #{log.errorMessage}, "
            + "result = #{log.result, typeHandler=com.ragagent.common.web.PgJsonTypeHandler}, "
            + "updated_at = #{now} "
            + "WHERE id = #{log.id}")
    int updateResult(@Param("log") SyncLog log, @Param("now") OffsetDateTime now);

    /**
     * 作废某数据源的全部在途日志。
     *
     * <p>⚠️ 列集是**四列**：除三列业务值外**必须**补 {@code updated_at = now}，
     * 且沿用同一个 {@code now}（与 {@code finished_at} 同值）。</p>
     */
    @Update("UPDATE sync_logs SET status = #{status}, finished_at = #{now}, "
            + "error_message = #{errorMessage}, updated_at = #{now} "
            + "WHERE data_source_id = #{dsId} AND status IN (#{running}, #{pending})")
    int cancelPending(@Param("dsId") String dsId,
                      @Param("status") String status,
                      @Param("running") String running,
                      @Param("pending") String pending,
                      @Param("errorMessage") String errorMessage,
                      @Param("now") OffsetDateTime now);

    /**
     * 物理删除早于保留期的同步日志。
     *
     * <p>PG 的 {@code NOW() - INTERVAL ? DAY} 写法 H2 不支持（H2 的 {@code INTERVAL}
     * 语法不同），所以把界时刻**在 Java 侧算好**再传参——语义等价：
     * {@code NOW()} 是事务开始时刻，Java 取的是调用时刻。</p>
     *
     * <p>{@code SyncLog} 没有 {@code deleted_at}，所以这是<b>物理删</b>，
     * 且是全局的（不按租户）。保留天数的钳制在仓储层。</p>
     */
    @Delete("DELETE FROM sync_logs WHERE started_at < #{cutoff}")
    int deleteStartedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
