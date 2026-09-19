package com.ragagent.sandbox.runtime;

/**
 * 对照 Go 哨兵 {@code sandbox.ErrUnsafeOutboundURL}（internal/sandbox/url_guard.go L36）：
 * 端点用了不支持的 scheme、或解析到策略禁止的地址时返回。
 * Go 侧消息形如 {@code "sandbox: unsafe outbound URL: <原因>"}（fmt.Errorf %w 包装），
 * Java 侧由构造处拼全量消息。
 */
public class UnsafeOutboundURLException extends RuntimeException {
    public UnsafeOutboundURLException(String message) {
        super(message);
    }
}
