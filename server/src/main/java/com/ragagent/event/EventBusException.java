package com.ragagent.event;

/**
 * EventBus 的失败信号（包装错误）。
 *
 * <p>message 格式：{@code event handler failed for <type>: <原因>}。</p>
 */
public class EventBusException extends RuntimeException {

    public EventBusException(String message) {
        super(message);
    }

    public EventBusException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 包装为标准 message 格式；cause message 为空时以类名兜底（防御）。 */
    static EventBusException wrap(String eventType, Throwable cause) {
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        return new EventBusException("event handler failed for " + eventType + ": " + reason, cause);
    }
}
