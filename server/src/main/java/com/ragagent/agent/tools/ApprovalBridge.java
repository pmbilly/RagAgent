package com.ragagent.agent.tools;

import com.ragagent.event.Event;
import com.ragagent.event.EventBus;

/**
 * tools 包 ↔ agent.approval 包的类型桥（4.1 的 approval 面与 4.5c 的工具面之间的
 * 显式适配；不改 4.1 既有文件）。
 *
 * <ul>
 *   <li>{@code ToolCancellation}（ctx.Err() 语义）→ {@code approval.Cancellation}
 *       （isCancelled + onCancel 最小面；工具侧没有"取消时回调"的注册点，实现为 no-op）；</li>
 *   <li>{@code event.EventBus / event.Event} → {@code approval.EventBus / approval.Event}
 *       （gate 只发不订——逐字段转投真实总线）。</li>
 * </ul>
 */
final class ApprovalBridge {

    private ApprovalBridge() {
    }

    static com.ragagent.agent.approval.Cancellation toCancellation(ToolCancellation cancellation) {
        if (cancellation == null) {
            return com.ragagent.agent.approval.Cancellation.none();
        }
        return new com.ragagent.agent.approval.Cancellation() {
            @Override
            public boolean isCancelled() {
                return cancellation.cancellationError() != null;
            }

            @Override
            public AutoCloseable onCancel(Runnable action) {
                return () -> {
                };
            }
        };
    }

    static com.ragagent.agent.approval.EventBus toEventBus(EventBus bus) {
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
