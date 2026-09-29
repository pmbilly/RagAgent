package com.ragagent.knowledge.task;

import com.ragagent.knowledge.domain.ExtractChunkPayload;

/**
 * 分块图抽取任务的投递口。
 * Timeout 30 分钟），Java 侧是进程内队列——契约保持一致（入队即异步执行、
 * 失败按重试预算重试、耗尽后放弃并记日志）。</p>
 */
public interface ChunkExtractTaskQueue {

    /**
     * 投递一个分块抽取任务（载荷含追踪载体，worker 侧续接同一棵树）。
     * <p>投递失败抛异常——调用方（post-process 的 fan-out）据此释放该分块占用的
     * finalizing 槽，避免行搁浅在 finalizing。</p>
     */
    void enqueue(ExtractChunkPayload payload);
}
