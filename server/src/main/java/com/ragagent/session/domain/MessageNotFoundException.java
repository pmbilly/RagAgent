package com.ragagent.session.domain;

/**
 * 消息不存在。
 *
 * <p>仓储查不到行时抛出；HTTP 层按端点映射成固定的 404 文案
 * （如 "record not found" / "suggestions not found" / "message not found"），
 * 不读本类的 message。所以本类的 message 文案**不是契约**，只用于日志。</p>
 */
public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException() {
        super("message not found");
    }
}
