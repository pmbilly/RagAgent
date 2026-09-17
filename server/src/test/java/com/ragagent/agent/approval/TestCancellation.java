package com.ragagent.agent.approval;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试用取消信号（Go 侧对应 {@code context.WithCancel} 的 ctx）。
 */
class TestCancellation implements Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> actions = new CopyOnWriteArrayList<>();

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public AutoCloseable onCancel(Runnable action) {
        if (cancelled.get()) {
            // 对照 Go：已取消的 ctx，select 会立刻走 ctx.Done() 分支
            action.run();
            return () -> {
            };
        }
        actions.add(action);
        return () -> actions.remove(action);
    }

    /** 对照 Go {@code cancel()}：触发一次（重复调用无副作用） */
    void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable action : actions) {
                action.run();
            }
        }
    }
}
