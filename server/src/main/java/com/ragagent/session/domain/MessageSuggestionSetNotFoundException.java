package com.ragagent.session.domain;

/**
 * 消息建议集合不存在（仓储查不到行时抛出，控制器按类型分派 404）。
 *
 * <p>仓储抛出、控制器按类型分派 404，不读本类的 message。</p>
 */
public class MessageSuggestionSetNotFoundException extends RuntimeException {

    public MessageSuggestionSetNotFoundException() {
        super("message suggestion set not found");
    }
}
