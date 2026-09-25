package com.ragagent.knowledge.service;

/**
 * 问题生成批任务的投递口（对照 Go 的 asynq {@code QueueQuestion} + {@code TypeQuestionGeneration}，
 * 入队参数 {@code asynq.MaxRetry(3), asynq.Timeout(30*time.Minute)}——见
 * {@code knowledge_post_process.go:684-690}）。
 *
 * <p>与 {@link ChunkExtractTaskQueue} 同款纪律：Go 用 asynq，Java 侧是进程内队列——
 * 入队即异步执行、失败按重试预算重试、耗尽后放弃并记日志。</p>
 *
 * <p>投递失败抛异常——调用方（post-process 的 fan-out）据此释放该批占用的 finalizing 槽，
 * 避免行搁浅在 finalizing（对照 Go 的 shortfall-release）。</p>
 */
public interface QuestionGenerationTaskQueue {

    /** 投递一个批任务（载荷含追踪载体，worker 侧续接同一棵树）。 */
    void enqueue(QuestionBatchPayload payload);
}
