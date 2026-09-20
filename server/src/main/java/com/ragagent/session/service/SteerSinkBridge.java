package com.ragagent.session.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.agent.SteerSink;
import com.ragagent.common.context.TenantContext;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MentionedItem;
import com.ragagent.stream.StreamBatch;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * handler 侧的 steer back half（对照 Go internal/handler/session/steer.go 的
 * {@code steerSink}，qa.go 在 setupSSEStream 里 per-run 构造并经 SetSteerSink
 * 交给引擎）。
 *
 * <p>通过共享 StreamManager 读 steer 子列表、把接受的消息按运行请求 ID 落成
 * user 角色行。消费标记 {@code consumed} 挂在事件本身上（不存进程内存），
 * 这样每个副本对"还有哪些待处理"结论一致。</p>
 *
 * <p>Java 侧差异：Go 的方法带 ctx；Java 的 {@link SteerSink} 接口无 ctx——
 * 构造期捕获 {@link TenantContextSnapshot}，每个入口 replay（finally clear）。</p>
 */
public final class SteerSinkBridge implements SteerSink {

    private static final Logger log = LoggerFactory.getLogger(SteerSinkBridge.class);

    private final String sessionId;
    private final String requestId;
    private final Message assistantMessage;
    private final MessageService messageService;
    private final StreamManager streamManager;
    private final com.ragagent.event.TenantContextSnapshot tenant;

    private final Object mu = new Object();
    private String lastUserMessageID = "";
    private int drainedOffset;
    private Set<String> injectedIDs = new LinkedHashSet<>();

    public SteerSinkBridge(
            String sessionId, String requestId, Message assistantMessage,
            MessageService messageService, StreamManager streamManager,
            com.ragagent.event.TenantContextSnapshot tenant) {
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.assistantMessage = assistantMessage;
        this.messageService = messageService;
        this.streamManager = streamManager;
        this.tenant = tenant;
    }

    // ── 对照 PollSteer（steer.go L98-132） ──────────────────────────────────

    @Override
    public List<Map<String, Object>> pollSteer(String sid, String messageId, int lastOffset) {
        tenant.replay();
        try {
            return pollSteerInner(messageId, lastOffset);
        } finally {
            TenantContext.clear();
        }
    }

    private List<Map<String, Object>> pollSteerInner(String messageId, int lastOffset) {
        // Always read from the start so an after→inject promote of an already
        // skipped event is visible on the next drain. Consumed injects are
        // filtered by injectedIDs rather than offset.
        StreamBatch batch = streamManager.getSteerEvents(sessionId, messageId, 0);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (StreamEvent evt : batch.events()) {
            if (QaSupport.STEER_DELIVERY_AFTER.equals(QaSupport.steerDeliveryOfEvent(evt))) {
                continue;
            }
            if (hasInjected(evt.getId()) || QaSupport.steerEventConsumed(evt)) {
                continue;
            }
            if (out.size() >= QaSupport.STEER_DRAIN_BATCH_LIMIT) {
                break;
            }
            markInjected(evt.getId());
            out.add(steerEventToRaw(evt));
        }
        synchronized (mu) {
            if (batch.nextOffset() > drainedOffset) {
                drainedOffset = batch.nextOffset();
            }
        }
        return out;
    }

    /** 对照 steerEventToRaw（steer.go L184-200）。 */
    private static Map<String, Object> steerEventToRaw(StreamEvent evt) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", evt.getId());
        raw.put("content", evt.getContent());
        Map<String, Object> data = evt.getData();
        if (data != null) {
            Object m = data.get("mentioned_items");
            if (m instanceof List<?> l) {
                raw.put("mentioned_items", l);
            }
            Object ch = data.get("channel");
            if (ch instanceof String s) {
                raw.put("channel", s);
            }
            Object d = data.get("delivery");
            if (d instanceof String s) {
                raw.put("delivery", s);
            }
        }
        return raw;
    }

    private boolean hasInjected(String id) {
        if (QaSupport.isEmpty(id)) {
            return false;
        }
        synchronized (mu) {
            return injectedIDs.contains(id);
        }
    }

    private void markInjected(String id) {
        if (QaSupport.isEmpty(id)) {
            return;
        }
        synchronized (mu) {
            injectedIDs.add(id);
        }
    }

    private void unmarkInjected(String id) {
        if (QaSupport.isEmpty(id)) {
            return;
        }
        synchronized (mu) {
            injectedIDs.remove(id);
        }
    }

    /** 对照 InjectedIDs（steer.go L165-174）：引擎已消费的 steer 事件 ID 副本。 */
    public Set<String> injectedIds() {
        synchronized (mu) {
            return new LinkedHashSet<>(injectedIDs);
        }
    }

    /** 对照 DrainedOffset（steer.go L178-182）。 */
    public int drainedOffset() {
        synchronized (mu) {
            return drainedOffset;
        }
    }

    // ── 对照 PersistSteerMessage（steer.go L211-276） ────────────────────────

    @Override
    public String persistSteerMessage(String sid, String messageId, String steerId,
            String content, Object mentionedItems, String channel) {
        tenant.replay();
        try {
            return persistSteerInner(messageId, steerId, content, mentionedItems, channel);
        } finally {
            TenantContext.clear();
        }
    }

    private String persistSteerInner(String messageId, String steerId,
            String content, Object mentionedItemsRaw, String channel) {
        if (messageService == null) {
            unmarkInjected(steerId);
            return "";
        }
        String existing = persistedUserMessageId(sessionId, messageId, steerId);
        if (!existing.isEmpty()) {
            synchronized (mu) {
                lastUserMessageID = existing;
            }
            return existing;
        }
        String ch = channel == null || channel.isBlank() ? "web" : channel;
        Message msg = new Message();
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContent(content);
        msg.setRequestId(requestId);
        msg.setMentionedItems(toMentionedItems(mentionedItemsRaw));
        msg.setCreatedAt(java.time.OffsetDateTime.now());
        msg.setCompleted(true);
        msg.setChannel(ch);
        Message created;
        try {
            created = messageService.createMessage(msg);
        } catch (RuntimeException e) {
            log.error("steer persist failed session={} steer={}: {}", sessionId, steerId, e.toString());
            unmarkInjected(steerId);
            return "";
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(QaSupport.STEER_DATA_CONSUMED, true);
        patch.put(QaSupport.STEER_DATA_USER_MESSAGE_ID, created.getId());
        boolean updated;
        try {
            updated = streamManager.updateSteerEventData(sessionId, messageId, steerId, patch);
        } catch (RuntimeException e) {
            log.warn("steer consume flag failed for session {} steer {}: {}",
                    sessionId, steerId, e.toString());
            updated = true; // Go 的 err 分支只记日志继续
        }
        if (!updated) {
            // Deleted concurrently, or the CAS gave up. The user row must not
            // stay around for a retry to insert a second copy of the same steer.
            try {
                messageService.deleteMessage(sessionId, created.getId());
            } catch (RuntimeException e) {
                log.warn("steer persist rollback failed for session {} message {}: {}",
                        sessionId, created.getId(), e.toString());
            }
            unmarkInjected(steerId);
            String again = persistedUserMessageId(sessionId, messageId, steerId);
            if (!again.isEmpty()) {
                synchronized (mu) {
                    lastUserMessageID = again;
                }
                return again;
            }
            return "";
        }
        synchronized (mu) {
            lastUserMessageID = created.getId();
        }
        return created.getId();
    }

    /** 对照 persistedUserMessageID（steer.go L278-292）。 */
    private String persistedUserMessageId(String sid, String messageId, String steerId) {
        if (streamManager == null || QaSupport.isEmpty(steerId)) {
            return "";
        }
        try {
            StreamBatch batch = streamManager.getSteerEvents(sid, messageId, 0);
            for (StreamEvent evt : batch.events()) {
                if (evt.getId().equals(steerId)) {
                    return QaSupport.getString(evt.getData(), QaSupport.STEER_DATA_USER_MESSAGE_ID);
                }
            }
        } catch (RuntimeException e) {
            return "";
        }
        return "";
    }

    /** 对照 LastPersistedUserMessageID（steer.go L296-300）。 */
    public String lastPersistedUserMessageId() {
        synchronized (mu) {
            return lastUserMessageID;
        }
    }

    /** 对照 rawToMentionedItems / types.MentionedItemsFromRaw。 */
    @SuppressWarnings("unchecked")
    static List<MentionedItem> toMentionedItems(Object raw) {
        List<MentionedItem> out = new java.util.ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(MentionedItem.fromRawMap((Map<String, Object>) m));
            }
        }
        return out;
    }
}
