package com.ragagent.auth.service;

/**
 * JWT 签名/结构校验失败的业务异常。
 * AuthFilter 捕获后继续 X-API-Key 通道或 401，
 * 消息保持锁定原文（如 "invalid token"），仅用于日志。
 */
public class TokenValidationException extends RuntimeException {

    public TokenValidationException(String message) {
        super(message);
    }

    public TokenValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
