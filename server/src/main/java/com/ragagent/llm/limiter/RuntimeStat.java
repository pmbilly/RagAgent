package com.ragagent.llm.limiter;


/**
 * 单个模型的信号量观测快照，JSON 键逐字段固定：
 * model_id / name / active / waiting / limit。
 *
 * 语义：Active 为进程内在途数；
 * Waiting 故意保持进程内（等待者阻塞在应用进程里）。
 */
public record RuntimeStat(
        String modelId,
        String name,
        long active,
        long waiting,
        int limit) {
}
