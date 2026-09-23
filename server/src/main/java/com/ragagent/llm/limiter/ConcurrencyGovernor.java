package com.ragagent.llm.limiter;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 对照 Go limiter 的 governor（governor.go）：进程级并发闸门 + 默认 per-model 上限。
 *
 * <p>闸门是进程级的，被每个面向 provider 的模型客户端层（chat、vlm、embedding）共享；
 * 放在这里而不是某个客户端包内，是为了让它们共用同一限流器与同一 per-model 上限而不互相 import。
 *
 * <p><b>节流只作用于后台任务</b>（Go 的判断在 {@code GateNamedN} 这一层，不在 limiter 实现里）：
 * Go 先读进程级 (limiter, 默认上限) 快照，再判断 {@code types.IsBackgroundTask(ctx)}，
 * 交互式 HTTP 路径直接 passthrough（返回 noop release）。Java 用 ThreadLocal 版的
 * {@link BackgroundTaskContext#isBackgroundTask()} 承载同一标记，判断位置与顺序完全一致。
 *
 * <p>生命周期：Go 在启动时由 container.registerLiteModelConcurrencyLimiter 调 SetGovernor
 * 装配；Java 侧由 {@code config.ModelConcurrencyGovernorWiring} 在启动时装配
 * （2026-09-23 走查批补上——此前装配点缺失，闸门从未生效）。装配前 governor=null、
 * limit=0 → 全部放行。Redis 分布式版仍是备案（多实例协调不做的同族取舍），恒走 Lite。
 * 该类是无状态 Spring 组件，按构造器注入方式使用（约定：不用 Lombok）。
 */
@Component
public class ConcurrencyGovernor {

    /**
     * Go 用 RWMutex 保护 (governor, governorN) 这一对值；Java 用一个不可变状态对象 + volatile
     * 引用，使"换后端 + 换默认上限"整体原子可见（避免两个 volatile 字段读串）。
     */
    private record State(ModelConcurrencyLimiter limiter, int limit) {
    }

    private volatile State state = new State(null, 0);

    public ConcurrencyGovernor() {
    }

    /**
     * 对照 Go SetGovernor：装配进程级后台并发闸门与默认 per-model 上限。
     * limiter 为 null 或 limit <= 0 即关闭治理（所有调用放行）。
     */
    public void setGovernor(ModelConcurrencyLimiter limiter, int limit) {
        state = new State(limiter, limit);
    }

    /**
     * 对照 Go SetGlobalLimit：只更新进程级默认 per-model 上限，保留已装配的后端。
     * 供系统设置运行时桥使用（无需重启即可调 model.max_concurrency）；
     * 非正值关闭默认值（自带 MaxConcurrency 的模型仍按自己的上限生效）。
     */
    public void setGlobalLimit(int limit) {
        State current = state;
        state = new State(current.limiter(), limit);
    }

    /** 对照 Go Gate：用进程级默认上限取槽，等价于 {@code gateN(ctx, modelID, 0)} */
    public Release gate(String modelId) {
        return gateN(modelId, 0);
    }

    /** 对照 Go GateN：modelLimit <= 0 时回退到进程级默认上限 */
    public Release gateN(String modelId, int modelLimit) {
        return gateNamedN(modelId, "", modelLimit);
    }

    /**
     * 对照 Go GateNamedN：后台任务且已装配 governor 时按 (modelId, limit) 取并发槽；
     * 否则返回 {@link Release#NOOP}（交互式调用永不被节流）。
     *
     * 永不阻塞到永久：限流器故障或等待被中断都会 fail open（见 ModelConcurrencyLimiter）。
     */
    public Release gateNamedN(String modelId, String modelName, int modelLimit) {
        // 对照 Go: governorMu.RLock(); l, defaultLimit := governor, governorN; RUnlock()
        State current = state;

        int limit = modelLimit;
        if (limit <= 0) {
            limit = current.limit();
        }
        ModelConcurrencyLimiter l = current.limiter();
        if (l == null || limit <= 0 || !BackgroundTaskContext.isBackgroundTask()) {
            return Release.NOOP;
        }
        // 对照 Go 的类型断言：实现支持才记录展示名
        l.setModelName(modelId, modelName);
        Release release = l.acquire(modelId, limit);
        return release == null ? Release.NOOP : release;
    }

    /**
     * 对照 Go RuntimeStats：返回本进程观测到的信号量（Redis 后端报告集群级 Active；
     * 等待者按设计始终是本实例内的）。
     *
     * Go 的 (stats, enabled, err) → Java 的 GovernorStats：后端不支持观测时 enabled=false；
     * err 在 Go 两个实现里只会来自 Redis 后端，Java 当前仅本地后端（无错误通道）。
     */
    public GovernorStats runtimeStats() {
        ModelConcurrencyLimiter l = state.limiter();
        if (!(l instanceof LocalLimiter local)) {
            return new GovernorStats(List.of(), false);
        }
        return new GovernorStats(local.runtimeStats(), true);
    }

    /** 对照 Go RuntimeStats 的三元返回值：stats + 是否可观测（enabled） */
    public record GovernorStats(List<RuntimeStat> stats, boolean enabled) {
        public GovernorStats {
            stats = stats == null ? List.of() : List.copyOf(stats);
        }
    }
}
