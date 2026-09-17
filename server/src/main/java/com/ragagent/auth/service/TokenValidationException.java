package com.ragagent.auth.service;

/**
 * JWT 签名/结构校验失败的业务异常（对照 Go ValidateToken 返回的 error）。
 * AuthFilter 捕获后按 Go Auth() 语义继续 X-API-Key 通道或 401，
 * 消息保持 Go 原文（如 "invalid token"），仅用于日志。
 */
public class TokenValidationException extends RuntimeException {

    public TokenValidationException(String message) {
        super(message);
    }

    public TokenValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
