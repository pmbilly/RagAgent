package com.ragagent.wiki.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragagent.wiki.domain.TaskDeadLetter;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code task_dead_letters} 仓储语句（对照 Go
 * repository/task_queue.go 的 {@code taskDeadLetterRepository}）。
 *
 * <p>增删查全部由 {@link BaseMapper} 覆盖：{@code insert}（Go {@code Insert}）、
 * 按 scope / task_type 的游标分页查询（Go {@code ListByScope} / {@code ListByTaskType}，
 * 顺序 {@code failed_at DESC, id DESC}——由索引
 * {@code idx_task_dead_letters_scope (scope, scope_id, failed_at DESC)} 覆盖）、
 * {@code deleteById}（Go {@code DeleteByID}）。</p>
 *
 * <p>本 Mapper 目前<b>不需要</b>自定义 SQL 语句，存在只为让
 * {@link TaskDeadLetterRepository} 有独立的注入点，并让"这张表归 wiki 模块"在
 * 目录结构上一目了然。</p>
 */
@Mapper
public interface TaskDeadLetterMapper extends BaseMapper<TaskDeadLetter> {
}
