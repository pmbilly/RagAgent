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
}
