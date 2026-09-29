package com.ragagent.knowledge.task;

import com.ragagent.knowledge.domain.QuestionBatchPayload;

/**
 * 问题生成批任务的投递口。
 * 入队即异步执行、失败按重试预算重试、耗尽后放弃并记日志。</p>
 * <p>投递失败抛异常——调用方（post-process 的 fan-out）据此释放该批占用的 finalizing 槽，
 * 避免行搁浅在 finalizing。</p>
 */
public interface QuestionGenerationTaskQueue {

    /** 投递一个批任务（载荷含追踪载体，worker 侧续接同一棵树）。 */
    void enqueue(QuestionBatchPayload payload);
}
