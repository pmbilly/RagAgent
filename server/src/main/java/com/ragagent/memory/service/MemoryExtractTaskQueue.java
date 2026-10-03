package com.ragagent.memory.service;

import java.time.Duration;

/**
 * 蒸馏任务的投递端口。
 *
 * <p>与 {@code wiki.service.WikiIngestTaskQueue} 同一个取舍：这里用<b>进程内虚拟线程队列</b>
 * （不是 Redis 支撑的分布式队列），端口把"投递语义"与
 * "传输实现"分开。</p>
 *
 * <h2>必须保留的语义（这些是"一轮都不会丢"的一部分，不是实现细节）</h2>
 * <ol>
 *   <li><b>延迟投递</b>：一轮结束后等
 *       {@code ExtractDelay()} 才蒸馏，给连续对话防抖。</li>
 *   <li><b>重试预算</b>：handler 抛异常时最多再试 2 次。</li>
 *   <li><b>失败要能报出来</b>：投递失败时调用方会释放"在途"槽位
 *       （{@code releaseSlot}），否则一个丢掉的任务会把这个主体一直挡到租约过期。
 *       所以本方法**抛异常**而不是静默丢弃。</li>
 * </ol>
 */
public interface MemoryExtractTaskQueue {

    /**
     * 投递一个蒸馏任务。
     *
     * @param delay 首次执行的延迟；非正数表示立刻执行
     * @throws RuntimeException 投递失败
     */
    void enqueue(MemoryExtractPayload payload, Duration delay);
}
