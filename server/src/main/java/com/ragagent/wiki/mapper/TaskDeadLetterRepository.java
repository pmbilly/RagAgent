package com.ragagent.wiki.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.wiki.domain.TaskDeadLetter;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 死信档案仓储（对照 Go internal/application/repository/task_queue.go 的
 * {@code taskDeadLetterRepository}，接口见 internal/types/interfaces/task_queue.go）。
 *
 * <p>两个写入方：asynq 死信中间件（每归档一个 asynq 任务写一行），以及服务层重试
 * 处理器（每个耗尽批内重试预算的 op 写一行）。<b>Java 侧只有后者</b>——asynq 不存在了
 * （换成进程内虚拟线程队列，见 {@code InProcessWikiIngestTaskQueue}），
 * 队列层的归档由该实现自行处理。</p>
 *
 * <p>读取是运维驱动的：按 scope 列查（定位单个 KB）、按 task_type 列查
 * （跨 KB 找症状）。无 TTL。</p>
 */
@Repository
public class TaskDeadLetterRepository {

    /** 对照 Go 的 limit 钳制区间 [1, 200] */
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 200;

    private final TaskDeadLetterMapper mapper;

    public TaskDeadLetterRepository(TaskDeadLetterMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 对照 Go {@code Insert}：记录一条死信。<b>尽力而为</b>的调用方应当忽略异常
     * （asynq 中间件即如此，避免插入失败掩盖底层任务错误）；wiki 的重试处理器
     * 则把它收集进 settleErrs 以便让批次结算失败可见。
     */
    @Transactional
    public void insert(TaskDeadLetter dl) {
        if (dl.getFailCount() == null) {
            dl.setFailCount(0);
        }
        if (dl.getFailedAt() == null) {
            // Go 侧由 DB 的 failed_at DEFAULT NOW() 填；Java 侧显式给值，
            // 让插入后内存对象的时间戳与库里一致（响应/日志会读到它）
            dl.setFailedAt(OffsetDateTime.now());
        }
        mapper.insert(dl);
    }

    /**
     * 对照 Go {@code ListByScope}：给定 scope 元组下的死信，<b>最新在前</b>，
     * 按 failed-id 游标分页。
     *
     * @param cursor 上一页最旧一条的 id（字符串）；"" = 从最新开始
     * @return 分页结果；{@code nextCursor} 为空串表示到底
     */
    public CursorPage listByScope(String scope, String scopeId, String cursor, int limit) {
        int clamped = clampLimit(limit);
        LambdaQueryWrapper<TaskDeadLetter> q = new LambdaQueryWrapper<TaskDeadLetter>()
                .eq(TaskDeadLetter::getScope, scope)
                .eq(TaskDeadLetter::getScopeId, scopeId)
                .orderByDesc(TaskDeadLetter::getFailedAt)
                .orderByDesc(TaskDeadLetter::getId)
                .last("LIMIT " + clamped);
        applyCursor(q, cursor);
        return page(q, clamped);
    }

    /**
     * 对照 Go {@code ListByTaskType}：给定 task_type 下的死信，游标语义同上。
     */
    public CursorPage listByTaskType(String taskType, String cursor, int limit) {
        int clamped = clampLimit(limit);
        LambdaQueryWrapper<TaskDeadLetter> q = new LambdaQueryWrapper<TaskDeadLetter>()
                .eq(TaskDeadLetter::getTaskType, taskType)
                .orderByDesc(TaskDeadLetter::getFailedAt)
                .orderByDesc(TaskDeadLetter::getId)
                .last("LIMIT " + clamped);
        applyCursor(q, cursor);
        return page(q, clamped);
    }

    /**
     * 对照 Go {@code DeleteByID}：删掉单条死信（例如运维手工重排任务之后）。
     */
    @Transactional
    public void deleteById(long id) {
        mapper.deleteById(id);
    }

    /** 对照 Go 的 {@code ([]*TaskDeadLetter, string, error)} 返回：本页 + 下一页游标 */
    public record CursorPage(List<TaskDeadLetter> rows, String nextCursor) {}

    private CursorPage page(LambdaQueryWrapper<TaskDeadLetter> q, int limit) {
        List<TaskDeadLetter> rows = mapper.selectList(q);
        // 多取一条判"还有下一页"会多一次查询；Go 的实现也是取 limit 条后
        // 用最后一条的 id 作为 nextCursor（满页即认为还有下一页）。
        String next = "";
        if (rows.size() >= limit && !rows.isEmpty()) {
            next = String.valueOf(rows.get(rows.size() - 1).getId());
        }
        return new CursorPage(rows, next);
    }

    private static void applyCursor(LambdaQueryWrapper<TaskDeadLetter> q, String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return;
        }
        try {
            q.lt(TaskDeadLetter::getId, Long.parseLong(cursor.trim()));
        } catch (NumberFormatException e) {
            // 非法游标 = 从头开始（与 Go 的 strconv 失败后忽略等价）
        }
    }

    private static int clampLimit(int limit) {
        if (limit < MIN_LIMIT) {
            return MIN_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
