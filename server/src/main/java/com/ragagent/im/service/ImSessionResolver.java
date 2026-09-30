package com.ragagent.im.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.ragagent.im.domain.ChannelSessionEntity;
import com.ragagent.im.runtime.ImFormat;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.session.domain.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IM 消息的会话解析：user / thread 两种模式的映射查找与首建（含并发撞唯一约束时的
 * 孤儿会话回收回落）。
 */
final class ImSessionResolver {

    private static final Logger log = LoggerFactory.getLogger(ImSessionResolver.class);

    private final ImService service;

    ImSessionResolver(ImService service) {
        this.service = service;
    }

    // ── 会话解析（service.go L2237-2444） ────────────────────────────────

    ChannelSessionEntity resolveSession(IncomingMessage msg, long tenantId, String agentId,
            String imChannelId, String sessionMode) {
        if (ImTypes.SESSION_MODE_THREAD.equals(sessionMode)) {
            return resolveThreadSession(msg, tenantId, agentId, imChannelId);
        }
        return resolveUserSession(msg, tenantId, agentId, imChannelId);
    }


    ChannelSessionEntity resolveUserSession(IncomingMessage msg, long tenantId,
            String agentId, String imChannelId) {
        ChannelSessionEntity cs = service.channelSessions.findUserSession(msg.platform, msg.userId,
                msg.chatId, tenantId, agentId);
        if (cs != null) {
            return cs;
        }
        // 有文本就以 "" 起头（首条消息后按内容起标题）；否则用 IM 身份标题。
        Session created = createImSession(tenantId,
                ImFormat.imInitialSessionTitle(msg, ImFormat::buildUserSessionTitle),
                "Auto-created from " + msg.platform + " IM integration");
        ChannelSessionEntity fresh = newMapping(msg, tenantId, agentId, imChannelId, created.getId());
        fresh.setChatId(msg.chatId);
        return insertMapping(fresh, created);
    }


    ChannelSessionEntity resolveThreadSession(IncomingMessage msg, long tenantId,
            String agentId, String imChannelId) {
        String threadId = msg.threadId;
        if (threadId == null || threadId.isEmpty()) {
            // 纵深防御：前端挡住不支持平台的 thread 模式；真空 thread 回落 user 模式，
            // 避免所有空 thread 消息共享一个会话。
            log.warn("[IM] Thread mode but ThreadID is empty (platform={} chat={}), falling back to user session",
                    msg.platform, msg.chatId);
            return resolveUserSession(msg, tenantId, agentId, imChannelId);
        }
        ChannelSessionEntity cs = service.channelSessions.findThreadSession(msg.platform, msg.chatId,
                threadId, tenantId, agentId);
        if (cs != null) {
            return cs;
        }
        Session created = createImSession(tenantId,
                ImFormat.imInitialSessionTitle(msg, ImFormat::buildThreadSessionTitle),
                "Thread-based session from " + msg.platform + " IM");
        ChannelSessionEntity fresh = newMapping(msg, tenantId, agentId, imChannelId, created.getId());
        fresh.setChatId(msg.chatId);
        fresh.setThreadId(threadId);
        return insertMapping(fresh, created);
    }


    Session createImSession(long tenantId, String title, String description) {
        Session s = new Session();
        s.setTenantId(tenantId);
        s.setTitle(title);
        s.setDescription(description);
        return service.sessionService.createSession(s);
    }


    static ChannelSessionEntity newMapping(IncomingMessage msg, long tenantId,
            String agentId, String imChannelId, String sessionId) {
        ChannelSessionEntity e = new ChannelSessionEntity();
        e.setId(UUID.randomUUID().toString());
        e.setPlatform(msg.platform);
        // Go 零值语义：空串不是 NULL（H2 显式 NULL 不落列 DEFAULT）
        e.setUserId(msg.userId == null ? "" : msg.userId);
        e.setChatId(msg.chatId == null ? "" : msg.chatId);
        e.setThreadId(msg.threadId == null ? "" : msg.threadId);
        e.setSessionId(sessionId);
        e.setTenantId(tenantId);
        e.setAgentId(agentId == null ? "" : agentId);
        e.setImChannelId(imChannelId == null ? "" : imChannelId);
        e.setStatus("active");
        e.setMetadata("{}");
        return e;
    }


    ChannelSessionEntity insertMapping(ChannelSessionEntity fresh, Session created) {
        try {
            service.channelSessions.insert(fresh, OffsetDateTime.now());
            log.info("[IM] Created new session mapping: session={}", created.getId());
            return fresh;
        } catch (RuntimeException e) {
            log.error("[IM] channel session insert failed: {}", e.toString(), e);
            // 并发创建撞唯一约束：清掉孤儿会话，回落已存在映射（Go L2320-2340 同形）。
            try {
                service.sessionService.deleteSession(created.getId());
            } catch (Exception cleanup) {
                log.warn("[IM] Failed to clean up orphaned session {}: {}",
                        created.getId(), cleanup.getMessage());
            }
            ChannelSessionEntity existing = service.channelSessions.findUserSession(fresh.getPlatform(),
                    fresh.getUserId(), fresh.getChatId(), fresh.getTenantId(), fresh.getAgentId());
            if (existing == null) {
                throw new IllegalStateException("create channel session: " + e.getMessage(), e);
            }
            return existing;
        }
    }
}
