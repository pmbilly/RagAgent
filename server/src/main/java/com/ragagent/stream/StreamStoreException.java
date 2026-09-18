package com.ragagent.stream;

/**
 * 流存储读写失败（对照 Go 里 {@code fmt.Errorf("failed to ...: %w", err)} 那一批包装错误）。
 *
 * <p>Go 用 {@code error} 返回，Java 用非受检异常——由全局异常处理器兜成 500，
 * 与 Go 的"往上抛、由 ErrorHandler 统一成信封"路径一致。</p>
 */
public class StreamStoreException extends RuntimeException {

    public StreamStoreException(String message) {
        super(message);
    }

    public StreamStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
