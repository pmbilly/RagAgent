package com.ragagent.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 对照 Go internal/errors/errors.go 的 AppError。
 * 全局错误响应形态（Go middleware/error_handler.go）：
 * {"success": false, "error": {"code": N, "message": "...", "details": ...}}
 * 注意 details 即使为 null 也输出（Go gin.H 无 omitempty），故此处用 ALWAYS。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AppError(int code, String message, Object details, int httpCode) {

    public static AppError badRequest(String message) {
        return new AppError(ErrorCode.BAD_REQUEST.value(), message, null, 400);
    }

    public static AppError unauthorized(String message) {
        return new AppError(ErrorCode.UNAUTHORIZED.value(), message, null, 401);
    }

    public static AppError forbidden(String message) {
        return new AppError(ErrorCode.FORBIDDEN.value(), message, null, 403);
    }

    public static AppError notFound(String message) {
        return new AppError(ErrorCode.NOT_FOUND.value(), message, null, 404);
    }

    public static AppError conflict(String message) {
        return new AppError(ErrorCode.CONFLICT.value(), message, null, 409);
    }

    public static AppError tooManyRequests(String message) {
        return new AppError(ErrorCode.TOO_MANY_REQUESTS.value(),
                message == null || message.isBlank() ? "too many requests" : message, null, 429);
    }

    public static AppError internal(String message) {
        return new AppError(ErrorCode.INTERNAL_SERVER.value(),
                message == null || message.isBlank() ? "服务器内部错误" : message, null, 500);
    }

    public static AppError serviceUnavailable(String message) {
        return new AppError(ErrorCode.SERVICE_UNAVAILABLE.value(),
                message == null || message.isBlank() ? "服务暂时不可用" : message, null, 503);
    }

    public static AppError validation(String message) {
        return new AppError(ErrorCode.VALIDATION.value(), message, null, 400);
    }

    public AppError withDetails(Object details) {
        return new AppError(code, message, details, httpCode);
    }
}
