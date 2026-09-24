package com.ragagent.wiki.service;

/**
 * wiki 后台任务的投递端口（对照 Go 的 {@code interfaces.TaskEnqueuer} +
 * {@code asynq.Client}，见 wiki_ingest.go 的 {@code task.Enqueue(t)} 调用点）。
 *
 * <p><b>为什么是端口而不是直接调 asynq</b>：Go 的 asynq 是 Redis 支撑的分布式任务队列；
 * Java 侧本阶段不引入 Redis 队列，改为<b>进程内虚拟线程队列</b>
 * （{@link InProcessWikiIngestTaskQueue}），与阶段 3 的
 * {@code KnowledgeProcessWorker} 同一取舍（见 {@code docs/known-issues/00-foundation.md}
 * 原 conventions §9「阶段 3 已知差异 1」）。端口把"投递语义"与"传输实现"分开，
 * 将来接 Redis / MQ 时只需换一个实现。</p>
 *
 * <p><b>必须保留的语义</b>（这些是 wiki ingest 正确性的一部分，不是实现细节）：</p>
 * <ol>
 *   <li><b>延迟投递</b>（{@code ProcessIn}）：上传后 30 秒才触发批次，给连续上传防抖。</li>
 *   <li><b>TaskID 合并</b>：带 {@code TaskID} 的任务在已有一个同 id 的任务排队/运行时，
 *       第二次入队<b>不产生新任务</b>。finalize 调度靠它把窗口内的多次触发合并成一次
 *       索引重建；cap 重试与 stale 重检也靠它防惊群。</li>
 *   <li><b>重试预算</b>（{@code MaxRetry}）与<b>重试延迟策略</b>：见
 *       {@link InProcessWikiIngestTaskQueue#retryDelaySeconds}。</li>
 *   <li><b>超时</b>（{@code Timeout}）：60 分钟（ingest）/ 30 分钟（finalize）。</li>
 * </ol>
 */
public interface WikiIngestTaskQueue {

    /**
     * 投递一个任务。对照 Go {@code client.Enqueue(t)}。
     *
     * @return {@code true} = 新任务已入队；{@code false} = 已有同 {@code TaskID} 的任务
     *         在排队或运行，本次被<b>合并</b>（对应 Go 的
     *         {@code asynq.ErrTaskIDConflict} / {@code ErrDuplicateTask}，
     *         调用方把这种"冲突"当作预期的合并信号而非失败）。
     */
    boolean enqueue(WikiIngestTask task);
}
