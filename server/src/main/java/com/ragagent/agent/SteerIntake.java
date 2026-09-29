package com.ragagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.agent.domain.AgentState;
import com.ragagent.event.Event;
import com.ragagent.event.EventIds;
import com.ragagent.event.EventType;
import com.ragagent.event.UserMessageInjectedData;
import com.ragagent.llm.domain.ChatMessage;

/**
 * 引擎侧 steer（运行中注入）消费协作者：注入内容净化与轮首/收束前 drain。
 *
 * <p>持有 {@link AgentEngine} 回引以访问引擎字段；本类不得独立实例化。</p>
 */
final class SteerIntake {

    private static final Logger log = LoggerFactory.getLogger(SteerIntake.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentEngine engine;

    SteerIntake(AgentEngine engine) {
        this.engine = engine;
    }

    /** 换行归一 + trim，与 chat pipeline 对 user query 的处理一致。 */
    static String sanitizeSteerContent(String content) {
        return content.replace("\r\n", "\n").replace("\r", "\n").trim();
    }

    /**
     * 轮边界 drain steer（对照 drainSteerMessages）：压缩后、下一次 LLM 调用前，注入文本
     * 落在保护尾内、计入 engine.lastSentMsgCount 的增量、下一次调用立即可见。
     * 持久化先于追加：失败留下事件待下次重试——先追加会让模型看到历史没记录的文本。
     *
     * @return 注入条数
     */
    int drainSteerMessages(AgentState state, AgentEngine.MsgRef messagesRef, String sessionId, String messageID) {
        if (engine.steerSink == null) {
            return 0;
        }
        List<Map<String, Object>> events;
        try {
            events = engine.steerSink.pollSteer(sessionId, messageID, 0);
        } catch (RuntimeException e) {
            log.warn("[Agent] Steer poll failed at round {}: {}", state.getCurrentRound() + 1,
                    e.getMessage());
            return 0;
        }
        if (events == null || events.isEmpty()) {
            return 0;
        }

        int injected = 0;
        for (Map<String, Object> evt : events) {
            String content = sanitizeSteerContent(mapString(evt, "content"));
            if (content.isEmpty()) {
                continue;
            }
            String steerID = mapString(evt, "id");
            String userMessageID = engine.steerSink.persistSteerMessage(sessionId, messageID, steerID,
                    content, evt.get("mentioned_items"), mapString(evt, "channel"));
            if (userMessageID == null || userMessageID.isEmpty()) {
                log.warn("[Agent] Steer persist failed for {}, leaving event pending", steerID);
                continue;
            }
            messagesRef.items.add(new ChatMessage("user", steerMessageContent(content)));
            if (state.getPendingSteerMessages() == null) {
                state.setPendingSteerMessages(new ArrayList<>());
            }
            state.getPendingSteerMessages().add(userMessageID);
            engine.eventBus.emit(new Event(EventIds.generateEventID("injected"),
                    EventType.EVENT_USER_MESSAGE_INJECTED, sessionId,
                    new UserMessageInjectedData(steerID, content, messageID, userMessageID), null, ""));
            injected++;
        }
        if (injected > 0) {
            log.info("[Agent][Round-{}] Injected {} steered user message(s) into the turn",
                    state.getCurrentRound() + 1, injected);
        }
        return injected;
    }

    /** 只给模型输入加投递上下文；持久化行与 UI 保留原文（对照 types.SteerMessageContent）。 */
    static String steerMessageContent(String content) {
        return "<steer_message>\n" + content + "\n</steer_message>\n<continue_task>\n"
                + "This is guidance for the task in progress. Apply it and continue unfinished work "
                + "unless the user explicitly changes or cancels the task.\n</continue_task>";
    }

    /** JSON-decoded map 读字符串（对照 types.MapString）。 */
    static String mapString(Map<String, Object> m, String key) {
        if (m == null) {
            return "";
        }
        Object v = m.get(key);
        return v instanceof String s ? s : "";
    }
}
