package com.ragagent.datasource.mapper;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.datasource.domain.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code data_sources} 的基础仓储（对照 Go {@code DataSourceRepository}，
 * internal/application/repository/datasource_repo.go L14-151）。
 *
 * <h2>三条必须显式写的理由</h2>
 * <ol>
 *   <li><b>⚠️ 自定义 {@code @Select} 的结果映射**不会**自动套实体上的
 *       {@code @TableField(typeHandler=…)}</b>（约定 §9，波 0 的 memory 模块又踩过一次）。
 *       {@code data_sources} 有**三个** jsonb 列，每个读方法都必须重复声明
 *       {@code @Results}——漏了的表现是"库里明明有值、读出来恒为 null"，
 *       H2 与 PG 都会中。</li>
 *   <li><b>软删除 {@code deleted_at IS NULL} 必须显式写</b>：约定 §8 明确
 *       <b>不用</b> {@code @TableLogic}（datetime 逻辑删除值在 MP 各版本行为敏感），
 *       所以每个读/写都手写这个条件。漏了就会读出让用户"删掉又复活"的行。</li>
 *   <li><b>排序全部显式</b>（GORM 的隐式排序为零）：本 Mapper 里两处
 *       {@code created_at DESC}，逐条对照 Go 的 {@code Order(...)}。</li>
 * </ol>
 *
 * <h2>jsonb 列不需要 {@code FieldStrategy.ALWAYS}</h2>
 * <p>三个 jsonb 列在迁移 000029 里**都没有 DEFAULT**——MyBatis-Plus 对 null 字段省略该列
 * 恰好落到 SQL NULL，与 Go 的 {@code JSON.Value()} 对空值回 {@code nil, nil} 一致。
 * （§9 那条"带 DEFAULT 的 jsonb 列必须 ALWAYS"只针对 wiki {@code page_metadata} 那类。）</p>
 */
@Mapper
public interface DataSourceMapper extends BaseMapper<DataSource> {

    /**
     * 对照 {@code FindByID}：{@code Where(id).Where(deleted_at IS NULL).First(&ds)}。
     *
     * <p>{@code First} 在无显式 Order 时补主键序 → {@code ORDER BY id LIMIT 1}
     * （主键唯一，等于无排序）。刻意写成 {@code LIMIT 1} 而不是依赖结果集大小，
     * 与 Go 逐行对应。</p>
     *
     * @return 未命中回 {@code null}（Go 的 {@code gorm.ErrRecordNotFound}
     *         由仓储转成 {@code "data source not found"}）
     */
    @Results({
            @Result(column = "config", property = "config", typeHandler = PgJsonTypeHandler.class),
            @Result(column = "last_sync_cursor", property = "lastSyncCursor",
                    typeHandler = PgJsonTypeHandler.class),
            @Result(column = "last_sync_result", property = "lastSyncResult",
                    typeHandler = PgJsonTypeHandler.class),
    })
    @Select("SELECT * FROM data_sources WHERE id = #{id} AND deleted_at IS NULL ORDER BY id LIMIT 1")
    DataSource selectByIdOrNull(@Param("id") String id);

    /**
     * 对照 {@code FindByKnowledgeBase}：{@code Where(knowledge_base_id = ?).
     * Where(deleted_at IS NULL).Order("created_at DESC").Find(&dataSources)}。
     *
     * <p>⚠️ GORM 的 {@code Find} 会把 nil 切片初始化成**非 nil 空切片**，
     * 所以无行时 Go 返回的是 {@code []} 而不是 {@code nil}；Java 侧同样返回空 {@code List}
     * 而不是 {@code null}（否则 service 层的 {@code len(list)} 判定会分叉）。</p>
     */
    @Results({
            @Result(column = "config", property = "config", typeHandler = PgJsonTypeHandler.class),
            @Result(column = "last_sync_cursor", property = "lastSyncCursor",
                    typeHandler = PgJsonTypeHandler.class),
            @Result(column = "last_sync_result", property = "lastSyncResult",
                    typeHandler = PgJsonTypeHandler.class),
    })
    @Select("SELECT * FROM data_sources WHERE knowledge_base_id = #{kbId} AND deleted_at IS NULL "
            + "ORDER BY created_at DESC")
    java.util.List<DataSource> selectByKnowledgeBase(@Param("kbId") String kbId);

    /**
     * 对照 {@code FindActive}（供调度器使用）：
     * {@code status = 'active' AND deleted_at IS NULL AND sync_schedule != ''}
     * ，{@code ORDER BY created_at DESC}。
     *
     * <p>三个条件缺一不可：最后那个滤掉"没有 cron 表达式"的行——否则调度器会
     * 每小时唤醒一批永远不该被调度的数据源。</p>
     */
    @Results({
            @Result(column = "config", property = "config", typeHandler = PgJsonTypeHandler.class),
            @Result(column = "last_sync_cursor", property = "lastSyncCursor",
                    typeHandler = PgJsonTypeHandler.class),
            @Result(column = "last_sync_result", property = "lastSyncResult",
                    typeHandler = PgJsonTypeHandler.class),
    })
    @Select("SELECT * FROM data_sources WHERE status = #{status} AND deleted_at IS NULL "
            + "AND sync_schedule <> '' ORDER BY created_at DESC")
    java.util.List<DataSource> selectActive(@Param("status") String status);

    /**
     * 对照 Go 的 {@code tx.Model(&types.DataSource{}).Where("id = ?", ds.ID).
     * UpdateColumn("sync_deletions", syncDeletions)}。
     *
     * <p>这是 {@code Create} / {@code Update} 里"把用户选的 {@code false} 从 GORM 的
     * 默认值替换下救回来"的那一步。{@code UpdateColumn} 是单列表更新，
     * 且 <b>不带 hooks、不自动刷 {@code updated_at}</b>（GORM 的 {@code ConvertToAssignments}
     * 在 map 分支里对 {@code stmt.SkipHooks} 有判断）——所以这里也不动它。</p>
     *
     * <p>软删除条件同样由 GORM 自动补（{@code SoftDeleteDeleteClause} 走的是
     * {@code SoftDeleteQueryClause}），故这里显式写 {@code deleted_at IS NULL}。</p>
     */
    @Update("UPDATE data_sources SET sync_deletions = #{syncDeletions} "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int updateSyncDeletions(@Param("id") String id, @Param("syncDeletions") boolean syncDeletions);

    /**
     * 对照 Go 的 {@code Where("id = ?", id).Delete(&types.DataSource{})}。
     *
     * <p>{@code gorm.DeletedAt} 让 {@code Delete} 变成
     * {@code UPDATE … SET deleted_at = <now> WHERE id = ? AND deleted_at IS NULL}——
     * **不是**物理删，且**不碰** {@code updated_at}。</p>
     */
    @Update("UPDATE data_sources SET deleted_at = #{now} WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") String id, @Param("now") OffsetDateTime now);
}
