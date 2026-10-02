package com.ragagent.agent.tools;

import com.ragagent.event.Event;
import com.ragagent.event.EventBus;

/**
 * tools 包 ↔ common.approval 包的类型桥（两个包面之间的显式适配）。
 *
 * <ul>
 *   <li>{@code ToolCancellation}（ctx.Err() 语义）→ {@code approval.Cancellation}
 *       （isCancelled + onCancel 最小面；工具侧没有"取消时回调"的注册点，实现为 no-op）；</li>
 *   <li>{@code event.EventBus / event.Event} → {@code approval.EventBus / approval.Event}
 *       （gate 只发不订——逐字段转投真实总线）。</li>
 * </ul>
 */
public final class ApprovalBridge {

    private ApprovalBridge() {
    }

    public static com.ragagent.common.approval.Cancellation toCancellation(ToolCancellation cancellation) {
        if (cancellation == null) {
            return com.ragagent.common.approval.Cancellation.none();
        }
        return new com.ragagent.common.approval.Cancellation() {
            @Override
            public boolean isCancelled() {
                return cancellation.cancellationError() != null;
            }

            /**
             * 工具侧没有"取消时回调"的注册点，曾实现为 no-op——代价是用户点停止后
             * 10 分钟的人工审批等待照跑（烧 token/占沙箱）。这里用探测线程桥接
             * 轮询式 ToolCancellation → 回调式 onCancel：轮询到取消即触发 action，
             * 注册被 close（finally）后停止观察。
             */
            @Override
            public AutoCloseable onCancel(Runnable action) {
                if (action == null) {
                    return () -> {
                    };
                }
                java.util.concurrent.atomic.AtomicBoolean settled =
                        new java.util.concurrent.atomic.AtomicBoolean();
                Thread.ofVirtual().name("approval-cancel-watch").start(() -> {
                    while (!settled.get()) {
                        if (cancellation.cancellationError() != null) {
                            if (settled.compareAndSet(false, true)) {
                                try {
                                    action.run();
                                } catch (RuntimeException ignored) {
                                    // 取消回调失败不外泄（gate 自己的 deliver 会兜底）
                                }
                            }
                            return;
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                });
                return () -> settled.set(true);
            }
        };
    }

    static com.ragagent.common.approval.EventBus toEventBus(EventBus bus) {
        if (bus == null) {
            return null;
        }
        return approvalEvent -> bus.emit(new Event(
                approvalEvent.id(),
                approvalEvent.type() == null ? "" : approvalEvent.type().value(),
                approvalEvent.sessionId(),
                approvalEvent.data(),
                approvalEvent.metadata(),
                approvalEvent.requestId()));
    }
}
