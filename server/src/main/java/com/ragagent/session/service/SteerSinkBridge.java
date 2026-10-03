package com.ragagent.session.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.agent.SteerSink;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MentionedItem;
import com.ragagent.stream.StreamBatch;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * steer 的 handler 侧后半段：每轮 QA 在建立 SSE 流时构造一个实例，
 * 经 {@code setSteerSink} 交给引擎。
 *
 * <p>通过共享 StreamManager 读 steer 子列表、把接受的消息按运行请求 ID 落成
 * user 角色行。消费标记 {@code consumed} 挂在事件本身上（不存进程内存），
 * 这样每个副本对"还有哪些待处理"结论一致。</p>
 *
 * <p>{@link SteerSink} 接口方法不带租户上下文——
 * 构造期捕获 {@link TenantContextSnapshot}，每个入口先 replay、finally 恢复调用方原上下文。</p>
 */
public final class SteerSinkBridge implements SteerSink {

    private static final Logger log = LoggerFactory.getLogger(SteerSinkBridge.class);

    private final String sessionId;
    private final String requestId;
    private final MessageService messageService;
    private final StreamManager streamManager;
    private final com.ragagent.event.TenantContextSnapshot tenant;

    private final Object mu = new Object();
    private String lastUserMessageID = "";
    private int drainedOffset;
    private Set<String> injectedIDs = new LinkedHashSet<>();

    public SteerSinkBridge(
            String sessionId, String requestId,
            MessageService messageService, StreamManager streamManager,
            com.ragagent.event.TenantContextSnapshot tenant) {
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.messageService = messageService;
        this.streamManager = streamManager;
        this.tenant = tenant;
    }

    // ── pollSteer：drain 待注入的 steer 事件 ────────────────────────────────

    @Override
    public List<Map<String, Object>> pollSteer(String sid, String messageId, int lastOffset) {
        // 借用快照上下文执行：调用方可能是**引擎执行线程本身**（轮首 drainSteerMessages，
        // 该线程已有租户/身份上下文）——必须保存-恢复，不能 clear，否则引擎后续
        // 轮次的模型/KB/工具解析全部丢租户（knowledge_search 检索恒空即此因）。
        // 调用方无上下文时 prev 全空，恢复等价于 clear（跨线程借用场景行为不变）。
        com.ragagent.event.TenantContextSnapshot prev =
                com.ragagent.event.TenantContextSnapshot.capture();
        tenant.replay();
        try {
            return pollSteerInner(messageId, lastOffset);
        } finally {
            prev.replay();
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

    /** 事件 → 引擎侧 raw map（id/content/mentioned_items/channel/delivery）。 */
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

    /** 引擎已消费的 steer 事件 ID 副本。 */
    public Set<String> injectedIds() {
        synchronized (mu) {
            return new LinkedHashSet<>(injectedIDs);
        }
    }

    /** 当前 drain 偏移。 */
    public int drainedOffset() {
        synchronized (mu) {
            return drainedOffset;
        }
    }

    // ── persistSteerMessage：接受的 steer 消息落成 user 行 ───────────────────

    @Override
    public String persistSteerMessage(String sid, String messageId, String steerId,
            String content, Object mentionedItems, String channel) {
        // 同 pollSteer：保存-恢复调用方上下文（引擎线程调用时不得清空租户）。
        com.ragagent.event.TenantContextSnapshot prev =
                com.ragagent.event.TenantContextSnapshot.capture();
        tenant.replay();
        try {
            return persistSteerInner(messageId, steerId, content, mentionedItems, channel);
        } finally {
            prev.replay();
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
            updated = true; // 标记失败只记日志，不回滚
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

    /** 查该 steer 事件已落库的 user 消息 id（没有则空串）。 */
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

    /** 最近一次落库的 user 消息 id。 */
    public String lastPersistedUserMessageId() {
        synchronized (mu) {
            return lastUserMessageID;
        }
    }

    /** 对照 rawToMentionedItems / types.MentionedItemsFromRaw。 */
    @SuppressWarnings("unchecked")
    static List<MentionedItem> toMentionedItems(Object raw) {
        return MentionedItem.fromRawList(raw);
    }
}
