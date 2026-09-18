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
 * {@code sync_logs} 的基础仓储（对照 Go {@code SyncLogRepository}，
 * internal/application/repository/datasource_repo.go L153-324）。
 *
 * <h2>与 {@link DataSourceMapper} 的三点不同</h2>
 * <ol>
 *   <li><b>没有软删除</b>：{@code sync_logs} 表里没有 {@code deleted_at}
 *       （Go 的 {@code SyncLog} 也没有 {@code gorm.DeletedAt}）。
 *       {@code CleanupOldLogs} 是**物理 DELETE**，且是全局的（不带租户条件）。
 *       别给它加 {@code deleted_at IS NULL}。</li>
 *   <li><b>只有一个 jsonb 列</b>{@code result}——每个返回实体的 {@code @Select}
 *       仍需重复声明 {@code @Results}（§9）。</li>
 *   <li><b>分页与排序全显式</b>：{@code started_at DESC} 两处；
 *       {@code FindLatest} 是 {@code ORDER BY started_at DESC, id ASC}
 *       （第二个来自 GORM {@code First} 追加的主键序，见类 {@code SyncLog} 的清单第 4 条）。</li>
 * </ol>
 */
@Mapper
public interface SyncLogMapper extends BaseMapper<SyncLog> {

    /**
     * 对照 {@code FindByID}：{@code Where(id).First(&log)}。
     *
     * @return 未命中回 {@code null}（Go 的 {@code ErrRecordNotFound}
     *         由仓储转成 {@code "sync log not found"}）
     */
    @Results({
            @Result(column = "result", property = "result", typeHandler = PgJsonTypeHandler.class),
    })
    @Select("SELECT * FROM sync_logs WHERE id = #{id} ORDER BY id LIMIT 1")
    SyncLog selectByIdOrNull(@Param("id") String id);

    /**
     * 对照 {@code FindByDataSource}：{@code Order("started_at DESC").Limit(limit).Offset(offset)}。
     *
     * <p>limit / offset 的钳制在仓储层（{@code limit <= 0 → 10}、{@code offset < 0 → 0}），
     * SQL 里不再判断。GORM 的 {@code Find} 让无行时返回非 nil 空切片。</p>
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
     * 对照 {@code FindLatest}：
     * {@code Where(data_source_id = ?).Order("started_at DESC").Limit(1).First(&log)}。
     *
     * <p>GORM 的 {@code First} 会把 {@code Limit(1)} 与 {@code ORDER BY started_at DESC}
     * 合起来，并**追加**主键序 → {@code ORDER BY started_at DESC, id ASC LIMIT 1}。
     * 追加的 {@code id} 只在 {@code started_at} 完全并列时破平局，照抄不亏。</p>
     *
     * @return 未命中回 {@code null}——**注意**：Go 在这里把
     *         {@code gorm.ErrRecordNotFound} 吞成了 {@code nil, nil}，
     *         与同文件其它读方法（上抛错误）不同，别统一。
     */
    @Results({
            @Result(column = "result", property = "result", typeHandler = PgJsonTypeHandler.class),
    })
    @Select("SELECT * FROM sync_logs WHERE data_source_id = #{dsId} "
            + "ORDER BY started_at DESC, id ASC LIMIT 1")
    SyncLog selectLatest(@Param("dsId") String dsId);

    /**
     * 对照 {@code HasRunningSync}：{@code Model(&SyncLog{}).Where(data_source_id = ?).
     * Where(status = 'running').Count(&count)}。
     *
     * <p>仓储把它折成 {@code count > 0}。</p>
     */
    @Select("SELECT COUNT(*) FROM sync_logs WHERE data_source_id = #{dsId} AND status = #{status}")
    long countByStatus(@Param("dsId") String dsId, @Param("status") String status);

    /**
     * 对照 {@code UpdateResult} 的 {@code Updates(map)}。
     *
     * <p>⚠️ Go 显式写了 {@code updated_at}，所以 GORM 的 map 分支**不会**再补一个
     * ——这里照抄同一列集，别漏 {@code updated_at}，也别多加。</p>
     *
     * <p>{@code result} 是 jsonb，必须在 SQL 里挂 typeHandler：
     * MyBatis-Plus 的 {@code UpdateWrapper.set()} 拿不到实体上的
     * {@code @TableField(typeHandler=…)}，会退化成 Java 序列化并落库成
     * {@code 0xACED…} 魔数（约定 §9）。</p>
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
     * 对照 {@code CancelPendingByDataSource}。
     *
     * <p>⚠️ Go 的 map 里只有三列，**没有** {@code updated_at}——GORM 的 map 分支
     * 会替它补上 {@code updated_at = NowFunc()}（§9「Go 源码没写、GORM 偷偷补
     * updated_at」的第四处）。所以线上写的是**四列**，Java 侧显式补上，
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
     * 对照 {@code CleanupOldLogs}：
     * {@code Where("started_at < NOW() - INTERVAL ? DAY", retentionDays).Delete(&types.SyncLog{})}。
     *
     * <p>PG 的 {@code NOW() - INTERVAL ? DAY} 在 H2 上不可移植（H2 的 {@code INTERVAL}
     * 语法不同），所以把界时刻**在 Java 侧算好**再传参——语义等价：
     * {@code NOW()} 是事务开始时刻，Java 取的是调用时刻。</p>
     *
     * <p>{@code SyncLog} 没有 {@code DeletedAt}，所以这是<b>物理删</b>，
     * 且是全局的（不按租户）。保留天数的钳制在仓储层。</p>
     */
    @Delete("DELETE FROM sync_logs WHERE started_at < #{cutoff}")
    int deleteStartedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
