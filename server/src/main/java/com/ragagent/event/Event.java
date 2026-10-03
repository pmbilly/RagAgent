package com.ragagent.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 事件包络。
 *
 * <p>字段：ID / Type / SessionID / Data / Metadata / RequestID。
 * 发射路径按引用传递，两处约定用于隔离调用方对象：</p>
 * <ul>
 *   <li>{@link #shallowCopy()}：{@link EventBus} 发射前先做浅拷贝，发射中补出的 UUID
 *       只写在拷贝上，调用方的 Event 对象不被写回。</li>
 *   <li>{@code metadata} 是<b>共享引用</b>：浅拷贝不复制 map，中间件写
 *       {@code duration_ms} 后调用方原对象也能看到。</li>
 * </ul>
 *
 * <p>便捷构造：{@link #newEvent(String, Object)}（metadata 建空 map）；
 * {@link #withSessionId}/{@link #withRequestId}/{@link #withMetadata}
 * 返回新 Event，metadata map 共享，withMetadata 就地写入。</p>
 *
 * <p>注意：本类本身不是线上 JSON 契约（线上契约是 data 里装的 payload 与 StreamEvent），
 * 不参与序列化。</p>
 */
public class Event {

    /** 事件 ID（自动生成 UUID，用于流式更新追踪；同一 id 的 final_answer 分片在客户端重组） */
    private String id = "";

    /** 事件类型（取值见 {@link EventType}） */
    private String type = "";

    /** 会话 ID */
    private String sessionId = "";

    /** 事件数据（payload 对象，见 payload 子包） */
    private Object data;

    /** 事件元数据（可空、浅拷贝间共享） */
    private Map<String, Object> metadata;

    /** 请求 ID */
    private String requestId = "";

    public Event() {
    }

    public Event(String id, String type, String sessionId, Object data,
                 Map<String, Object> metadata, String requestId) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "" : type;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.data = data;
        this.metadata = metadata;
        this.requestId = requestId == null ? "" : requestId;
    }

    /** 构造事件：metadata 建空 map。 */
    public static Event newEvent(String eventType, Object data) {
        Event e = new Event();
        e.type = eventType;
        e.data = data;
        e.metadata = new LinkedHashMap<>();
        return e;
    }

    /** 浅拷贝：字段逐个复制，metadata map 保持<b>同一引用</b>。 */
    public Event shallowCopy() {
        return new Event(id, type, sessionId, data, metadata, requestId);
    }

    /** 返回设置了会话 ID 的新 Event（metadata 共享）。 */
    public Event withSessionId(String sessionId) {
        Event c = shallowCopy();
        c.sessionId = sessionId == null ? "" : sessionId;
        return c;
    }

    /** 返回设置了请求 ID 的新 Event（metadata 共享）。 */
    public Event withRequestId(String requestId) {
        Event c = shallowCopy();
        c.requestId = requestId == null ? "" : requestId;
        return c;
    }

    /**
     * metadata 为 null 时先建 map，再<b>就地</b>写入键值，返回新 Event。
     * 因为 map 是共享引用，原 Event 也能看到这次写入。
     */
    public Event withMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        metadata.put(key, value);
        return shallowCopy();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null ? "" : type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId == null ? "" : sessionId;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId == null ? "" : requestId;
    }

    /**
     * 生成标准 UUID（v4，36 字符小写）。
     * 注意与 {@link EventIds#generateEventID} 不同——那个带类型后缀且只取前 8 位。
     */
    static String newUuid() {
        return UUID.randomUUID().toString();
    }
}
