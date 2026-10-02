package com.ragagent.wiki.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.wiki.domain.TaskPendingOp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code task_pending_ops} 仓储语句。
 *
 * <p>本 Mapper 只放<b>必须用 SQL 表达</b>的原子语句；CRUD 与条件查询直接用
 * {@link BaseMapper} 的 {@code selectList}/{@code insert}/{@code deleteBatchIds}。
 * 调用方见 {@link TaskPendingOpsRepository}。</p>
 */
@Mapper
public interface TaskPendingOpMapper extends BaseMapper<TaskPendingOp> {

    /**
     * 把已认领的行放回未认领态，让下一次认领立刻可再次命中，而不必等认领变陈旧。
     *
     * <p><b>保留 fail_count</b>（只清 {@code claimed_at}），这样重试预算仍能递减。</p>
     */
    @Update({"<script>",
            "UPDATE task_pending_ops SET claimed_at = NULL",
            "WHERE id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    int releaseByIds(@Param("ids") java.util.List<Long> ids);

    /**
     * fail_count 自增并返回新值。
     *
     * <p>原实现用 {@code RETURNING fail_count} 或在事务里先读后写；Java 侧用一条
     * {@code UPDATE ... SET fail_count = fail_count + 1} 再单独读回，包在同一个
     * 事务里（仓储层方法上有 {@code @Transactional}），语义等价。</p>
     */
    @Update("UPDATE task_pending_ops SET fail_count = fail_count + 1 WHERE id = #{id}")
    int incrementFailCount(@Param("id") long id);

    /**
     * 认领写入：仅当行仍"可认领"（未认领，或认领已陈旧）
     * 时才盖上新认领戳。<b>受影响行数 &lt; 目标行数即说明有并发认领者抢走了部分行</b>
     * ——这是对 PG {@code SELECT ... FOR UPDATE SKIP LOCKED} 的可移植替代：
     * 条件更新本身就是原子的，谁先更新成功谁拿到行。
     *
     * @param staleBefore 早于该时刻的认领视为陈旧、可被覆盖
     */
    @Update({"<script>",
            "UPDATE task_pending_ops SET claimed_at = #{now}",
            "WHERE id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "AND (claimed_at IS NULL OR claimed_at &lt; #{staleBefore})",
            "</script>"})
    int claimByIds(@Param("ids") java.util.List<Long> ids,
                   @Param("now") OffsetDateTime now,
                   @Param("staleBefore") OffsetDateTime staleBefore);
    /**
     * 有在途（未结算）ingest op 的 KB 列表——供启动期孤儿任务重放用（B12）。
     *
     * <p>只取 {@code scope_id} 单列：单实例下 KB id 全局唯一，租户由该 KB 的任一行
     * 反查（{@code peekBatch} 取一条即可），避免多列结果集的列名大小写差异（H2 与 PG 不同）。</p>
     */
    @Select("SELECT DISTINCT scope_id FROM task_pending_ops "
            + "WHERE task_type = #{taskType} AND op = 'ingest'")
    List<String> distinctIngestScopeIds(@Param("taskType") String taskType);
}
