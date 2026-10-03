package com.ragagent.session.domain;

/**
 * 消息 + 所属会话标题。
 *
 * <p><b>纯内部结构</b>：只活在搜索管线的合并阶段里，从不直接出现在响应体——
 * 响应里的是 {@link MessageSearchGroupItem}。所以这里不做 Jackson 注解，
 * 用组合携带消息本体（两步查询后在内存拼装）。</p>
 */
public class MessageWithSession {

    private final Message message;
    private String sessionTitle = "";

    public MessageWithSession(Message message, String sessionTitle) {
        this.message = message;
        this.sessionTitle = sessionTitle == null ? "" : sessionTitle;
    }

    public Message getMessage() {
        return message;
    }

    public String getSessionTitle() {
        return sessionTitle;
    }
}
