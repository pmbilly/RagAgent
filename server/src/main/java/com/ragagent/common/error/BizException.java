package com.ragagent.common.error;

/**
 * Java 侧承载 AppError 的异常（对照 Go 的 error 返回值 + c.Error() 链）。
 * service 层抛出，由 GlobalExceptionHandler 统一写出。
 */
public class BizException extends RuntimeException {

    private final AppError appError;

    public BizException(AppError appError) {
        super("error code: " + appError.code() + ", error message: " + appError.message());
        this.appError = appError;
    }

    public static BizException badRequest(String message) {
        return new BizException(AppError.badRequest(message));
    }

    public static BizException unauthorized(String message) {
        return new BizException(AppError.unauthorized(message));
    }

    public static BizException forbidden(String message) {
        return new BizException(AppError.forbidden(message));
    }

    public static BizException notFound(String message) {
        return new BizException(AppError.notFound(message));
    }

    public static BizException conflict(String message) {
        return new BizException(AppError.conflict(message));
    }

    public static BizException internal(String message) {
        return new BizException(AppError.internal(message));
    }

    public static BizException serviceUnavailable(String message) {
        return new BizException(AppError.serviceUnavailable(message));
    }

    public AppError appError() {
        return appError;
    }

    /**
     * Go {@code err.Error()} 的 Java 等价文案：沿 cause 链找**最近的 BizException**，用它已带前缀的
     * message（{@code "error code: %d, error message: %s"}）；找不到则用链上最深的非空 message，
     * 再退到 {@code toString()}。
     *
     * <p><b>为什么需要它</b>：Go 侧 AppError 是 {@code error} 值本身，工具出错时
     * {@code return nil, err} 让上层 {@code err.Error()} 天然带前缀；Java 侧工具抛异常后若取
     * {@code e.getMessage()} 再包一层，前缀就丢了——SSE 终止错误帧的 content 会与 Go 不一致
     * （见 `known-issues/09` 第三节）。凡"把异常翻成 Go 的 error 文案"的地方都该走这里。</p>
     */
    public static String wireText(Throwable err) {
        Throwable deepest = null;
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof BizException biz) {
                return biz.getMessage();
            }
            if (t.getMessage() != null && !t.getMessage().isEmpty()) {
                deepest = t;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        if (deepest != null) {
            return deepest.getMessage();
        }
        return err == null ? "" : err.toString();
    }
}
