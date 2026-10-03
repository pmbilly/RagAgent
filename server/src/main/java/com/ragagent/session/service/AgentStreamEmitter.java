package com.ragagent.session.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.common.llm.ResponseType;
import com.ragagent.stream.StreamEvent;
import com.ragagent.stream.StreamManager;

/**
 * {@code AgentStreamBridge} 的 SSE 发射缝：组装 {@link StreamEvent}（时间戳取当前时刻）并追加到
 * {@link StreamManager}；**追加失败只记日志**（不打断本轮）。
 *
 * <p>为什么单独一类：桥里 17 个 handler 各抄了一份同样的"组装 + try 追加 + catch 日志"样板，
 * 且日志文案逐个不同。这里把样板收成 {@link #emit}/{@link #emitTolerant}（组装 + 追加）与
 * {@link #append}/{@link #appendTolerant}/{@link #appendAll}（只追加，给需要额外字段的事件）；
 * <b>日志文案由调用方逐字传入</b>，级别区分"可容忍"（warn，如 session_title 的流可能已结束）与
 * 常规（error）。</p>
 */
final class AgentStreamEmitter {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamEmitter.class);

    private final String sessionId;
    private final String assistantMessageId;
    private final StreamManager streamManager;

    AgentStreamEmitter(String sessionId, String assistantMessageId, StreamManager streamManager) {
        this.sessionId = sessionId;
        this.assistantMessageId = assistantMessageId;
        this.streamManager = streamManager;
    }

    /** 组装事件（时间戳取当前时刻；{@code data} 可为空）。 */
    StreamEvent event(String id, ResponseType type, String content, boolean done,
                      Map<String, Object> data) {
        StreamEvent se = new StreamEvent();
        se.setId(id);
        se.setType(type);
        se.setContent(content);
        se.setDone(done);
        se.setTimestamp(OffsetDateTime.now());
        se.setData(data);
        return se;
    }

    /** 组装并追加；失败按 error 记一条（文案 = {@code failureLog + ": " + 异常}）。 */
    void emit(String id, ResponseType type, String content, boolean done,
              Map<String, Object> data, String failureLog) {
        append(event(id, type, content, done, data), failureLog);
    }

    /** 组装并追加；失败按 warn 记一条（流可能已结束的宽容路径）。 */
    void emitTolerant(String id, ResponseType type, String content, boolean done,
                      Map<String, Object> data, String failureLog) {
        appendTolerant(event(id, type, content, done, data), failureLog);
    }

    /** 追加；失败 error 级记日志。 */
    void append(StreamEvent event, String failureLog) {
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, event);
        } catch (RuntimeException e) {
            log.error("{}: {}", failureLog, e.toString());
        }
    }

    /** 追加；失败 warn 级记日志。 */
    void appendTolerant(StreamEvent event, String failureLog) {
        try {
            streamManager.appendEvent(sessionId, assistantMessageId, event);
        } catch (RuntimeException e) {
            log.warn("{}: {}", failureLog, e.toString());
        }
    }

    /**
     * 顺序追加一组事件、共用一个 try/catch（第一个失败则后续不再尝试——与逐个裸 append 同语义，
     * 但日志只记一条，如 complete 的 fallback answer 对）。
     */
    void appendAll(String failureLog, StreamEvent... events) {
        try {
            for (StreamEvent event : List.of(events)) {
                streamManager.appendEvent(sessionId, assistantMessageId, event);
            }
        } catch (RuntimeException e) {
            log.error("{}: {}", failureLog, e.toString());
        }
    }
}
