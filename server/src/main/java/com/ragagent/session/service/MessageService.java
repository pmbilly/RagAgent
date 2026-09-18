package com.ragagent.session.service;

import org.springframework.stereotype.Service;

import com.ragagent.common.context.TenantContext;
import com.ragagent.session.domain.Message;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.session.mapper.SessionRepository;

/**
 * 消息的**最小读路径**（对照 Go {@code internal/application/service/message.go}
 * 的 {@code GetMessage}）。
 *
 * <p>本阶段只落 {@code continue-stream} 需要的那一个读方法；
 * 消息 CRUD、分页、附件暂存等随各自端点补。</p>
 *
 * <h2>与 Go 的一处未接线差异</h2>
 * <p>Go 用 {@code sessionUserIDForLookup(ctx)}（带共享 agent 的租户范围分支），
 * Java 照抄了同一个函数（见 {@link SessionService#sessionUserIDForLookup()}），
 * 但那条分支背后的机制属阶段 7，目前恒为 false——所以这里的查询**始终带 user 范围**。
 * 差异方向是偏保守的（Go 放行的，Java 可能 404），不是漏洞。</p>
 */
@Service
public class MessageService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public MessageService(SessionRepository sessionRepository, MessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 对照 Go {@code messageService.GetMessage}（L105-128）：
     * **先按读可见性确认会话可读**，再取消息。
     *
     * <p>会话那一步不能省——只查 {@code (session_id, message_id)} 会绕过
     * 本函数里 {@code loadSessionForRead} 建立的那套可见性判定。</p>
     */
    public Message getMessage(String sessionId, String messageId) {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("types.TenantIDContextKey not set in context");
        }
        SessionService.loadSessionForRead(
                sessionRepository, tenantId, SessionService.sessionUserIDForLookup(), sessionId);
        return messageRepository.getMessage(sessionId, messageId);
    }
}
