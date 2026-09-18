package com.ragagent.session.domain;

/**
 * 消息不存在（对照 Go 仓储直接返回的 {@code gorm.ErrRecordNotFound}）。
 *
 * <p>Go 侧没有专门的 {@code ErrMessageNotFound} 哨兵——仓储把 GORM 的原始错误透传上来，
 * 服务层继续透传，最后由 handler 统一落成 **404 "message not found"**（字面量，
 * 不读 err.Error()）。所以本类的 message 文案**不是契约**，只用于日志。</p>
 */
public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException() {
        super("message not found");
    }
}
