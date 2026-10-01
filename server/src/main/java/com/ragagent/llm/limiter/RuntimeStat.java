package com.ragagent.llm.limiter;


/**
 * 对照 Go limiter.RuntimeStat（limiter.go），json tag 逐字段对齐：
 * model_id / name / active / waiting / limit。
 *
 * 语义：Active 对 Redis 后端是集群级、对本地信号量是进程内；
 * Waiting 故意保持进程内（等待者阻塞在应用进程里，Redis 中不体现）。
 */
public record RuntimeStat(
        String modelId,
        String name,
        long active,
        long waiting,
        int limit) {
}
