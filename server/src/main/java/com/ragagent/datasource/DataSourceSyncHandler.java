package com.ragagent.datasource;

import com.ragagent.datasource.domain.DataSourceSyncPayload;

/**
 * 数据源同步任务的执行端口（对照 Go 里注册到 asynq server 的那个
 * {@code datasource:sync} handler，实现在 {@code datasource_service.go}）。
 *
 * <p>本模块（connector 层）只声明端口：{@link InProcessDataSourceSyncTaskQueue}
 * 用它把队列里的任务真正跑起来，而实现由下一步的 service 层提供
 * （与 wiki 的 {@code WikiIngestBatchHandler} / memory 的
 * {@code MemoryExtractionService} 同一处置）。</p>
 *
 * <p><b>生命周期</b>：抛异常 = 本次尝试失败 → 由队列按 {@code maxRetry} 重试；
 * 被中断 = 任务超时/取消（{@code asynq.Timeout(2h)} 的等价物）。</p>
 */
public interface DataSourceSyncHandler {

    /** 执行一次数据源同步（{@code payload} 已从 JSON 反序列化）。 */
    void handle(DataSourceSyncPayload payload);
}
