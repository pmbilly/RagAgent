package com.ragagent.common.error;

/**
 * Go handler 里 {@code c.JSON(status, gin.H{"error": "消息"})} 的直写形态——
 * **纯字符串**错误体（不是 AppError 信封），状态码任意（400/404/409/500…）。
 *
 * <p>与 {@link GuardForbiddenException}（恒 403 + "Forbidden: " 前缀）同族但更通用：
 * system admin 组的 promote/revoke/reset-password 等端点大量使用该形态
 * （如 404 {"error":"User not found"}、400 {"error":"Cannot revoke your own
 * system admin privileges"}）。global handler 按消息渲染
 * {@code {"error":"..."}}，不经 AppError 信封。</p>
 */
public class PlainErrorException extends RuntimeException {

    private final int status;

    public PlainErrorException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static PlainErrorException badRequest(String message) {
        return new PlainErrorException(400, message);
    }

    public static PlainErrorException notFound(String message) {
        return new PlainErrorException(404, message);
    }

    public static PlainErrorException internal(String message) {
        return new PlainErrorException(500, message);
    }
}
