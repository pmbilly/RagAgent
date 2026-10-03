package com.ragagent.datasource;

import java.time.Duration;

import com.ragagent.datasource.domain.DataSourceSyncPayload;

/**
 * 同步任务的投递端口（调度器触发同步时的那一次入队调用）。
 *
 * <p>与 {@code wiki.service.WikiIngestTaskQueue} / {@code memory.service.MemoryExtractTaskQueue}
 * 同一个取舍：传输是<b>进程内虚拟线程队列</b>，
 * 端口把"投递语义"与"传输实现"分开。多副本部署要恢复跨实例去重，换一个
 * Redis/MQ 实现即可（端口就是为此留的）。</p>
 *
 * <h2>必须保留的四条语义（它们是调度器两层级去重的一半）</h2>
 * <ol>
 *   <li><b>确定性 TaskID 去重</b>：同一个 ID 的任务
 *       全局只入队一次，后到者得到 {@link Outcome#TASK_ID_CONFLICT}。调度 cron
 *       按<b>绝对墙钟</b>触发（{@code "0 0 * * * *"} 永远是整点），多实例会在同一分钟
 *       一起触发——TaskID 就是让"只有第一个赢"的那一层。</li>
 *   <li><b>重试预算</b>：失败后最多重试 {@code maxRetry} 次。</li>
 *   <li><b>任务超时</b>：单次执行超时 {@code timeout}——超时后任务被取消，
 *       实现为"中断执行线程"（见 {@link Connector#sleep} 的取消语义）。</li>
 *   <li><b>失败要能报出来</b>：投递失败时调度器会把 sync_log 置为 {@code failed}
 *       并写下 {@code "enqueue failed: <原因>"}，所以本方法<b>抛异常</b>而不是静默丢弃。</li>
 * </ol>
 */
public interface DataSourceSyncTaskQueue {

    /** 队列名。 */
    String QUEUE_SYNC = "sync";

    /** 任务类型名。 */
    String TASK_TYPE_DATA_SOURCE_SYNC = "datasource:sync";

    /** 投递结果。 */
    enum Outcome {
        /** 成功入队。 */
        ENQUEUED,
        /**
         * 已有同 TaskID 的任务在队列/执行中。
         *
         * <p>这是<b>正常结果</b>而不是错误：调度器会把它记成
         * {@code sync_log.status = canceled} + {@code "deduplicated: another instance enqueued first"}。</p>
         */
        TASK_ID_CONFLICT
    }

    /**
     * 投递一个数据源同步任务。
     *
     * @param payload  任务载荷（JSON 化的 {@link DataSourceSyncPayload} 由实现负责）
     * @param taskId   确定性任务 ID（{@code "dssync:<dsID>:<yyyyMMddHHmm>"}）
     * @param maxRetry 失败后的最大重试次数
     * @param timeout  单次执行的超时
     * @return {@link Outcome#ENQUEUED} 或 {@link Outcome#TASK_ID_CONFLICT}
     * @throws DataSourceSyncEnqueueException 其它投递失败（TaskID 冲突以外的失败）
     */
    Outcome enqueue(DataSourceSyncPayload payload, String taskId, int maxRetry, Duration timeout);
}
