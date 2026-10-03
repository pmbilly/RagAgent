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
 * {@code data_sources} 的基础仓储。
 *
 * <h2>三条必须显式写的理由</h2>
 * <ol>
 *   <li><b>⚠️ 自定义 {@code @Select} 的结果映射**不会**自动套实体上的
 *       {@code @TableField(typeHandler=…)}</b>。
 *       {@code data_sources} 有**三个** jsonb 列，每个读方法都必须重复声明
 *       {@code @Results}——漏了的表现是"库里明明有值、读出来恒为 null"，
 *       H2 与 PG 都会中。</li>
 *   <li><b>软删除 {@code deleted_at IS NULL} 必须显式写</b>：
 *       <b>不用</b> {@code @TableLogic}（datetime 逻辑删除值在 MP 各版本行为敏感），
 *       所以每个读/写都手写这个条件。漏了就会读出让用户"删掉又复活"的行。</li>
 *   <li><b>排序全部显式</b>：本 Mapper 里两处
 *       {@code created_at DESC}，与仓储契约一致。</li>
 * </ol>
 *
 * <h2>jsonb 列不需要 {@code FieldStrategy.ALWAYS}</h2>
 * <p>三个 jsonb 列在迁移 000029 里**都没有 DEFAULT**——MyBatis-Plus 对 null 字段省略该列
 * 恰好落到 SQL NULL（读回即 {@code null}）。
 * （"带 DEFAULT 的 jsonb 列必须 ALWAYS"那条规则只针对 wiki {@code page_metadata} 那类。）</p>
 */
@Mapper
public interface DataSourceMapper extends BaseMapper<DataSource> {

    /**
     * 按 id 取未删除行。
     *
     * <p>带 {@code ORDER BY id LIMIT 1}（主键唯一，等于无排序）。刻意写成
     * {@code LIMIT 1} 而不是依赖结果集大小。</p>
     *
     * @return 未命中回 {@code null}（仓储层转成 {@code "data source not found"}）
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
     * 按知识库取全部未删除行，{@code created_at DESC}。
     *
     * <p>⚠️ 无行时返回空 {@code List} 而不是 {@code null}
     * （仓储契约如此，service 层的 {@code size()} 判定依赖它）。</p>
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
     * 供调度器使用：
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
     * 单列更新 {@code sync_deletions}。
     *
     * <p>这是 {@code Create} / {@code Update} 里"把用户选的 {@code false} 从
     * 列默认值替换下救回来"的那一步。它是单列表更新，
     * 且 <b>不带钩子、不自动刷 {@code updated_at}</b>——所以这里也不动它。</p>
     *
     * <p>软删除条件 {@code deleted_at IS NULL} 在这里显式写。</p>
     */
    @Update("UPDATE data_sources SET sync_deletions = #{syncDeletions} "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int updateSyncDeletions(@Param("id") String id, @Param("syncDeletions") boolean syncDeletions);

    /**
     * 软删：等价于
     * {@code UPDATE … SET deleted_at = <now> WHERE id = ? AND deleted_at IS NULL}——
     * **不是**物理删，且**不碰** {@code updated_at}。
     */
    @Update("UPDATE data_sources SET deleted_at = #{now} WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") String id, @Param("now") OffsetDateTime now);
}
