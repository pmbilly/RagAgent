package com.ragagent.event;

/**
 * EventBus 的失败信号（对照 Go {@code fmt.Errorf("event handler failed for %s: %w", event.Type, err)}
 * 的包装错误，internal/event/event.go:184/234）。
 *
 * <p>message 与 Go 的 %w 链逐字同构：{@code event handler failed for <type>: <原因>}。</p>
 */
public class EventBusException extends RuntimeException {

    public EventBusException(String message) {
        super(message);
    }

    public EventBusException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 对照 Go 的包装格式；cause message 为空时以类名兜底（Go 的 %v 对空 error 不会出现，防御）。 */
    static EventBusException wrap(String eventType, Throwable cause) {
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        return new EventBusException("event handler failed for " + eventType + ": " + reason, cause);
    }
}
