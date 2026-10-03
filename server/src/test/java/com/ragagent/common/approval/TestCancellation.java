package com.ragagent.common.approval;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试用取消信号（{@link Cancellation} 的可手动触发实现）。
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
            // 已取消时注册的回调立即执行
            action.run();
            return () -> {
            };
        }
        actions.add(action);
        return () -> actions.remove(action);
    }

    /** 触发一次（重复调用无副作用） */
    void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable action : actions) {
                action.run();
            }
        }
    }
}
